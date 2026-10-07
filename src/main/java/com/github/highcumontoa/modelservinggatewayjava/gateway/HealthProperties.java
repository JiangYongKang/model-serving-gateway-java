package com.github.highcumontoa.modelservinggatewayjava.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 版本健康判定配置（全局默认，按版本各自独立计时计数）。 */
@ConfigurationProperties(prefix = "gateway.health")
public class HealthProperties {

    /** 连续失败/过载达到该次数即判定不健康。 */
    private int failureThreshold = 3;

    /** 不健康冷却期（毫秒），到期后进入半开试探。 */
    private long cooldownMs = 5000;

    /** 半开状态允许同时进行的试探请求数。 */
    private int halfOpenProbeCount = 1;

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public int getHalfOpenProbeCount() {
        return halfOpenProbeCount;
    }

    public void setHalfOpenProbeCount(int halfOpenProbeCount) {
        this.halfOpenProbeCount = halfOpenProbeCount;
    }
}
