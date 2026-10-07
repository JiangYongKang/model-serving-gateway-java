package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchScheduler;
import com.github.highcumontoa.modelservinggatewayjava.batch.VersionCapacity;
import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评分网关编排：
 * <ol>
 *   <li>版本路由：严格按请求版本选择本地实现，缺失/未知/已下线/契约不兼容
 *       一律返回可区分的 REJECTED，绝不静默回退默认版本；</li>
 *   <li>按版本车道：每个版本独立的 {@link BatchScheduler}（独立队列与调度线程），
 *       一个版本变慢/积压不影响其他版本；承载参数按版本独立配置、
 *       运行中原子替换生效，同一批判定看到同一套参数快照；</li>
 *   <li>版本健康：持续失败/过载的版本进入 UNHEALTHY 受控状态，快速以
 *       VERSION_UNHEALTHY 拒绝（与普通过载拒绝区分），冷却后经半开试探自动恢复；</li>
 *   <li>降级策略：执行超时、内部异常、结果不可用（NaN/Inf）时，
 *       返回确定性的中性先验分 0.5 + 标签 FALLBACK，状态 DEGRADED，
 *       与正常结论（PASS/FAIL）严格区分，并写入 {@link DegradationLog} 供查询；</li>
 *   <li>运行观测：按版本统计排队深度、在途数量、拒绝/降级计数与健康状态，
 *       可查询、可重置，查询与重置不影响在途请求判定。</li>
 * </ol>
 */
@Component
public class ScoringGateway {

    private static final Logger log = LoggerFactory.getLogger(ScoringGateway.class);

    /** 降级时使用的确定性中性先验分，可解释且可复现。 */
    public static final double FALLBACK_SCORE = 0.5;

    private final ModelRegistry registry;
    private final DegradationLog degradationLog;
    private final VersionCapacityRegistry capacityRegistry;
    private final VersionHealthManager healthManager;
    private final VersionStatsRegistry statsRegistry;
    /** 按版本独立的调度车道：队列、调度线程、批次完全隔离。 */
    private final Map<String, BatchScheduler<ScoreRequest, ScoreResponse>> lanes =
            new ConcurrentHashMap<>();

    @Autowired
    public ScoringGateway(ModelRegistry registry, DegradationLog degradationLog,
                          VersionCapacityRegistry capacityRegistry,
                          VersionHealthManager healthManager,
                          VersionStatsRegistry statsRegistry) {
        this.registry = registry;
        this.degradationLog = degradationLog;
        this.capacityRegistry = capacityRegistry;
        this.healthManager = healthManager;
        this.statsRegistry = statsRegistry;
    }

    /** 兼容旧用法：以全局批量配置作为各版本默认承载参数，健康判定用默认配置。 */
    public ScoringGateway(ModelRegistry registry, DegradationLog degradationLog,
                          BatchProperties properties) {
        this(registry, degradationLog, new VersionCapacityRegistry(properties),
                new VersionHealthManager(new HealthProperties()), new VersionStatsRegistry());
    }

    public ScoreResponse score(ScoreRequest request) {
        log.info("incoming request: {}", request);
        // 1. 版本路由与契约校验（入队前同步完成，失败即拒绝，不占用队列）
        ScoreResponse routingError = validateRouting(request);
        if (routingError != null) {
            log.info("request rejected at routing: requestId={} reason={}",
                    request.requestId(), routingError.reason());
            if (request.modelVersion() != null && !request.modelVersion().isBlank()) {
                statsRegistry.stats(request.modelVersion()).recordRejected(routingError.reason());
            }
            return routingError;
        }
        String version = request.modelVersion();
        // 2. 健康门禁：不健康版本快速失败，不再无意义堆积排队
        if (!healthManager.tryAdmit(version)) {
            long retryMs = healthManager.retryAfterMs(version);
            log.info("request rejected by health gate: requestId={} version={} retryAfterMs={}",
                    request.requestId(), version, retryMs);
            statsRegistry.stats(version).recordRejected(RejectReason.VERSION_UNHEALTHY);
            return ScoreResponse.rejected(request.requestId(), version,
                    RejectReason.VERSION_UNHEALTHY);
        }
        // 3. 进入该版本独立的批量调度车道
        try {
            ScoreResponse response = lane(version).submit(request).join();
            recordOutcome(version, response);
            log.info("decision: requestId={} status={} score={} label={} reason={} batch={}/{}",
                    response.requestId(), response.status(), response.score(),
                    response.label(), response.reason(), response.batchId(), response.batchSize());
            return response;
        } catch (CompletionException e) {
            return handleSchedulerFailure(request, e.getCause());
        }
    }

