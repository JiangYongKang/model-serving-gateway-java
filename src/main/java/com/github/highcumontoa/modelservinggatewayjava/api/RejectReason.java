package com.github.highcumontoa.modelservinggatewayjava.api;

/** 可区分的拒绝/降级原因。 */
public enum RejectReason {
    NONE,
    VERSION_MISSING,
    VERSION_UNKNOWN,
    VERSION_RETIRED,
    CONTRACT_INCOMPATIBLE,
    QUEUE_FULL,
    WAIT_TIMEOUT,
    /** 版本持续过载/失败被判定为不健康，进入受控状态后的快速拒绝。 */
    VERSION_UNHEALTHY,
    EXECUTION_TIMEOUT,
    INTERNAL_ERROR,
    RESULT_UNAVAILABLE
}
