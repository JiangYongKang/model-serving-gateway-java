package com.github.highcumontoa.modelservinggatewayjava.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 按版本容量治理配置。
 * 未单独配置的版本使用全局默认（gateway.batch.* + 本类的健康策略默认值）；
 * versions 下的覆盖项只生效非空字段，其余继承默认。
 */
@ConfigurationProperties(prefix = "gateway.capacity")
public class CapacityProperties {

    /** 连续失败/过载达到该次数后判定版本不健康。 */
    private int failureThreshold = 5;

    /** 不健康后经过该毫秒数放行恢复探针。 */
    private long recoveryMs = 5000;

    /** 按版本的覆盖配置（字段为空的项继承默认值）。 */
    private Map<String, Override> versions = new HashMap<>();

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getRecoveryMs() {
        return recoveryMs;
    }

    public void setRecoveryMs(long recoveryMs) {
        this.recoveryMs = recoveryMs;
    }

    public Map<String, Override> getVersions() {
        return versions;
    }

    public void setVersions(Map<String, Override> versions) {
        this.versions = versions;
    }

    /** 单版本覆盖项：null 表示继承默认。 */
    public static class Override {
        private Integer maxBatchSize;
        private Long batchWindowMs;
        private Integer queueCapacity;
        private Long maxQueueWaitMs;
        private Long executionTimeoutMs;
        private Integer failureThreshold;
        private Long recoveryMs;

        public Integer getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(Integer maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public Long getBatchWindowMs() {
            return batchWindowMs;
        }

        public void setBatchWindowMs(Long batchWindowMs) {
            this.batchWindowMs = batchWindowMs;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(Integer queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Long getMaxQueueWaitMs() {
            return maxQueueWaitMs;
        }

        public void setMaxQueueWaitMs(Long maxQueueWaitMs) {
            this.maxQueueWaitMs = maxQueueWaitMs;
        }

        public Long getExecutionTimeoutMs() {
            return executionTimeoutMs;
        }

        public void setExecutionTimeoutMs(Long executionTimeoutMs) {
            this.executionTimeoutMs = executionTimeoutMs;
        }

        public Integer getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(Integer failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Long getRecoveryMs() {
            return recoveryMs;
        }

        public void setRecoveryMs(Long recoveryMs) {
            this.recoveryMs = recoveryMs;
        }
    }
}
