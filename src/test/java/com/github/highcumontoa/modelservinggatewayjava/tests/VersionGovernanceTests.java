package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DegradationLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.ScoringGateway;
import com.github.highcumontoa.modelservinggatewayjava.governance.CapacityProperties;
import com.github.highcumontoa.modelservinggatewayjava.governance.GovernorRegistry;
import com.github.highcumontoa.modelservinggatewayjava.governance.HealthState;
import com.github.highcumontoa.modelservinggatewayjava.governance.VersionCapacity;
import com.github.highcumontoa.modelservinggatewayjava.governance.VersionStats;
import com.github.highcumontoa.modelservinggatewayjava.model.LinearScoringModel;
import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按版本容量治理与运行观测测试：
 * 版本隔离、运行中调参、健康状态进入与自动恢复、运行状态查询与重置。
 * 日志打印每个用例覆盖的业务点与判定依据，便于人工核对。
 */
class VersionGovernanceTests {

    private static final Logger log = LoggerFactory.getLogger(VersionGovernanceTests.class);

    private ModelRegistry registry;
    private GovernorRegistry governors;
    private ScoringGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    private ScoringGateway newGateway(CapacityProperties cap, LocalModel... models) {
        BatchProperties batch = new BatchProperties();
        batch.setMaxBatchSize(4);
        batch.setBatchWindowMs(5);
        batch.setQueueCapacity(16);
        batch.setMaxQueueWaitMs(500);
        batch.setExecutionTimeoutMs(1000);
        registry = new ModelRegistry();
        for (LocalModel m : models) {
            registry.register(m);
        }
        governors = new GovernorRegistry(batch, cap);
        gateway = new ScoringGateway(registry, new DegradationLog(), governors);
        return gateway;
    }

    private static CapacityProperties cap(int failureThreshold, long recoveryMs) {
        CapacityProperties c = new CapacityProperties();
        c.setFailureThreshold(failureThreshold);
        c.setRecoveryMs(recoveryMs);
        return c;
    }

    private static LocalModel fastModel(String version) {
        return new LinearScoringModel(version, 1,
                Map.of("income", 1.2, "debt", -1.5), -0.3);
    }

