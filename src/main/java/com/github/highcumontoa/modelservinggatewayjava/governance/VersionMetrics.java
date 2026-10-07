package com.github.highcumontoa.modelservinggatewayjava.governance;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单版本运行计数：各类拒绝/降级分别计数，便于区分
 * 普通过载拒绝（QUEUE_FULL/WAIT_TIMEOUT）、普通超时/异常降级
 * 与版本不健康导致的快速拒绝（VERSION_UNHEALTHY）。
 * 全部使用原子计数，查询与重置不干扰正常调度；
 * 重置只清零计数，不改变在途请求已得出的判定。
 */
class VersionMetrics {

    private final AtomicLong okCount = new AtomicLong();
    private final AtomicLong rejectedQueueFull = new AtomicLong();
    private final AtomicLong rejectedWaitTimeout = new AtomicLong();
    private final AtomicLong rejectedUnhealthy = new AtomicLong();
    private final AtomicLong degradedExecutionTimeout = new AtomicLong();
    private final AtomicLong degradedInternalError = new AtomicLong();
    private final AtomicLong degradedResultUnavailable = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();

    void recordOk() {
        okCount.incrementAndGet();
    }

    void recordRejectedQueueFull() {
        rejectedQueueFull.incrementAndGet();
    }

    void recordRejectedWaitTimeout() {
        rejectedWaitTimeout.incrementAndGet();
    }

    void recordRejectedUnhealthy() {
        rejectedUnhealthy.incrementAndGet();
    }

    void recordDegradedExecutionTimeout() {
        degradedExecutionTimeout.incrementAndGet();
    }

    void recordDegradedInternalError() {
        degradedInternalError.incrementAndGet();
    }

    void recordDegradedResultUnavailable() {
        degradedResultUnavailable.incrementAndGet();
    }

    void inFlightChanged(int delta) {
        inFlight.addAndGet(delta);
    }

    int inFlight() {
        return inFlight.get();
    }

    VersionStats snapshot(String version, int queued, HealthState health, long retryAfterMs) {
        return new VersionStats(version, queued, inFlight.get(),
                okCount.get(), rejectedQueueFull.get(), rejectedWaitTimeout.get(),
                rejectedUnhealthy.get(), degradedExecutionTimeout.get(),
                degradedInternalError.get(), degradedResultUnavailable.get(),
                health, retryAfterMs);
    }

    /** 清零全部计数（在途数为实时值，随请求完成自然归零，不在此重置）。 */
    void reset() {
        okCount.set(0);
        rejectedQueueFull.set(0);
        rejectedWaitTimeout.set(0);
        rejectedUnhealthy.set(0);
        degradedExecutionTimeout.set(0);
        degradedInternalError.set(0);
        degradedResultUnavailable.set(0);
    }
}
