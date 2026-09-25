package com.github.highcumontoa.modelservinggatewayjava.api;

/** 评分响应：正常、降级与拒绝结果通过 status/reason 可区分。 */
public record ScoreResponse(
        String requestId,
        String modelVersion,
        DecisionStatus status,
        Double score,
        String label,
        RejectReason reason,
        String batchId,
        int batchSize) {

    public static ScoreResponse ok(String requestId, String version, double score, String label,
                                   String batchId, int batchSize) {
        return new ScoreResponse(requestId, version, DecisionStatus.OK, score, label,
                RejectReason.NONE, batchId, batchSize);
    }

    public static ScoreResponse degraded(String requestId, String version, double score,
                                         RejectReason reason, String batchId, int batchSize) {
        return new ScoreResponse(requestId, version, DecisionStatus.DEGRADED, score, "FALLBACK",
                reason, batchId, batchSize);
    }

    public static ScoreResponse rejected(String requestId, String version, RejectReason reason) {
        return new ScoreResponse(requestId, version, DecisionStatus.REJECTED, null, null,
                reason, null, 0);
    }
}
