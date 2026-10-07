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
    EXECUTION_TIMEOUT,
    INTERNAL_ERROR,
    RESULT_UNAVAILABLE,
    /** 版本被判定不健康，受控状态下快速拒绝（与普通过载拒绝相区分）。 */
    VERSION_UNHEALTHY
}
