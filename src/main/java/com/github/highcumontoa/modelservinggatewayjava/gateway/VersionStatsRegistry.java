package com.github.highcumontoa.modelservinggatewayjava.gateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按版本统计注册表：每个版本一份独立 {@link VersionStats}。
 * 查询与重置只操作计数器，不触碰调度车道，不干扰正常调度。
 */
@Component
public class VersionStatsRegistry {

    private final Map<String, VersionStats> perVersion = new ConcurrentHashMap<>();

    /** 取某版本的统计（不存在则创建空统计）。 */
    public VersionStats stats(String version) {
        return perVersion.computeIfAbsent(version, v -> new VersionStats());
    }

    /** 已观测到的版本列表。 */
    public List<String> versions() {
        return List.copyOf(perVersion.keySet());
    }

    /** 重置某版本累计计数。 */
    public void reset(String version) {
        VersionStats stats = perVersion.get(version);
        if (stats != null) {
            stats.reset();
        }
    }

    /** 重置全部版本累计计数。 */
    public void resetAll() {
        perVersion.values().forEach(VersionStats::reset);
    }
}
