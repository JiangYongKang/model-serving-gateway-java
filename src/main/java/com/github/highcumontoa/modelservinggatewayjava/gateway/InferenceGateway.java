package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.batch.BatchOutcome;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchScheduler;
import com.github.highcumontoa.modelservinggatewayjava.batch.SchedulerFailure;
import com.github.highcumontoa.modelservinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modelservinggatewayjava.model.DeterministicLocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ReasonCode;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推理网关：统一入口 → 版本路由/契约校验 → 批量调度 → 正常 / 降级 / 拒绝三类可区分结论。
 *
 * <p>策略（确定性，可复现）：
 * <ul>
 *   <li>版本缺失：拒绝 VERSION_NOT_FOUND；版本已下线：拒绝 VERSION_RETIRED；
 *       契约不兼容：拒绝 CONTRACT_INCOMPATIBLE——以上均不静默回退默认版本；</li>
 *   <li>排队已满 / 排队超时：拒绝 QUEUE_FULL / WAIT_TIMEOUT——过载必须显式拒绝；</li>
 *   <li>模型超时 / 内部异常 / 结果不可用：返回 DEGRADED，附确定性兜底分数（由
 *       “FALLBACK + 版本 + 规范化输入”哈希导出），与正常分数结构分离、绝不混入正常结论。</li>
 * </ul>
 */
@Component
public class InferenceGateway {

    private static final Logger log = LoggerFactory.getLogger(InferenceGateway.class);

    private final ModelRegistry registry;
    private final GatewayProperties properties;
    private final DecisionAuditLog auditLog;
    private final ConcurrentHashMap<String, BatchScheduler> schedulers = new ConcurrentHashMap<>();

