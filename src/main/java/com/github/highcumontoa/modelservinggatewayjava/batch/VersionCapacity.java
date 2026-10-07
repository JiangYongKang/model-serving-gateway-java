package com.github.highcumontoa.modelservinggatewayjava.batch;

/**
 * 单个模型版本的承载参数快照（不可变）。
 * 调度器在形成每一批时整体读取一次快照，保证同一批判定看到同一套参数；
 * 运行中调整通过整体替换快照生效，不会出现一半旧值一半新值。
 */
public record VersionCapacity(
        int maxBatchSize,
        long batchWindowMs,
        int queueCapacity,
        long maxQueueWaitMs,
        long executionTimeoutMs) {

    public VersionCapacity {
        if (maxBatchSize <= 0 || batchWindowMs <= 0 || queueCapacity <= 0
                || maxQueueWaitMs <= 0 || executionTimeoutMs <= 0) {
            throw new IllegalArgumentException("all capacity parameters must be positive");
        }
    }

    /** 从全局批量配置构造一份快照，作为各版本的默认承载参数。 */
    public static VersionCapacity from(BatchProperties p) {
        return new VersionCapacity(p.getMaxBatchSize(), p.getBatchWindowMs(),
                p.getQueueCapacity(), p.getMaxQueueWaitMs(), p.getExecutionTimeoutMs());
    }
}
