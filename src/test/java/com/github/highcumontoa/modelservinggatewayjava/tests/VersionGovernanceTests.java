package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.batch.VersionCapacity;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DegradationLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.HealthProperties;
import com.github.highcumontoa.modelservinggatewayjava.gateway.HealthState;
import com.github.highcumontoa.modelservinggatewayjava.gateway.ScoringGateway;
import com.github.highcumontoa.modelservinggatewayjava.gateway.VersionCapacityRegistry;
import com.github.highcumontoa.modelservinggatewayjava.gateway.VersionHealthManager;
import com.github.highcumontoa.modelservinggatewayjava.gateway.VersionStatsRegistry;
import com.github.highcumontoa.modelservinggatewayjava.gateway.VersionStatsSnapshot;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按版本容量治理与运行观测测试：
 * 版本隔离、运行中调整承载参数、健康状态进入与自动恢复、状态查询与重置。
 * 日志打印每个用例覆盖的业务点与判定依据，便于人工核对。
 */
class VersionGovernanceTests {

    private static final Logger log = LoggerFactory.getLogger(VersionGovernanceTests.class);

    private ModelRegistry registry;
    private DegradationLog degradationLog;
    private ScoringGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    private ScoringGateway newGateway(VersionCapacity defaults, HealthProperties health,
                                      LocalModel... models) {
        registry = new ModelRegistry();
        for (LocalModel m : models) {
            registry.register(m);
        }
        degradationLog = new DegradationLog();
        BatchProperties props = new BatchProperties();
        VersionCapacityRegistry capacityRegistry = new VersionCapacityRegistry(props);
        gateway = new ScoringGateway(registry, degradationLog, capacityRegistry,
                new VersionHealthManager(health), new VersionStatsRegistry());
        // 各版本统一先打上本用例的默认容量
        for (LocalModel m : models) {
            gateway.updateCapacity(m.version(), defaults);
        }
        return gateway;
    }

