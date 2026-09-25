package com.github.highcumontoa.modelservinggatewayjava.batch;

import com.github.highcumontoa.modelservinggatewayjava.protocol.ReasonCode;

/** 调度阶段的可区分失败；batchId 可为空（如排队阶段即被拒绝）。 */
public class SchedulerFailure extends RuntimeException {

    private final ReasonCode reasonCode;
    private final String batchId;

    public SchedulerFailure(ReasonCode reasonCode, String message) {
        this(reasonCode, message, null);
    }

    public SchedulerFailure(ReasonCode reasonCode, String message, String batchId) {
        super(message);
        this.reasonCode = reasonCode;
        this.batchId = batchId;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }

    public String batchId() {
        return batchId;
    }
}
