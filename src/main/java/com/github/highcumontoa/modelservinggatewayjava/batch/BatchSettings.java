package com.github.highcumontoa.modelservinggatewayjava.batch;

/**
 * 批量调度参数的不可变快照。
 * 调度器在形成每个批次时取一次快照，保证同一批的判定使用同一套参数；
 * 运行中调整参数通过替换快照生效，不影响已在处理中的批次。
 */
public record BatchSettings(
        int maxBatchSize,
        long batchWindowMs,
        int queueCapacity,
        long maxQueueWaitMs,
        long executionTimeoutMs) {

    /** 从配置属性生成快照。 */
    public static BatchSettings from(BatchProperties p) {
        return new BatchSettings(p.getMaxBatchSize(), p.getBatchWindowMs(),
                p.getQueueCapacity(), p.getMaxQueueWaitMs(), p.getExecutionTimeoutMs());
    }
}
