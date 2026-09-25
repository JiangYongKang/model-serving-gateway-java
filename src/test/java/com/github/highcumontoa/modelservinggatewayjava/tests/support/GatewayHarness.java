package com.github.highcumontoa.modelservinggatewayjava.tests.support;

import com.github.highcumontoa.modelservinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DecisionAuditLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.InferenceGateway;
import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelStatus;

/** 测试装配：不启动 Spring，直接构造网关及可控的批量参数。 */
public final class GatewayHarness {

    private GatewayHarness() {
    }

    public static InferenceGateway gateway(ModelRegistry registry, DecisionAuditLog auditLog,
                                           int maxSize, long windowMs,
                                           int queueDepth, long queueWaitMs, long modelTimeoutMs) {
        GatewayProperties props = new GatewayProperties();
        props.getBatch().setMaxSize(maxSize);
        props.getBatch().setWindowMs(windowMs);
        props.getBatch().setQueueDepth(queueDepth);
        props.getBatch().setQueueWaitMs(queueWaitMs);
        props.getBatch().setModelTimeoutMs(modelTimeoutMs);
        return new InferenceGateway(registry, props, auditLog);
    }

    public static InferenceGateway gateway(ModelRegistry registry, int maxSize, long windowMs,
                                           int queueDepth, long queueWaitMs, long modelTimeoutMs) {
        return gateway(registry, new DecisionAuditLog(), maxSize, windowMs,
                queueDepth, queueWaitMs, modelTimeoutMs);
    }

    public static ModelRegistry registryWith(LocalModel... models) {
        ModelRegistry registry = new ModelRegistry();
        for (LocalModel model : models) {
            registry.register(model.version(), model, ModelStatus.ACTIVE);
        }
        return registry;
    }
}
