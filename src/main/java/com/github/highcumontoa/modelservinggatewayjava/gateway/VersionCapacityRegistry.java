package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.batch.VersionCapacity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按版本承载参数注册表：每个版本持有独立的 {@link VersionCapacity} 快照。
 * 运行中调整通过原子替换整体快照生效，不重启服务、不打断在途请求；
 * 调度器每批读取一次快照，保证同一批看到同一套参数。
 */
@Component
public class VersionCapacityRegistry {

    private final VersionCapacity defaultCapacity;
    private final Map<String, AtomicReference<VersionCapacity>> perVersion =
            new ConcurrentHashMap<>();

    public VersionCapacityRegistry(BatchProperties properties) {
        this.defaultCapacity = VersionCapacity.from(properties);
    }

    /** 当前生效的容量快照；未单独配置的版本返回全局默认。 */
    public VersionCapacity snapshot(String version) {
        AtomicReference<VersionCapacity> ref = perVersion.get(version);
        return ref == null ? defaultCapacity : ref.get();
    }

    /** 整体替换某版本的容量快照，立即对新提交/新批次生效。 */
    public void update(String version, VersionCapacity capacity) {
        perVersion.computeIfAbsent(version, v -> new AtomicReference<>(defaultCapacity))
                .set(capacity);
    }

    /** 恢复某版本为全局默认参数。 */
    public void reset(String version) {
        perVersion.remove(version);
    }
}
