package com.github.highcumontoa.modelservinggatewayjava.governance;

/** 版本健康状态。 */
public enum HealthState {
    /** 正常承载。 */
    HEALTHY,
    /** 持续过载/失败，进入受控状态：快速拒绝，不再无意义堆积。 */
    UNHEALTHY,
    /** 到达重试时点，放行单个探针请求验证是否恢复。 */
    PROBING
}
