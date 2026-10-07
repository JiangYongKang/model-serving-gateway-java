package com.github.highcumontoa.modelservinggatewayjava.gateway;

import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单版本运行计数：正常结论数、按原因分类的拒绝/降级计数。
 * 全部为原子计数器，查询与重置不加锁、不干扰调度；
 * 重置只清累计计数，不影响在途请求的判定结果。
 */
public class VersionStats {

    private final AtomicLong ok = new AtomicLong();
    private final Map<RejectReason, AtomicLong> rejected = new EnumMap<>(RejectReason.class);
    private final Map<RejectReason, AtomicLong> degraded = new EnumMap<>(RejectReason.class);

    public void recordOk() {
        ok.incrementAndGet();
    }

    public void recordRejected(RejectReason reason) {
        counter(rejected, reason).incrementAndGet();
    }

    public void recordDegraded(RejectReason reason) {
        counter(degraded, reason).incrementAndGet();
    }

    public long okCount() {
        return ok.get();
    }

    /** 拒绝计数快照（仅含非零项）。 */
    public Map<RejectReason, Long> rejectedCounts() {
        return snapshotOf(rejected);
    }

    /** 降级计数快照（仅含非零项）。 */
    public Map<RejectReason, Long> degradedCounts() {
        return snapshotOf(degraded);
    }

    /** 重置累计计数（仅计数器，不含排队深度等在途 gauge）。 */
    public synchronized void reset() {
        ok.set(0);
        rejected.clear();
        degraded.clear();
    }

    private static AtomicLong counter(Map<RejectReason, AtomicLong> map, RejectReason reason) {
        AtomicLong c = map.get(reason);
        if (c != null) {
            return c;
        }
        synchronized (map) {
            return map.computeIfAbsent(reason, r -> new AtomicLong());
        }
    }

    private static Map<RejectReason, Long> snapshotOf(Map<RejectReason, AtomicLong> map) {
        Map<RejectReason, Long> snapshot = new EnumMap<>(RejectReason.class);
        synchronized (map) {
            map.forEach((reason, count) -> {
                if (count.get() > 0) {
                    snapshot.put(reason, count.get());
                }
            });
        }
        return Map.copyOf(snapshot);
    }
}
