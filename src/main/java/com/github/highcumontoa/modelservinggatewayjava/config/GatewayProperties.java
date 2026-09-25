package com.github.highcumontoa.modelservinggatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 网关参数（骨架）。 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Batch batch = new Batch();

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public static class Batch {
        private int maxSize = 8;
        private long windowMs = 20;
        private int queueDepth = 16;
        private long queueWaitMs = 500;
        private long modelTimeoutMs = 200;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public long getWindowMs() { return windowMs; }
        public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
        public int getQueueDepth() { return queueDepth; }
        public void setQueueDepth(int queueDepth) { this.queueDepth = queueDepth; }
        public long getQueueWaitMs() { return queueWaitMs; }
        public void setQueueWaitMs(long queueWaitMs) { this.queueWaitMs = queueWaitMs; }
        public long getModelTimeoutMs() { return modelTimeoutMs; }
        public void setModelTimeoutMs(long modelTimeoutMs) { this.modelTimeoutMs = modelTimeoutMs; }
    }
}
