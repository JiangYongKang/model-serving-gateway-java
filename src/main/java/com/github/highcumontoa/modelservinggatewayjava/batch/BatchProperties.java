package com.github.highcumontoa.modelservinggatewayjava.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 批量调度与过载控制配置。 */
@ConfigurationProperties(prefix = "gateway.batch")
public class BatchProperties {

    /** 单批最大请求数。 */
    private int maxBatchSize = 8;

    /** 合批窗口（毫秒）。 */
    private long batchWindowMs = 20;

    /** 排队队列容量上限。 */
    private int queueCapacity = 64;

    /** 排队等待上限（毫秒），超过即拒绝。 */
    private long maxQueueWaitMs = 200;

    /** 单批执行超时（毫秒），超过走降级。 */
    private long executionTimeoutMs = 500;

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public long getBatchWindowMs() {
        return batchWindowMs;
    }

    public void setBatchWindowMs(long batchWindowMs) {
        this.batchWindowMs = batchWindowMs;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getMaxQueueWaitMs() {
        return maxQueueWaitMs;
    }

    public void setMaxQueueWaitMs(long maxQueueWaitMs) {
        this.maxQueueWaitMs = maxQueueWaitMs;
    }

    public long getExecutionTimeoutMs() {
        return executionTimeoutMs;
    }

    public void setExecutionTimeoutMs(long executionTimeoutMs) {
        this.executionTimeoutMs = executionTimeoutMs;
    }
}
