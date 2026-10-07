package com.github.highcumontoa.modelservinggatewayjava.governance;

import com.github.highcumontoa.modelservinggatewayjava.batch.BatchSettings;

/**
 * 单个模型版本的承载参数（不可变快照）。
 * 运行中调整通过整体替换快照生效：同一批的判定看到同一套参数，
 * 已在处理中的请求不受影响。
 */
public record VersionCapacity(
        int maxBatchSize,
        long batchWindowMs,
        int queueCapacity,
        long maxQueueWaitMs,
        long executionTimeoutMs,
        int failureThreshold,
        long recoveryMs) {

    /** 调度器使用的批量参数视图。 */
    public BatchSettings toBatchSettings() {
        return new BatchSettings(maxBatchSize, batchWindowMs, queueCapacity,
                maxQueueWaitMs, executionTimeoutMs);
    }
}
