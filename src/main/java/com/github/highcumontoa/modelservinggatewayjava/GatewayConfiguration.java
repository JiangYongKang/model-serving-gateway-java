package com.github.highcumontoa.modelservinggatewayjava;

import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.governance.CapacityProperties;
import com.github.highcumontoa.modelservinggatewayjava.model.LinearScoringModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 网关装配：启用批量配置与按版本容量治理配置，并注册内置本地模型版本。
 * 全部为本地确定性实现，不依赖外部服务或真实凭据。
 */
@Configuration
@EnableConfigurationProperties({BatchProperties.class, CapacityProperties.class})
public class GatewayConfiguration {

    private final ModelRegistry registry;

    public GatewayConfiguration(ModelRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void registerBuiltinModels() {
        // v1：契约 1，两特征线性模型
        registry.register(new LinearScoringModel("v1", 1,
                Map.of("income", 1.2, "debt", -1.5), -0.3));
        // v2：契约 2，三特征线性模型（契约升级，与 v1 不兼容）
        registry.register(new LinearScoringModel("v2", 2,
                Map.of("income", 0.8, "debt", -1.1, "tenure", 0.6), -0.1));
        // v0：已下线版本，用于验证 VERSION_RETIRED 路径
        registry.retire("v0");
    }
}
