package com.github.highcumontoa.modelservinggatewayjava.governance;

import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchScheduler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 版本治理器注册表：按版本懒加载独立的治理器。
 * 每个版本有独立的队列、参数快照、健康状态与计数，
 * 某个版本变慢或积压不会连累其他版本。
 */
@Component
public class GovernorRegistry {

    private final BatchProperties batchDefaults;
    private final CapacityProperties capacityProperties;
    private final Map<String, VersionGovernor> governors = new ConcurrentHashMap<>();

    public GovernorRegistry(BatchProperties batchDefaults,
                            CapacityProperties capacityProperties) {
        this.batchDefaults = batchDefaults;
        this.capacityProperties = capacityProperties;
    }

    /** 取（或懒加载）指定版本的治理器。 */
    public VersionGovernor governorFor(String version,
            BatchScheduler.BatchExecutor<ScoreRequest, ScoreResponse> executor) {
        return governors.computeIfAbsent(version,
                v -> new VersionGovernor(v, initialCapacity(v), executor));
    }

    /** 仅查询已存在的治理器；不存在返回 null（查询不应创建治理器）。 */
    public VersionGovernor find(String version) {
        return governors.get(version);
    }

    /** 运行中调整指定版本的承载参数；版本治理器不存在时返回 false。 */
    public boolean updateCapacity(String version, VersionCapacity capacity) {
        VersionGovernor governor = governors.get(version);
        if (governor == null) {
            return false;
        }
        governor.updateCapacity(capacity);
        return true;
    }

    /** 全部已治理版本的运行状态快照。 */
    public List<VersionStats> allStats() {
        return governors.values().stream()
                .map(VersionGovernor::stats)
                .sorted((a, b) -> a.version().compareTo(b.version()))
                .toList();
    }

    /** 版本的初始承载参数：全局默认 + 配置中的按版本覆盖。 */
    private VersionCapacity initialCapacity(String version) {
        VersionCapacity base = new VersionCapacity(
                batchDefaults.getMaxBatchSize(),
                batchDefaults.getBatchWindowMs(),
                batchDefaults.getQueueCapacity(),
                batchDefaults.getMaxQueueWaitMs(),
                batchDefaults.getExecutionTimeoutMs(),
                capacityProperties.getFailureThreshold(),
                capacityProperties.getRecoveryMs());
        CapacityProperties.Override o = capacityProperties.getVersions().get(version);
        if (o == null) {
            return base;
        }
        return new VersionCapacity(
                o.getMaxBatchSize() != null ? o.getMaxBatchSize() : base.maxBatchSize(),
                o.getBatchWindowMs() != null ? o.getBatchWindowMs() : base.batchWindowMs(),
                o.getQueueCapacity() != null ? o.getQueueCapacity() : base.queueCapacity(),
                o.getMaxQueueWaitMs() != null ? o.getMaxQueueWaitMs() : base.maxQueueWaitMs(),
                o.getExecutionTimeoutMs() != null
                        ? o.getExecutionTimeoutMs() : base.executionTimeoutMs(),
                o.getFailureThreshold() != null
                        ? o.getFailureThreshold() : base.failureThreshold(),
                o.getRecoveryMs() != null ? o.getRecoveryMs() : base.recoveryMs());
    }

    public void shutdownAll() {
        governors.values().forEach(VersionGovernor::shutdown);
    }
}
