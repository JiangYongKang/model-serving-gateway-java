package com.github.highcumontoa.modelservinggatewayjava.protocol;

/** 结论状态：正常 / 降级 / 拒绝，三者必须可区分。 */
public enum DecisionStatus {
    NORMAL,
    DEGRADED,
    REJECTED
}
