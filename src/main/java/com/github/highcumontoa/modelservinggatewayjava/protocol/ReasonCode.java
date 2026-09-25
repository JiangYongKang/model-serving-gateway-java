package com.github.highcumontoa.modelservinggatewayjava.protocol;

/** 可区分的判定原因码：路由错误、过载拒绝、执行期失败各自独立。 */
public enum ReasonCode {
    VERSION_NOT_FOUND,
    VERSION_RETIRED,
    CONTRACT_INCOMPATIBLE,
    QUEUE_FULL,
    WAIT_TIMEOUT,
    MODEL_TIMEOUT,
    MODEL_ERROR,
    RESULT_INVALID
}
