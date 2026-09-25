package com.github.highcumontoa.modelservinggatewayjava.protocol;

/** 统一入口评分响应；降级/拒绝结论与正常结论结构一致但 status/reasonCode 可区分。 */
public record ScoreResponse(
        String requestId,
        DecisionStatus status,
        String modelVersion,
        Double score,
        String batchId,
        ReasonCode reasonCode,
        String reason) {
}
