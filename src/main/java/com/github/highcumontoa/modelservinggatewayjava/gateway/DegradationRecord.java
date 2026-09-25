package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;

import java.time.Instant;

/** 一次降级事件记录，可被查询。 */
public record DegradationRecord(
        String requestId,
        String modelVersion,
        RejectReason reason,
        String detail,
        Instant at) {
}
