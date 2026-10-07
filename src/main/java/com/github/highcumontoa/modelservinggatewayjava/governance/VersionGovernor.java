package com.github.highcumontoa.modelservinggatewayjava.governance;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchScheduler;
import com.github.highcumontoa.modelservinggatewayjava.gateway.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单版本治理器：持有该版本独立的批量调度器、承载参数快照、
 * 健康状态机与运行计数。每个版本一个实例，队列、参数、健康状态
 * 完全隔离：某个版本变慢或积压不会连累其他版本。
 */
public class VersionGovernor {

    private static final Logger log = LoggerFactory.getLogger(VersionGovernor.class);

    private final String version;
    private final AtomicReference<VersionCapacity> capacity;
    private final BatchScheduler<ScoreRequest, ScoreResponse> scheduler;
    private final VersionHealth health;
    private final VersionMetrics metrics = new VersionMetrics();

    VersionGovernor(String version, VersionCapacity initial,
                    BatchScheduler.BatchExecutor<ScoreRequest, ScoreResponse> executor) {
        this.version = version;
        this.capacity = new AtomicReference<>(initial);
        this.health = new VersionHealth(initial.failureThreshold(), initial.recoveryMs());
        // 调度器每次形成批次时从快照引用取参数：同一批看到同一套参数，
        // 运行中替换快照即生效，不重启、不影响在途批次
        this.scheduler = new BatchScheduler<>("v[" + version + "]",
                () -> capacity.get().toBatchSettings(),
                batch -> {
                    metrics.inFlightChanged(batch.size());
                    try {
                        executor.execute(batch);
                    } finally {
                        metrics.inFlightChanged(-batch.size());
                    }
                });
    }

    public String version() {
        return version;
    }

    /**
     * 提交请求前先做健康接纳判定：不健康时快速以 VERSION_UNHEALTHY 拒绝，
     * 不再入队堆积；该拒绝与普通过载拒绝（QUEUE_FULL/WAIT_TIMEOUT）可区分。
     */
    public CompletableFuture<ScoreResponse> submit(ScoreRequest request) {
        VersionHealth.Admission admission = health.tryAdmit(System.nanoTime());
        if (admission == VersionHealth.Admission.REJECT) {
            metrics.recordRejectedUnhealthy();
            log.info("version {} unhealthy, fast reject requestId={} retryAfterMs={}",
                    version, request.requestId(), health.retryAfterMs(System.nanoTime()));
            CompletableFuture<ScoreResponse> future = new CompletableFuture<>();
            future.completeExceptionally(new GatewayException(RejectReason.VERSION_UNHEALTHY,
                    "version " + version + " is unhealthy, fast rejected"));
            return future;
        }
        return scheduler.submit(request);
    }

    /**
     * 记录一次请求结论：更新分类计数并驱动健康状态机。
     * 降级（超时/异常/结果不可用）与过载拒绝（队列满/等待超时）计为失败信号；
     * 不健康快速拒绝不计入（避免自我强化）。
     */
    public void recordOutcome(ScoreResponse response) {
        if (response.status() == DecisionStatus.OK) {
            metrics.recordOk();
            health.onSuccess();
            return;
        }
        long now = System.nanoTime();
        switch (response.reason()) {
            case EXECUTION_TIMEOUT -> {
                metrics.recordDegradedExecutionTimeout();
                health.onFailure(now);
            }
            case INTERNAL_ERROR -> {
                metrics.recordDegradedInternalError();
                health.onFailure(now);
            }
            case RESULT_UNAVAILABLE -> {
                metrics.recordDegradedResultUnavailable();
                health.onFailure(now);
            }
            case QUEUE_FULL -> {
                metrics.recordRejectedQueueFull();
                health.onFailure(now);
            }
            case WAIT_TIMEOUT -> {
                metrics.recordRejectedWaitTimeout();
                health.onFailure(now);
            }
            case VERSION_UNHEALTHY -> {
                // 已在 submit 时计数，这里不重复计，也不驱动状态机
            }
            default -> {
                // 路由类拒绝与本版本承载无关，不计入
            }
        }
    }

    /** 运行中调整承载参数：整体替换快照，新批次立即看到新参数，在途批次不受影响。 */
    public void updateCapacity(VersionCapacity newCapacity) {
        capacity.set(newCapacity);
        health.updatePolicy(newCapacity.failureThreshold(), newCapacity.recoveryMs());
        log.info("version {} capacity updated: {}", version, newCapacity);
    }

    public VersionCapacity capacity() {
        return capacity.get();
    }

    /** 当前运行状态快照（只读，不干扰调度）。 */
    public VersionStats stats() {
        long now = System.nanoTime();
        return metrics.snapshot(version, scheduler.queuedCount(),
                health.state(), health.retryAfterMs(now));
    }

    /** 重置计数并将健康状态恢复为 HEALTHY（压测场景）；不影响在途请求的判定。 */
    public void resetStats() {
        metrics.reset();
        health.reset();
        log.info("version {} stats reset", version);
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
