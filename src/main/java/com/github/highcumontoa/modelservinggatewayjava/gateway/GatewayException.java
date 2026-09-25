package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;

/** 携带可区分拒绝原因的网关异常。 */
public class GatewayException extends RuntimeException {

    private final RejectReason reason;

    public GatewayException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