    /** 按版本查询运行状态快照；无车道的版本返回全零快照。 */
    public VersionStatsSnapshot statsSnapshot(String version) {
        BatchScheduler<ScoreRequest, ScoreResponse> lane = lanes.get(version);
        VersionStats stats = statsRegistry.stats(version);
        return new VersionStatsSnapshot(version,
                healthManager.state(version), healthManager.retryAfterMs(version),
                lane == null ? 0 : lane.queueDepth(),
                lane == null ? 0 : lane.inFlight(),
                stats.okCount(), stats.rejectedCounts(), stats.degradedCounts());
    }

    /** 所有已观测版本的状态快照（按版本名排序，便于展示与断言）。 */
    public List<VersionStatsSnapshot> allStats() {
        return statsRegistry.versions().stream()
                .sorted(Comparator.naturalOrder())
                .map(this::statsSnapshot)
                .toList();
    }

    /** 重置某版本累计计数（仅计数器；不影响在途请求与调度）。 */
    public void resetStats(String version) {
        statsRegistry.reset(version);
    }

    /** 重置全部版本累计计数。 */
    public void resetAllStats() {
        statsRegistry.resetAll();
    }

    /** 运行中调整某版本承载参数：整体替换快照，对新提交/新批次生效。 */
    public void updateCapacity(String version, VersionCapacity capacity) {
        log.info("updating capacity: version={} capacity={}", version, capacity);
        capacityRegistry.update(version, capacity);
    }

    /** 查询某版本当前生效的承载参数。 */
    public VersionCapacity capacityOf(String version) {
        return capacityRegistry.snapshot(version);
    }

    private BatchScheduler<ScoreRequest, ScoreResponse> lane(String version) {
        return lanes.computeIfAbsent(version, v -> new BatchScheduler<>(
                "lane-" + v, () -> capacityRegistry.snapshot(v), this::executeBatch));
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

    /** 结论记账：成功/失败驱动健康状态机，并计入按版本统计。 */
    private void recordOutcome(String version, ScoreResponse response) {
        VersionStats stats = statsRegistry.stats(version);
        if (response.status() == DecisionStatus.OK) {
            healthManager.recordSuccess(version);
            stats.recordOk();
        } else if (response.status() == DecisionStatus.DEGRADED) {
            healthManager.recordFailure(version);
            stats.recordDegraded(response.reason());
        } else {
            // 执行前版本被下线等路由类拒绝：计入统计，不作为健康信号
            stats.recordRejected(response.reason());
        }
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

    /** 调度层失败：过载类原因拒绝，超时类原因降级；两者都计入健康信号。 */
    private ScoreResponse handleSchedulerFailure(ScoreRequest request, Throwable cause) {
        String version = request.modelVersion();
        if (cause instanceof GatewayException ge) {
            return switch (ge.reason()) {
                case QUEUE_FULL, WAIT_TIMEOUT -> {
                    log.info("request rejected by scheduler: requestId={} reason={} detail={}",
                            request.requestId(), ge.reason(), ge.getMessage());
                    healthManager.recordFailure(version);
                    statsRegistry.stats(version).recordRejected(ge.reason());
                    yield ScoreResponse.rejected(request.requestId(), version, ge.reason());
                }
                case EXECUTION_TIMEOUT -> {
                    healthManager.recordFailure(version);
                    statsRegistry.stats(version)
                            .recordDegraded(RejectReason.EXECUTION_TIMEOUT);
                    yield degrade(request, RejectReason.EXECUTION_TIMEOUT,
                            ge.getMessage(), null);
                }
                default -> {
                    healthManager.recordFailure(version);
                    statsRegistry.stats(version).recordDegraded(RejectReason.INTERNAL_ERROR);
                    yield degrade(request, RejectReason.INTERNAL_ERROR,
                            String.valueOf(cause), null);
                }
            };
        }
        healthManager.recordFailure(version);
        statsRegistry.stats(version).recordDegraded(RejectReason.INTERNAL_ERROR);
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
        lanes.values().forEach(BatchScheduler::shutdown);
    }
}
