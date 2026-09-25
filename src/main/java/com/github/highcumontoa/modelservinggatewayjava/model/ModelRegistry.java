package com.github.highcumontoa.modelservinggatewayjava.model;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 模型版本注册表。
 * 在线版本与已下线版本分开记录：已下线版本必须能被明确识别，
 * 以便网关返回 VERSION_RETIRED 而不是 VERSION_UNKNOWN 或静默回退默认版本。
 */
@Component
public class ModelRegistry {

    private final Map<String, LocalModel> online = new ConcurrentHashMap<>();
    private final Set<String> retired = new CopyOnWriteArraySet<>();

    /** 注册（或替换）一个在线版本。 */
    public void register(LocalModel model) {
        online.put(model.version(), model);
        retired.remove(model.version());
    }

    /** 将版本标记为下线；下线后不再参与路由。 */
    public void retire(String version) {
        online.remove(version);
        retired.add(version);
    }

    /** 查找在线版本。 */
    public Optional<LocalModel> find(String version) {
        return Optional.ofNullable(online.get(version));
    }

    /** 该版本是否存在但已下线。 */
    public boolean isRetired(String version) {
        return retired.contains(version);
    }

    /** 当前在线版本列表（排序，便于展示与测试断言）。 */
    public List<String> onlineVersions() {
        return online.keySet().stream().sorted().toList();
    }
}
