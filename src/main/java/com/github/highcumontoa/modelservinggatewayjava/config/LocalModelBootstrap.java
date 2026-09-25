package com.github.highcumontoa.modelservinggatewayjava.config;

import com.github.highcumontoa.modelservinggatewayjava.model.DeterministicLocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 本地模型版本装配（全部为本地确定性实现，无外部依赖、无真实凭据）：
 * <ul>
 *   <li>v1：在线，契约 1.0，输入仅需文本；</li>
 *   <li>v2：在线，契约 2.0，必须包含 features 字段（输入契约升级）；</li>
 *   <li>v0：已下线，任何请求必须显式得到 VERSION_RETIRED。</li>
 * </ul>
 */
@Configuration
public class LocalModelBootstrap {

    @Bean
    public ModelRegistry modelRegistry() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("v1",
                new DeterministicLocalModel("v1", "1.0", "salt-v1"),
                ModelStatus.ACTIVE);
        registry.register("v2",
                new DeterministicLocalModel("v2", "2.0", "salt-v2", Set.of("features")),
                ModelStatus.ACTIVE);
        registry.register("v0",
                new DeterministicLocalModel("v0", "1.0", "salt-v0"),
                ModelStatus.RETIRED);
        return registry;
    }
}