    private static LocalModel slowModel(String version, long sleepMs) {
        return new LocalModel() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public int contractVersion() {
                return 1;
            }

            @Override
            public double score(Map<String, Double> features) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0.9;
            }
        };
    }

    /** 开关控制的模型：unhealthy 为 true 时抛异常，false 时正常打分。 */
    private static LocalModel flappingModel(String version, AtomicBoolean unhealthy) {
        return new LocalModel() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public int contractVersion() {
                return 1;
            }

            @Override
            public double score(Map<String, Double> features) {
                if (unhealthy.get()) {
                    throw new IllegalStateException("simulated version failure");
                }
                return 0.9;
            }
        };
    }

    private static ScoreRequest req(String id, String version) {
        return new ScoreRequest(id, version, 1, Map.of("income", 2.0, "debt", 0.5));
    }

    @Test
    void slowVersionDoesNotAffectHealthyVersion() {
        log.info("业务覆盖：版本隔离 —— 一个版本持续超时进入受控状态，另一版本照常判定");
        LocalModel slow = new LocalModel() {
            @Override
            public String version() {
                return "slow";
            }

            @Override
            public int contractVersion() {
                return 1;
            }

            @Override
            public double score(Map<String, Double> features) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0.9;
            }
        };
        CapacityProperties cap = cap(3, 60000);
        // slow 版本单独配置很小的执行超时；v1 用默认大超时
        CapacityProperties.Override o = new CapacityProperties.Override();
        o.setExecutionTimeoutMs(80L);
        cap.getVersions().put("slow", o);
        ScoringGateway gw = newGateway(cap, slow, fastModel("v1"));

        List<ScoreResponse> slowResponses = new ArrayList<>();
        List<ScoreResponse> v1Responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            slowResponses.add(gw.score(req("s" + i, "slow")));
            v1Responses.add(gw.score(req("h" + i, "v1")));
        }
        for (ScoreResponse r : slowResponses) {
            log.info("slow version decision: id={} status={} reason={}",
                    r.requestId(), r.status(), r.reason());
        }
        for (ScoreResponse r : v1Responses) {
            log.info("healthy version decision: id={} status={} score={} label={}",
                    r.requestId(), r.status(), r.score(), r.label());
        }

        // slow 版本：前 3 次超时降级，达到阈值后进入不健康，后续快速拒绝
        assertEquals(DecisionStatus.DEGRADED, slowResponses.get(0).status());
        assertEquals(RejectReason.EXECUTION_TIMEOUT, slowResponses.get(0).reason());
        assertEquals(DecisionStatus.REJECTED, slowResponses.get(4).status());
        assertEquals(RejectReason.VERSION_UNHEALTHY, slowResponses.get(4).reason());
        // v1 版本全程不受影响：全部正常结论
        assertTrue(v1Responses.stream().allMatch(r -> r.status() == DecisionStatus.OK),
                "健康版本的请求不应被其他版本的积压/不健康连累");
        assertTrue(v1Responses.stream().noneMatch(r -> r.label().equals("FALLBACK")));

        VersionStats slowStats = governors.find("slow").stats();
        VersionStats v1Stats = governors.find("v1").stats();
        log.info("slow stats: {}", slowStats);
        log.info("v1 stats: {}", v1Stats);
        assertEquals(HealthState.UNHEALTHY, slowStats.health());
        assertTrue(slowStats.retryAfterMs() > 0, "不健康状态应给出距重试的剩余时间");
        assertEquals(HealthState.HEALTHY, v1Stats.health());
        assertEquals(5, v1Stats.okCount());
        assertEquals(0, v1Stats.rejectedUnhealthy());
    }

    @Test
    void capacityUpdateTakesEffectWithoutRestart() {
        log.info("业务覆盖：运行中调参 —— 不重启、不打断在途请求，新参数对新批次生效");
        ScoringGateway gw = newGateway(cap(100, 60000), slowModel("v1", 300));

        // 默认执行超时 1000ms：300ms 的慢模型正常完成（同时触发治理器创建）
        ScoreResponse initial = gw.score(req("u0", "v1"));
        log.info("initial: status={} score={}", initial.status(), initial.score());
        assertEquals(DecisionStatus.OK, initial.status());

        // 运行中把执行超时调到 80ms：同一版本同一模型，结论翻转为超时降级
        governors.updateCapacity("v1", new VersionCapacity(4, 5, 16, 500, 80, 100, 60000));
        ScoreResponse before = gw.score(req("u1", "v1"));
        log.info("after shrinking timeout: status={} reason={}", before.status(), before.reason());
        assertEquals(DecisionStatus.DEGRADED, before.status());
        assertEquals(RejectReason.EXECUTION_TIMEOUT, before.reason());

        // 运行中把执行超时调到 2000ms：结论恢复为正常
        governors.updateCapacity("v1", new VersionCapacity(4, 5, 16, 500, 2000, 100, 60000));
        ScoreResponse after = gw.score(req("u2", "v1"));
        log.info("after restoring timeout: status={} score={} label={}", after.status(),
                after.score(), after.label());
        assertEquals(DecisionStatus.OK, after.status());
        assertNotEquals("FALLBACK", after.label());

        // 排队上限同样可运行中调整：调到 1 后并发洪峰立即出现 QUEUE_FULL
        governors.updateCapacity("v1", new VersionCapacity(1, 1, 1, 500, 2000, 100, 60000));
        List<ScoreResponse> flood = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = "f" + i;
            Thread t = Thread.startVirtualThread(() -> flood.add(gw.score(req(id, "v1"))));
            threads.add(t);
        }
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long queueFull = flood.stream()
                .filter(r -> r.reason() == RejectReason.QUEUE_FULL).count();
        log.info("after shrinking queue capacity to 1: QUEUE_FULL count={}", queueFull);
        assertTrue(queueFull > 0, "运行中调小排队上限后应立即按新上限拒绝");
    }

    @Test
    void unhealthyVersionFastRejectsAndAutoRecovers() throws Exception {
        log.info("业务覆盖：健康受控 —— 持续失败进入不健康、快速拒绝且原因可区分、恢复后自动接纳");
        AtomicBoolean unhealthy = new AtomicBoolean(true);
        ScoringGateway gw = newGateway(cap(3, 300),
                flappingModel("v1", unhealthy), fastModel("v2"));

        // 连续 3 次失败（内部异常降级）达到阈值
        for (int i = 0; i < 3; i++) {
            ScoreResponse r = gw.score(req("a" + i, "v1"));
            log.info("failure injection: id={} status={} reason={}",
                    r.requestId(), r.status(), r.reason());
            assertEquals(DecisionStatus.DEGRADED, r.status());
            assertEquals(RejectReason.INTERNAL_ERROR, r.reason());
        }
        // 第 4 次：不健康快速拒绝，原因与普通超时/异常降级可区分
        ScoreResponse fast = gw.score(req("a3", "v1"));
        log.info("fast reject: status={} reason={}", fast.status(), fast.reason());
        assertEquals(DecisionStatus.REJECTED, fast.status());
        assertEquals(RejectReason.VERSION_UNHEALTHY, fast.reason());
        assertEquals(HealthState.UNHEALTHY, governors.find("v1").stats().health());

        // 版本恢复后，到达重试时点自动放行探针并回到健康
        unhealthy.set(false);
        Thread.sleep(350);
        ScoreResponse probe = gw.score(req("a4", "v1"));
        log.info("recovery probe: status={} score={} label={}",
                probe.status(), probe.score(), probe.label());
        assertEquals(DecisionStatus.OK, probe.status());
        assertEquals(HealthState.HEALTHY, governors.find("v1").stats().health());

        ScoreResponse after = gw.score(req("a5", "v1"));
        assertEquals(DecisionStatus.OK, after.status());
        // 全程 v2 不受影响
        assertEquals(DecisionStatus.OK, gw.score(req("a6", "v2")).status());
        log.info("recovered: v1 health={} v2 health={}",
                governors.find("v1").stats().health(),
                governors.find("v2").stats().health());
    }

    @Test
    void failedProbeKeepsVersionUnhealthyUntilRecovery() throws Exception {
        log.info("业务覆盖：探针失败 —— 未真正恢复时保持受控，恢复判定确定可复现");
        AtomicBoolean unhealthy = new AtomicBoolean(true);
        ScoringGateway gw = newGateway(cap(2, 300), flappingModel("v1", unhealthy));

        gw.score(req("p0", "v1"));
        gw.score(req("p1", "v1"));
        ScoreResponse rejected = gw.score(req("p2", "v1"));
        assertEquals(RejectReason.VERSION_UNHEALTHY, rejected.reason());

        // 到达重试时点但版本未恢复：探针失败，重新进入不健康并重新计时
        Thread.sleep(350);
        ScoreResponse probe = gw.score(req("p3", "v1"));
        log.info("failed probe: status={} reason={}", probe.status(), probe.reason());
        assertEquals(DecisionStatus.DEGRADED, probe.status());
        assertEquals(HealthState.UNHEALTHY, governors.find("v1").stats().health());
        ScoreResponse again = gw.score(req("p4", "v1"));
        assertEquals(RejectReason.VERSION_UNHEALTHY, again.reason());
        log.info("after failed probe: health={} retryAfterMs={}",
                governors.find("v1").stats().health(),
                governors.find("v1").stats().retryAfterMs());
    }

    @Test
    void statsAreQueryableAndResettableWithoutAffectingScheduling() {
        log.info("业务覆盖：运行观测 —— 按版本查询计数与健康状态，重置后计数清零且不影响判定");
        AtomicBoolean unhealthy = new AtomicBoolean(true);
        ScoringGateway gw = newGateway(cap(2, 60000),
                flappingModel("v1", unhealthy), fastModel("v2"));

        gw.score(req("q0", "v1"));
        gw.score(req("q1", "v1"));
        gw.score(req("q2", "v1")); // 不健康快速拒绝
        gw.score(req("q3", "v2"));

        VersionStats v1 = governors.find("v1").stats();
        VersionStats v2 = governors.find("v2").stats();
        log.info("v1 stats before reset: {}", v1);
        log.info("v2 stats before reset: {}", v2);
        assertEquals(2, v1.degradedInternalError());
        assertEquals(1, v1.rejectedUnhealthy());
        assertEquals(HealthState.UNHEALTHY, v1.health());
        assertEquals(1, v2.okCount());
        assertEquals(HealthState.HEALTHY, v2.health());

        // 重置只影响本版本计数与健康状态；v2 的计数不变
        governors.find("v1").resetStats();
        VersionStats v1After = governors.find("v1").stats();
        VersionStats v2After = governors.find("v2").stats();
        log.info("v1 stats after reset: {}", v1After);
        log.info("v2 stats after reset: {}", v2After);
        assertEquals(0, v1After.degradedInternalError());
        assertEquals(0, v1After.rejectedUnhealthy());
        assertEquals(HealthState.HEALTHY, v1After.health());
        assertEquals(1, v2After.okCount());

        // 重置不干扰正常调度：版本已恢复（开关也已关闭），请求正常判定
        unhealthy.set(false);
        ScoreResponse r = gw.score(req("q4", "v1"));
        log.info("after reset decision: status={} score={}", r.status(), r.score());
        assertEquals(DecisionStatus.OK, r.status());
        assertEquals(1, governors.find("v1").stats().okCount());
    }
}
