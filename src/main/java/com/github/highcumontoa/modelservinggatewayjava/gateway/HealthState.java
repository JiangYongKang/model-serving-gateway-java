package com.github.highcumontoa.modelservinggatewayjava.gateway;

/** 版本健康状态：正常接纳 / 不健康受控（快速失败）/ 半开试探恢复。 */
public enum HealthState {
    HEALTHY,
    UNHEALTHY,
    HALF_OPEN
}
