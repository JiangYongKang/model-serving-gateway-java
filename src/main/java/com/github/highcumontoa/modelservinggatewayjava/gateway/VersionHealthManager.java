package com.github.highcumontoa.modelservinggatewayjava.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 按版本健康状态机（熔断式受控）：
 * <ul>
 *   <li>HEALTHY：正常接纳；连续失败/过载达到阈值 → UNHEALTHY；</li>
 *   <li>UNHEALTHY：不再堆积排队，快速拒绝；冷却期固定（从进入时刻起算，
 *       期间的迟到结论不延长冷却），到期自动进入 HALF_OPEN；</li>
 *   <li>HALF_OPEN：放行有限个试探请求；任一探试成功 → HEALTHY（计数清零），
 *       任一探试失败 → 回到 UNHEALTHY 并重新起算冷却期。</li>
 * </ul>
 * 状态迁移只依赖计数器与单调时钟，确定、可复现；各版本状态完全独立。
 */
@Component
public class VersionHealthManager {

    private static final Logger log = LoggerFactory.getLogger(VersionHealthManager.class);

    private final HealthProperties properties;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    public VersionHealthManager(HealthProperties properties) {
        this.properties = properties;
    }

    /** 尝试接纳一个请求；不健康且未冷却到期（或半开试探已满）时返回 false。 */
    public boolean tryAdmit(String version) {
        return circuit(version).tryAdmit();
    }

    /** 记录一次成功结论。 */
    public void recordSuccess(String version) {
        circuit(version).recordSuccess();
    }

    /** 记录一次失败/过载结论。 */
    public void recordFailure(String version) {
        circuit(version).recordFailure();
    }

    /** 当前健康状态。 */
    public HealthState state(String version) {
        return circuit(version).state();
    }

    /** 距重新尝试接纳还剩多少毫秒；非不健康状态返回 0。 */
    public long retryAfterMs(String version) {
        return circuit(version).retryAfterMs();
    }

    private Circuit circuit(String version) {
        return circuits.computeIfAbsent(version, Circuit::new);
    }

    /** 单版本熔断器状态，所有迁移在该实例上同步，保证迁移确定。 */
    private final class Circuit {
        private final String version;
        private HealthState state = HealthState.HEALTHY;
        private int consecutiveFailures;
        private long retryAtNanos;
        private int halfOpenInFlight;

        Circuit(String version) {
            this.version = version;
        }

        synchronized boolean tryAdmit() {
            return switch (state) {
                case HEALTHY -> true;
                case UNHEALTHY -> {
                    if (System.nanoTime() >= retryAtNanos) {
                        transition(HealthState.HALF_OPEN);
                        halfOpenInFlight = 1;
                        yield true;
                    }
                    yield false;
                }
                case HALF_OPEN -> {
                    if (halfOpenInFlight < properties.getHalfOpenProbeCount()) {
                        halfOpenInFlight++;
                        yield true;
                    }
                    yield false;
                }
            };
        }

        synchronized void recordSuccess() {
            switch (state) {
                case HEALTHY -> consecutiveFailures = 0;
                case HALF_OPEN -> {
                    // 试探成功：恢复接纳，计数清零
                    halfOpenInFlight = 0;
                    consecutiveFailures = 0;
                    transition(HealthState.HEALTHY);
                }
                case UNHEALTHY -> {
                    // 进入不健康前已放行的在途请求迟到成功：不改变状态，
                    // 恢复只能经由半开试探确认，保证判定确定
                }
            }
        }

        synchronized void recordFailure() {
            switch (state) {
                case HEALTHY -> {
                    consecutiveFailures++;
                    if (consecutiveFailures >= properties.getFailureThreshold()) {
                        enterUnhealthy();
                    }
                }
                case HALF_OPEN -> {
                    // 试探失败：重新进入受控状态，冷却期重新起算
                    halfOpenInFlight = 0;
                    enterUnhealthy();
                }
                case UNHEALTHY -> {
                    // 在途迟到失败：不延长冷却期，重试时刻保持确定
                }
            }
        }

        private void enterUnhealthy() {
            retryAtNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(properties.getCooldownMs());
            transition(HealthState.UNHEALTHY);
        }

        private void transition(HealthState next) {
            log.info("version health transition: version={} {} -> {} "
                            + "(consecutiveFailures={}, cooldownMs={})",
                    version, state, next, consecutiveFailures, properties.getCooldownMs());
            state = next;
        }

        synchronized HealthState state() {
            return state;
        }

        synchronized long retryAfterMs() {
            if (state != HealthState.UNHEALTHY) {
                return 0;
            }
            long remaining = retryAtNanos - System.nanoTime();
            return Math.max(0, TimeUnit.NANOSECONDS.toMillis(remaining));
        }
    }
}
