package com.github.highcumontoa.modelservinggatewayjava.model;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型版本注册表：按精确版本号查找本地实现及生命周期状态。
 * 不提供“默认版本”兜底——版本缺失必须显式报错，不得静默替换。
 */
public class ModelRegistry {

    private final ConcurrentHashMap<String, Registration> models = new ConcurrentHashMap<>();

    public void register(String version, LocalModel model, ModelStatus status) {
        models.put(version, new Registration(model, status));
    }

    public Optional<Registration> find(String version) {
        if (version == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(models.get(version));
    }

    public record Registration(LocalModel model, ModelStatus status) {
    }
}
