package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.protocol.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ReasonCode;

/** 一条判定的可查询记录：结论、批次、判定依据。 */
public record DecisionRecord(
        String requestId,
        String modelVersion,
        String batchId,
        DecisionStatus status,
        ReasonCode reasonCode,
        String basis,
        long timestamp) {
}