    private static HealthProperties health(int failureThreshold, long cooldownMs) {
        HealthProperties h = new HealthProperties();
        h.setFailureThreshold(failureThreshold);
        h.setCooldownMs(cooldownMs);
        h.setHalfOpenProbeCount(1);
        return h;
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

    /** 可切换成功/失败的模型，用于健康状态用例。 */
    private static LocalModel toggleModel(String version, AtomicBoolean failing) {
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
                if (failing.get()) {
                    throw new IllegalStateException("simulated failure of " + version);
                }
                return 0.9;
            }
        };
    }

    private static ScoreRequest req(String id, String version) {
        return new ScoreRequest(id, version, 1, Map.of("income", 2.0, "debt", 0.5));
    }

    private List<ScoreResponse> fireConcurrent(ScoringGateway gw, List<ScoreRequest> requests)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ScoreResponse>> futures = new ArrayList<>();
        for (ScoreRequest r : requests) {
            futures.add(pool.submit(() -> {
                gate.await();
                return gw.score(r);
            }));
        }
        gate.countDown();
        List<ScoreResponse> responses = new ArrayList<>();
        for (Future<ScoreResponse> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();
        return responses;
    }

    @Test
    void overloadedVersionDoesNotAffectHealthyVersion() throws Exception {
        log.info("CASE 版本隔离：slow 版本被压住时，v1 版本依然正常");
        // slow：单批 1 条、队列 2、执行 300ms —— 洪峰下必然溢出/触发熔断
        ScoringGateway gw = newGateway(
                new VersionCapacity(1, 1, 2, 1000, 2000), health(3, 1000),
                slowModel("slow", 300), fastModel("v1"));

        List<ScoreRequest> flood = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            flood.add(req("f" + i, "slow"));
        }
        List<ScoreResponse> slowResponses = fireConcurrent(gw, flood);
        // 在 slow 积压期间，v1 同步打分
        List<ScoreResponse> v1Responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            v1Responses.add(gw.score(req("h" + i, "v1")));
        }

        long slowRejected = slowResponses.stream()
                .filter(r -> r.status() == DecisionStatus.REJECTED).count();
        long v1Ok = v1Responses.stream()
                .filter(r -> r.status() == DecisionStatus.OK).count();
        VersionStatsSnapshot slowStats = gw.statsSnapshot("slow");
        VersionStatsSnapshot v1Stats = gw.statsSnapshot("v1");
        log.info("slow rejected={} health={} v1 ok={} v1 health={}",
                slowRejected, slowStats.healthState(), v1Ok, v1Stats.healthState());

        assertTrue(slowRejected > 0, "slow 版本过载必须出现拒绝");
        assertEquals(5, v1Ok, "slow 版本过载不得影响 v1 的正常结论");
        assertEquals(5, v1Stats.okCount());
        assertTrue(v1Stats.rejected().isEmpty(), "v1 不得出现任何拒绝");
        assertEquals(HealthState.HEALTHY, v1Stats.healthState(),
                "v1 健康状态不得被 slow 连累");
    }

    @Test
    void runtimeCapacityUpdateTakesEffectWithoutRestart() {
        log.info("CASE 运行中调整参数：执行超时收紧后生效，放宽后恢复，无需重启");
        AtomicLong sleepMs = new AtomicLong(100);
        LocalModel model = new LocalModel() {
            @Override
            public String version() {
                return "m";
            }

            @Override
            public int contractVersion() {
                return 1;
            }

            @Override
            public double score(Map<String, Double> features) {
                try {
                    Thread.sleep(sleepMs.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0.9;
            }
        };
        // 健康阈值放大，避免本用例触发熔断干扰
        ScoringGateway gw = newGateway(
                new VersionCapacity(4, 5, 16, 1000, 2000), health(100, 1000), model);

        ScoreResponse before = gw.score(req("c1", "m"));
        log.info("before update: status={} reason={}", before.status(), before.reason());
        assertEquals(DecisionStatus.OK, before.status());

        // 运行中收紧执行超时：100ms 的模型必然超时降级
        gw.updateCapacity("m", new VersionCapacity(4, 5, 16, 1000, 30));
        assertEquals(30, gw.capacityOf("m").executionTimeoutMs());
        ScoreResponse tightened = gw.score(req("c2", "m"));
        log.info("after tightening: status={} reason={}", tightened.status(), tightened.reason());
        assertEquals(DecisionStatus.DEGRADED, tightened.status());
        assertEquals(RejectReason.EXECUTION_TIMEOUT, tightened.reason());

        // 运行中放宽：立即恢复正常，无需重启
        gw.updateCapacity("m", new VersionCapacity(4, 5, 16, 1000, 2000));
        ScoreResponse relaxed = gw.score(req("c3", "m"));
        log.info("after relaxing: status={} reason={}", relaxed.status(), relaxed.reason());
        assertEquals(DecisionStatus.OK, relaxed.status());
    }

    @Test
    void unhealthyVersionFailsFastAndRecoversAutomatically() throws Exception {
        log.info("CASE 健康状态：持续失败进入受控快速失败，恢复后自动重新接纳");
        AtomicBoolean failing = new AtomicBoolean(true);
        ScoringGateway gw = newGateway(
                new VersionCapacity(4, 5, 16, 1000, 2000), health(2, 300),
                toggleModel("flaky", failing), fastModel("v1"));

        ScoreResponse d1 = gw.score(req("u1", "flaky"));
        ScoreResponse d2 = gw.score(req("u2", "flaky"));
        log.info("failure 1: {} {}; failure 2: {} {}",
                d1.status(), d1.reason(), d2.status(), d2.reason());
        assertEquals(DecisionStatus.DEGRADED, d1.status());
        assertEquals(DecisionStatus.DEGRADED, d2.status());

        // 达到阈值：进入 UNHEALTHY，快速拒绝且不再产生新的降级记录
        VersionStatsSnapshot unhealthy = gw.statsSnapshot("flaky");
        log.info("after threshold: health={} retryAfterMs={}",
                unhealthy.healthState(), unhealthy.retryAfterMs());
        assertEquals(HealthState.UNHEALTHY, unhealthy.healthState());
        assertTrue(unhealthy.retryAfterMs() > 0, "不健康状态必须给出重试剩余时间");

        int degradationsBefore = degradationLog.all().size();
        ScoreResponse fastFail = gw.score(req("u3", "flaky"));
        log.info("controlled reject: status={} reason={}", fastFail.status(), fastFail.reason());
        assertEquals(DecisionStatus.REJECTED, fastFail.status());
        assertEquals(RejectReason.VERSION_UNHEALTHY, fastFail.reason(),
                "不健康快速失败必须与普通过载/超时降级区分");
        assertEquals(degradationsBefore, degradationLog.all().size(),
                "受控状态下不得再无意义执行并堆积降级记录");
        // 其他版本不受影响
        assertEquals(DecisionStatus.OK, gw.score(req("u9", "v1")).status());

        // 模型恢复 + 冷却到期：半开试探成功后自动重新接纳
        failing.set(false);
        Thread.sleep(350);
        ScoreResponse probe = gw.score(req("u4", "flaky"));
        log.info("probe after cooldown: status={} reason={}", probe.status(), probe.reason());
        assertEquals(DecisionStatus.OK, probe.status(), "冷却到期后试探请求应被接纳");
        VersionStatsSnapshot recovered = gw.statsSnapshot("flaky");
        log.info("after probe: health={} retryAfterMs={}",
                recovered.healthState(), recovered.retryAfterMs());
        assertEquals(HealthState.HEALTHY, recovered.healthState());
        assertEquals(0, recovered.retryAfterMs());
        assertEquals(DecisionStatus.OK, gw.score(req("u5", "flaky")).status(),
                "恢复后应持续正常接纳");
    }

    @Test
    void statsAreQueryableAndResettableWithoutDisturbingTraffic() {
        log.info("CASE 运行观测：按版本查询计数与健康状态，重置后调度不受影响");
        AtomicBoolean failing = new AtomicBoolean(true);
        ScoringGateway gw = newGateway(
                new VersionCapacity(4, 5, 16, 1000, 2000), health(100, 1000),
                fastModel("v1"), toggleModel("boom", failing));

        gw.score(req("s1", "v1"));
        gw.score(req("s2", "v1"));
        gw.score(new ScoreRequest("s3", "v1", 99, Map.of())); // 契约不兼容拒绝
        gw.score(req("s4", "boom"));                          // 内部异常降级

        VersionStatsSnapshot v1 = gw.statsSnapshot("v1");
        VersionStatsSnapshot boom = gw.statsSnapshot("boom");
        log.info("v1 stats: ok={} rejected={} health={}", v1.okCount(), v1.rejected(),
                v1.healthState());
        log.info("boom stats: degraded={} health={}", boom.degraded(), boom.healthState());
        assertEquals(2, v1.okCount());
        assertEquals(1L, v1.rejected().get(RejectReason.CONTRACT_INCOMPATIBLE));
        assertEquals(1L, boom.degraded().get(RejectReason.INTERNAL_ERROR));
        assertEquals(HealthState.HEALTHY, boom.healthState(), "阈值未到，不得误判不健康");
        assertTrue(gw.allStats().stream().map(VersionStatsSnapshot::modelVersion)
                .toList().containsAll(List.of("v1", "boom")));

        // 重置只清计数器：在途调度与后续判定不受影响
        gw.resetStats("v1");
        VersionStatsSnapshot v1After = gw.statsSnapshot("v1");
        VersionStatsSnapshot boomAfter = gw.statsSnapshot("boom");
        log.info("v1 after reset: ok={} rejected={}", v1After.okCount(), v1After.rejected());
        assertEquals(0, v1After.okCount());
        assertTrue(v1After.rejected().isEmpty());
        assertEquals(1L, boomAfter.degraded().get(RejectReason.INTERNAL_ERROR),
                "重置单版本不得影响其他版本计数");

        failing.set(false);
        ScoreResponse after = gw.score(req("s5", "boom"));
        log.info("traffic after reset: status={} reason={}", after.status(), after.reason());
        assertEquals(DecisionStatus.OK, after.status(), "重置统计不得改变后续请求判定");
        assertEquals(1, gw.statsSnapshot("boom").okCount());
    }

    @Test
    void queueCapacityUpdateRelievesOverloadAtRuntime() throws Exception {
        log.info("CASE 运行中调整参数：扩大排队上限后，同样的洪峰不再溢出");
        ScoringGateway gw = newGateway(
                new VersionCapacity(1, 1, 1, 1000, 2000), health(100, 1000),
                slowModel("m", 150));

        List<ScoreRequest> first = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            first.add(req("q1-" + i, "m"));
        }
        List<ScoreResponse> firstWave = fireConcurrent(gw, first);
        long firstFull = firstWave.stream()
                .filter(r -> r.reason() == RejectReason.QUEUE_FULL).count();
        log.info("small queue: QUEUE_FULL={}", firstFull);
        assertTrue(firstFull > 0, "小队列下洪峰必须溢出");

        // 运行中扩大队列：无需重启，洪峰不再溢出
        gw.updateCapacity("m", new VersionCapacity(1, 1, 64, 1000, 2000));
        gw.resetStats("m");
        List<ScoreRequest> second = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            second.add(req("q2-" + i, "m"));
        }
        List<ScoreResponse> secondWave = fireConcurrent(gw, second);
        long secondFull = secondWave.stream()
                .filter(r -> r.reason() == RejectReason.QUEUE_FULL).count();
        log.info("enlarged queue: QUEUE_FULL={}", secondFull);
        assertEquals(0, secondFull, "扩容后同样的洪峰不得再溢出");
        assertFalse(gw.statsSnapshot("m").rejected().containsKey(RejectReason.QUEUE_FULL));
    }
}
