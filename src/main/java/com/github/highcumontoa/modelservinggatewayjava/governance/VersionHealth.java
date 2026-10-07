package com.github.highcumontoa.modelservinggatewayjava.governance;

import java.util.concurrent.TimeUnit;

/**
 * 单版本健康状态机（熔断），全部迁移确定、可复现：
 * <ul>
 *   <li>HEALTHY：正常接纳；连续失败/过载达到 failureThreshold 进入 UNHEALTHY，
 *       任意一次成功清零连续失败计数；</li>
 *   <li>UNHEALTHY：快速拒绝，不再无意义堆积排队；到达 recoveryMs 后的
 *       第一个请求作为探针放行（PROBING），其余仍拒绝；</li>
 *   <li>PROBING：探针成功回到 HEALTHY 并清零计数；探针失败回到 UNHEALTHY
 *       并重新计时。自动恢复，无需人工重启。</li>
 * </ul>
 * 状态只作用于本版本，不影响其他版本。时间由调用方注入（System.nanoTime），
 * 便于确定性测试。
 */
class VersionHealth {

    /** 接纳判定结果。 */
    enum Admission {
        /** 正常接纳。 */
        ACCEPT,
        /** 不健康，快速拒绝。 */
        REJECT,
        /** 作为恢复探针放行。 */
        PROBE
    }

    private volatile int failureThreshold;
    private volatile long recoveryMs;

    private HealthState state = HealthState.HEALTHY;
    private int consecutiveFailures;
    private long openedAtNanos;

    VersionHealth(int failureThreshold, long recoveryMs) {
        this.failureThreshold = failureThreshold;
        this.recoveryMs = recoveryMs;
    }

    /** 运行中调整健康策略（随承载参数快照一起替换）。 */
    synchronized void updatePolicy(int failureThreshold, long recoveryMs) {
        this.failureThreshold = failureThreshold;
        this.recoveryMs = recoveryMs;
    }

    /** 请求进入前的接纳判定。 */
    synchronized Admission tryAdmit(long nowNanos) {
        if (state == HealthState.UNHEALTHY) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nowNanos - openedAtNanos);
            if (elapsedMs >= recoveryMs) {
                state = HealthState.PROBING;
                return Admission.PROBE;
            }
            return Admission.REJECT;
        }
        if (state == HealthState.PROBING) {
            // 探针在途，其余请求继续快速拒绝
            return Admission.REJECT;
        }
        return Admission.ACCEPT;
    }

    /** 记录一次成功：HEALTHY 下清零连续失败；PROBING 下确认恢复。 */
    synchronized void onSuccess() {
        consecutiveFailures = 0;
        if (state == HealthState.PROBING) {
            state = HealthState.HEALTHY;
        }
    }

    /** 记录一次失败/过载：达到阈值进入 UNHEALTHY；探针失败重新计时。 */
    synchronized void onFailure(long nowNanos) {
        if (state == HealthState.PROBING) {
            state = HealthState.UNHEALTHY;
            openedAtNanos = nowNanos;
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            state = HealthState.UNHEALTHY;
            openedAtNanos = nowNanos;
        }
    }

    synchronized HealthState state() {
        return state;
    }

    /** UNHEALTHY 状态下距下次放行探针的剩余毫秒；其余状态为 0。 */
    synchronized long retryAfterMs(long nowNanos) {
        if (state != HealthState.UNHEALTHY) {
            return 0;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nowNanos - openedAtNanos);
        return Math.max(0, recoveryMs - elapsedMs);
    }

    /** 重置为健康并清零计数（压测场景；不影响在途请求已得出的判定）。 */
    synchronized void reset() {
        state = HealthState.HEALTHY;
        consecutiveFailures = 0;
        openedAtNanos = 0;
    }
}
