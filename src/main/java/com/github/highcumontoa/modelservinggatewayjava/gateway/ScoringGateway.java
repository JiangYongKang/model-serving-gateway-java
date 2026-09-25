package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchScheduler;
import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletionException;

/**
 * 评分网关编排：
 * <ol>
 *   <li>版本路由：严格按请求版本选择本地实现，缺失/未知/已下线/契约不兼容
 *       一律返回可区分的 REJECTED，绝不静默回退默认版本；</li>
 *   <li>批量执行：通过 {@link BatchScheduler} 在有限窗口内合批；</li>
 *   <li>降级策略：执行超时、内部异常、结果不可用（NaN/Inf）时，
 *       返回确定性的中性先验分 0.5 + 标签 FALLBACK，状态 DEGRADED，
 *       与正常结论（PASS/FAIL）严格区分，并写入 {@link DegradationLog} 供查询。</li>
 * </ol>
 */
@Component
public class ScoringGateway {

    private static final Logger log = LoggerFactory.getLogger(ScoringGateway.class);

    /** 降级时使用的确定性中性先验分，可解释且可复现。 */
    public static final double FALLBACK_SCORE = 0.5;

    private final ModelRegistry registry;
    private final DegradationLog degradationLog;
    private final BatchScheduler<ScoreRequest, ScoreResponse> scheduler;

    public ScoringGateway(ModelRegistry registry, DegradationLog degradationLog,
                          BatchProperties properties) {
        this.registry = registry;
        this.degradationLog = degradationLog;
        this.scheduler = new BatchScheduler<>(properties, this::executeBatch);
    }

    public ScoreResponse score(ScoreRequest request) {
        log.info("incoming request: {}", request);
        // 1. 版本路由与契约校验（入队前同步完成，失败即拒绝，不占用队列）
        ScoreResponse routingError = validateRouting(request);
        if (routingError != null) {
            log.info("request rejected at routing: requestId={} reason={}",
                    request.requestId(), routingError.reason());
            return routingError;
        }
        // 2. 进入批量调度
        try {
            ScoreResponse response = scheduler.submit(request).join();
            log.info("decision: requestId={} status={} score={} label={} reason={} batch={}/{}",
                    response.requestId(), response.status(), response.score(),
                    response.label(), response.reason(), response.batchId(), response.batchSize());
            return response;
        } catch (CompletionException e) {
            return handleSchedulerFailure(request, e.getCause());
        }
    }

    /** 版本路由校验：任何不满足都返回可区分原因的 REJECTED。 */
    private ScoreResponse validateRouting(ScoreRequest request) {
        String version = request.modelVersion();
        if (version == null || version.isBlank()) {
            return ScoreResponse.rejected(request.requestId(), null, RejectReason.VERSION_MISSING);
        }
        if (registry.isRetired(version)) {
            return ScoreResponse.rejected(request.requestId(), version, RejectReason.VERSION_RETIRED);
        }
        LocalModel model = registry.find(version).orElse(null);
        if (model == null) {
            return ScoreResponse.rejected(request.requestId(), version, RejectReason.VERSION_UNKNOWN);
        }
        if (request.contractVersion() != null
                && request.contractVersion() != model.contractVersion()) {
            return ScoreResponse.rejected(request.requestId(), version,
                    RejectReason.CONTRACT_INCOMPATIBLE);
        }
        if (request.features() == null) {
            return ScoreResponse.rejected(request.requestId(), version,
                    RejectReason.CONTRACT_INCOMPATIBLE);
        }
        return null;
    }

    /** 批量执行：逐条打分；单条异常只影响该条，不污染同批其他结论。 */
    private void executeBatch(BatchScheduler.Batch<ScoreRequest, ScoreResponse> batch) {
        for (BatchScheduler.BatchItem<ScoreRequest, ScoreResponse> item : batch.items()) {
            ScoreRequest req = item.item();
            try {
                LocalModel model = registry.find(req.modelVersion()).orElse(null);
                if (model == null) {
                    // 执行前版本被下线：拒绝而非静默切换版本
                    item.future().complete(ScoreResponse.rejected(
                            req.requestId(), req.modelVersion(), RejectReason.VERSION_RETIRED));
                    continue;
                }
                double raw = model.score(req.features());
                if (!Double.isFinite(raw)) {
                    // 结果不可用：降级，不混入正常结论
                    item.future().complete(degrade(req, RejectReason.RESULT_UNAVAILABLE,
                            "model returned non-finite score: " + raw, batch));
                    continue;
                }
                String label = raw >= 0.5 ? "PASS" : "FAIL";
                item.future().complete(ScoreResponse.ok(req.requestId(), req.modelVersion(),
                        raw, label, batch.id(), batch.size()));
            } catch (Exception e) {
                item.future().complete(degrade(req, RejectReason.INTERNAL_ERROR,
                        String.valueOf(e), batch));
            }
        }
    }

    /** 调度层失败：过载类原因拒绝，超时类原因降级。 */
    private ScoreResponse handleSchedulerFailure(ScoreRequest request, Throwable cause) {
        if (cause instanceof GatewayException ge) {
            return switch (ge.reason()) {
                case QUEUE_FULL, WAIT_TIMEOUT -> {
                    log.info("request rejected by scheduler: requestId={} reason={} detail={}",
                            request.requestId(), ge.reason(), ge.getMessage());
                    yield ScoreResponse.rejected(request.requestId(), request.modelVersion(),
                            ge.reason());
                }
                case EXECUTION_TIMEOUT -> degrade(request, RejectReason.EXECUTION_TIMEOUT,
                        ge.getMessage(), null);
                default -> degrade(request, RejectReason.INTERNAL_ERROR,
                        String.valueOf(cause), null);
            };
        }
        return degrade(request, RejectReason.INTERNAL_ERROR, String.valueOf(cause), null);
    }

    /** 构造降级结果：确定性中性先验分，标签 FALLBACK，并记录可查询的降级原因。 */
    private ScoreResponse degrade(ScoreRequest req, RejectReason reason, String detail,
                                  BatchScheduler.Batch<ScoreRequest, ScoreResponse> batch) {
        degradationLog.record(new DegradationRecord(
                req.requestId(), req.modelVersion(), reason, detail, Instant.now()));
        return ScoreResponse.degraded(req.requestId(), req.modelVersion(), FALLBACK_SCORE,
                reason, batch == null ? null : batch.id(), batch == null ? 0 : batch.size());
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
