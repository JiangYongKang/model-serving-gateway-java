package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;

import java.util.Map;

/**
 * 单版本运行状态快照（查询用，只读）：
 * 排队深度、在途数量、各类拒绝/降级计数、健康状态与重试剩余时间。
 */
public record VersionStatsSnapshot(
        String modelVersion,
        HealthState healthState,
        long retryAfterMs,
        int queueDepth,
        int inFlight,
        long okCount,
        Map<RejectReason, Long> rejected,
        Map<RejectReason, Long> degraded) {
}
