package com.github.highcumontoa.modelservinggatewayjava.governance;

/**
 * 单版本运行状态快照（只读查询结果）：
 * 排队深度、在途数、各类拒绝/降级计数、健康状态与距重试的剩余毫秒。
 */
public record VersionStats(
        String version,
        int queued,
        int inFlight,
        long okCount,
        long rejectedQueueFull,
        long rejectedWaitTimeout,
        long rejectedUnhealthy,
        long degradedExecutionTimeout,
        long degradedInternalError,
        long degradedResultUnavailable,
        HealthState health,
        long retryAfterMs) {
}