    public InferenceGateway(ModelRegistry registry, GatewayProperties properties,
                            DecisionAuditLog auditLog) {
        this.registry = registry;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    public ScoreResponse score(ScoreRequest request) {
        String requestId = (request == null || isBlank(request.requestId()))
                ? "auto-" + java.util.UUID.randomUUID()
                : request.requestId();
        log.info("REQUEST requestId={} version={} contract={} inputs={}",
                requestId,
                request == null ? null : request.modelVersion(),
                request == null ? null : request.contractVersion(),
                request == null ? null : request.inputs());

        if (request == null || isBlank(request.modelVersion())) {
            return reject(requestId, null, null, ReasonCode.VERSION_NOT_FOUND,
                    "model version is required; no default version is used",
                    "missing version -> refuse, never fall back to default");
        }
        String version = request.modelVersion();

        ModelRegistry.Registration registration = registry.find(version).orElse(null);
        if (registration == null) {
            return reject(requestId, version, null, ReasonCode.VERSION_NOT_FOUND,
                    "model version '" + version + "' is not registered",
                    "version lookup empty -> refuse, never fall back to default");
        }
        if (registration.status() == ModelStatus.RETIRED) {
            return reject(requestId, version, null, ReasonCode.VERSION_RETIRED,
                    "model version '" + version + "' has been retired",
                    "lifecycle status=RETIRED -> refuse");
        }
        LocalModel model = registration.model();
        if (request.contractVersion() == null || !model.supportsContract(request.contractVersion())) {
            return reject(requestId, version, null, ReasonCode.CONTRACT_INCOMPATIBLE,
                    "contract '" + request.contractVersion() + "' is not supported by model '"
                            + version + "'",
                    "contract check failed -> refuse, no silent substitution");
        }
        java.util.Optional<String> inputError = model.validateInputs(request.inputs());
        if (inputError.isPresent()) {
            return reject(requestId, version, null, ReasonCode.CONTRACT_INCOMPATIBLE,
                    inputError.get(),
                    "input contract check failed -> refuse, no silent substitution");
        }

        BatchScheduler scheduler = schedulerFor(model);
        try {
            CompletableFuture<BatchOutcome> future = scheduler.submit(request);
            BatchOutcome outcome = future.join();
            String basis = "batch execution normal; batchId=" + outcome.batchId()
                    + "; score=deterministicHash(salt(version), canonical(inputs))";
            ScoreResponse response = new ScoreResponse(requestId, DecisionStatus.NORMAL,
                    version, outcome.score(), outcome.batchId(), null, null);
            auditLog.record(new DecisionRecord(requestId, version, outcome.batchId(),
                    DecisionStatus.NORMAL, null, basis, System.currentTimeMillis()));
            return response;
        } catch (CompletionException e) {
            SchedulerFailure failure = unwrap(e);
            return handleFailure(requestId, version, request, failure);
        }
    }

    private ScoreResponse handleFailure(String requestId, String version,
                                        ScoreRequest request, SchedulerFailure failure) {
        ReasonCode code = failure.reasonCode();
        if (code == ReasonCode.QUEUE_FULL || code == ReasonCode.WAIT_TIMEOUT) {
            String basis = "overload control: " + code + " (" + failure.getMessage() + ")";
            ScoreResponse response = new ScoreResponse(requestId, DecisionStatus.REJECTED,
                    version, null, failure.batchId(), code, failure.getMessage());
            auditLog.record(new DecisionRecord(requestId, version, failure.batchId(),
                    DecisionStatus.REJECTED, code, basis, System.currentTimeMillis()));
            return response;
        }
        // 执行期失败：确定性降级，分数独立计算并与正常分数显式区分
        double fallback = fallbackScore(version, request.inputs());
        String basis = "execution failure " + code + ": " + failure.getMessage()
                + "; fallback=deterministicHash(FALLBACK, version, canonical(inputs))";
        ScoreResponse response = new ScoreResponse(requestId, DecisionStatus.DEGRADED,
                version, fallback, failure.batchId(), code,
                "degraded fallback due to " + code + ": " + failure.getMessage());
        auditLog.record(new DecisionRecord(requestId, version, failure.batchId(),
                DecisionStatus.DEGRADED, code, basis, System.currentTimeMillis()));
        log.warn("DEGRADE requestId={} version={} reason={} fallbackScore={}",
                requestId, version, code, fallback);
        return response;
    }

    private ScoreResponse reject(String requestId, String version, String batchId,
                                 ReasonCode code, String message, String basis) {
        ScoreResponse response = new ScoreResponse(requestId, DecisionStatus.REJECTED,
                version, null, batchId, code, message);
        auditLog.record(new DecisionRecord(requestId, version, batchId,
                DecisionStatus.REJECTED, code, basis, System.currentTimeMillis()));
        log.warn("REJECT requestId={} version={} reason={} basis={}", requestId, version, code, basis);
        return response;
    }

    private BatchScheduler schedulerFor(LocalModel model) {
        return schedulers.computeIfAbsent(model.version(), v -> {
            GatewayProperties.Batch cfg = properties.getBatch();
            return new BatchScheduler(model, cfg.getMaxSize(), cfg.getWindowMs(),
                    cfg.getQueueDepth(), cfg.getQueueWaitMs(), cfg.getModelTimeoutMs());
        });
    }

    /**
     * 确定性兜底分数：仅依赖版本与输入，同版本同输入的降级结论也可复现；
     * 与正常分数使用不同盐值前缀，语义上不混入正常结论。
     */
    static double fallbackScore(String version, java.util.Map<String, Object> inputs) {
        String canonical = "FALLBACK|" + version + "|" + DeterministicLocalModel.canonicalize(inputs);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        long h = 0;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (digest[i] & 0xffL);
        }
        return ((h & Long.MAX_VALUE) % 10_000L) / 10_000.0;
    }

    private static SchedulerFailure unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SchedulerFailure sf) {
                return sf;
            }
            cur = cur.getCause();
        }
        return new SchedulerFailure(ReasonCode.MODEL_ERROR, "unexpected error: " + t);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    public void close() {
        schedulers.values().forEach(BatchScheduler::close);
    }
}
