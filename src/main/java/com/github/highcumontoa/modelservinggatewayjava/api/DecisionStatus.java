package com.github.highcumontoa.modelservinggatewayjava.api;

/** 判定结论状态：正常 / 降级 / 拒绝。 */
public enum DecisionStatus {
    OK,
    DEGRADED,
    REJECTED
}
