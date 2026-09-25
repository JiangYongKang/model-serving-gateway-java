package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.batch.BatchProperties;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DegradationLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.ScoringGateway;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关行为测试：并发批量、过载拒绝、版本兼容与降级。
 * 日志打印请求、批次分组与判定依据，便于人工核对。
 */
class ScoringGatewayTests {

    private static final Logger log = LoggerFactory.getLogger(ScoringGatewayTests.class);

    private ModelRegistry registry;
    private DegradationLog degradationLog;
    private ScoringGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    private ScoringGateway newGateway(BatchProperties props, LocalModel... models) {
        registry = new ModelRegistry();
        for (LocalModel m : models) {
            registry.register(m);
        }
        degradationLog = new DegradationLog();
        gateway = new ScoringGateway(registry, degradationLog, props);
        return gateway;
    }

    private static BatchProperties props(int maxBatchSize, long windowMs, int queueCapacity,
                                         long maxQueueWaitMs, long executionTimeoutMs) {
        BatchProperties p = new BatchProperties();
        p.setMaxBatchSize(maxBatchSize);
        p.setBatchWindowMs(windowMs);
        p.setQueueCapacity(queueCapacity);
        p.setMaxQueueWaitMs(maxQueueWaitMs);
        p.setExecutionTimeoutMs(executionTimeoutMs);
        return p;
    }

    private static LocalModel fastModel(String version, int contract) {
        return new LinearScoringModel(version, contract,
                Map.of("income", 1.2, "debt", -1.5), -0.3);
    }

    private static LocalModel slowModel(String version, int contract, long sleepMs) {
        return new LocalModel() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public int contractVersion() {
                return contract;
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

    private static ScoreRequest req(String id, String version, Integer contract) {
        return new ScoreRequest(id, version, contract, Map.of("income", 2.0, "debt", 0.5));
    }

    /** 并发提交 n 个请求，带统一起跑线。 */
    private List<ScoreResponse> fireConcurrent(ScoringGateway gw, List<ScoreRequest> requests)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ScoreResponse>> futures = new ArrayList<>();
        for (ScoreRequest r : requests) {
            futures.add(pool.submit(() -> {
                gate.await();
                log.info("submitting request: {}", r);
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

    private static void printDecisionBasis(List<ScoreResponse> responses) {
        for (ScoreResponse r : responses) {
            log.info("decision basis: requestId={} status={} score={} label={} reason={} batch={} batchSize={}",
                    r.requestId(), r.status(), r.score(), r.label(), r.reason(),
                    r.batchId(), r.batchSize());
        }
    }

    @Test
    void concurrentRequestsAreMergedIntoBatches() throws Exception {
        ScoringGateway gw = newGateway(props(8, 50, 64, 1000, 2000), fastModel("v1", 1));
        List<ScoreRequest> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(req("r" + i, "v1", 1));
        }
        List<ScoreResponse> responses = fireConcurrent(gw, requests);
        printDecisionBasis(responses);

        assertTrue(responses.stream().allMatch(r -> r.status() == DecisionStatus.OK));
        int maxBatch = responses.stream().mapToInt(ScoreResponse::batchSize).max().orElse(0);
        log.info("observed max batch size = {}", maxBatch);
        assertTrue(maxBatch > 1, "并发请求应被合并为批量执行");
        // 同批请求共享同一 batchId
        long distinctBatches = responses.stream().map(ScoreResponse::batchId).distinct().count();
        log.info("distinct batch ids = {}", distinctBatches);
        assertTrue(distinctBatches < responses.size(), "应存在请求共享同一批次");
    }

    @Test
    void queueOverflowIsRejectedWithQueueFull() throws Exception {
        // 慢模型 + 极小队列：并发洪峰必然溢出
        ScoringGateway gw = newGateway(props(1, 1, 2, 1000, 2000),
                slowModel("v1", 1, 150));
        List<ScoreRequest> requests = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            requests.add(req("of" + i, "v1", 1));
        }
        List<ScoreResponse> responses = fireConcurrent(gw, requests);
        printDecisionBasis(responses);

        long queueFull = responses.stream()
                .filter(r -> r.reason() == RejectReason.QUEUE_FULL).count();
        log.info("QUEUE_FULL rejections = {}", queueFull);
        assertTrue(queueFull > 0, "队列溢出必须以 QUEUE_FULL 明确拒绝");
        // 不得静默丢弃：每个请求都有明确结论
        assertTrue(responses.stream().allMatch(r -> r.status() != null));
        assertTrue(responses.stream().noneMatch(
                r -> r.status() == DecisionStatus.REJECTED && r.reason() == RejectReason.NONE));
    }

    @Test
    void waitTimeoutIsRejectedDistinctFromQueueFull() throws Exception {
        // 队列够大不会溢出，但排队等待超过上限
        ScoringGateway gw = newGateway(props(1, 1, 16, 30, 2000),
                slowModel("v1", 1, 120));
        List<ScoreRequest> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(req("wt" + i, "v1", 1));
        }
        List<ScoreResponse> responses = fireConcurrent(gw, requests);
        printDecisionBasis(responses);

        long waitTimeout = responses.stream()
                .filter(r -> r.reason() == RejectReason.WAIT_TIMEOUT).count();
        long queueFull = responses.stream()
                .filter(r -> r.reason() == RejectReason.QUEUE_FULL).count();
        log.info("WAIT_TIMEOUT={} QUEUE_FULL={}", waitTimeout, queueFull);
        assertTrue(waitTimeout > 0, "排队超时必须以 WAIT_TIMEOUT 拒绝");
        assertEquals(0, queueFull, "本场景不应出现 QUEUE_FULL，两种拒绝原因必须可区分");
    }

    @Test
    void versionRoutingErrorsAreDistinguishable() {
        ScoringGateway gw = newGateway(props(4, 10, 16, 500, 1000), fastModel("v1", 1));
        registry.retire("v0");

        ScoreResponse missing = gw.score(req("m1", null, null));
        ScoreResponse unknown = gw.score(req("m2", "v9", 1));
        ScoreResponse retired = gw.score(req("m3", "v0", 1));
        ScoreResponse incompatible = gw.score(req("m4", "v1", 99));
        ScoreResponse nullFeatures = gw.score(
                new ScoreRequest("m5", "v1", 1, null));
        List<ScoreResponse> all = List.of(missing, unknown, retired, incompatible, nullFeatures);
        printDecisionBasis(all);

        assertEquals(RejectReason.VERSION_MISSING, missing.reason());
        assertEquals(RejectReason.VERSION_UNKNOWN, unknown.reason());
        assertEquals(RejectReason.VERSION_RETIRED, retired.reason());
        assertEquals(RejectReason.CONTRACT_INCOMPATIBLE, incompatible.reason());
        assertEquals(RejectReason.CONTRACT_INCOMPATIBLE, nullFeatures.reason());
        assertTrue(all.stream().allMatch(r -> r.status() == DecisionStatus.REJECTED));
        // 不兼容时不得静默使用默认版本给出结论
        assertTrue(all.stream().allMatch(r -> r.score() == null));
    }

    @Test
    void sameInputSameVersionIsReproducible() {
        ScoringGateway gw = newGateway(props(4, 5, 16, 500, 1000), fastModel("v1", 1));
        ScoreRequest request = req("rep", "v1", 1);
        ScoreResponse first = gw.score(request);
        ScoreResponse second = gw.score(request);
        ScoreResponse third = gw.score(request);
        printDecisionBasis(List.of(first, second, third));

        assertEquals(DecisionStatus.OK, first.status());
        assertEquals(first.score(), second.score());
        assertEquals(second.score(), third.score());
        assertEquals(first.label(), third.label());
    }

    @Test
    void executionTimeoutDegradesAndIsQueryable() throws Exception {
        // 慢模型超过执行超时：走降级；正常版本不受影响
        ScoringGateway gw = newGateway(props(1, 1, 16, 1000, 80),
                slowModel("slow", 1, 500), fastModel("v1", 1));

        ScoreResponse degraded = gw.score(req("d1", "slow", 1));
        ScoreResponse normal = gw.score(req("n1", "v1", 1));
        printDecisionBasis(List.of(degraded, normal));

        assertEquals(DecisionStatus.DEGRADED, degraded.status());
        assertEquals(RejectReason.EXECUTION_TIMEOUT, degraded.reason());
        assertEquals(ScoringGateway.FALLBACK_SCORE, degraded.score());
        assertEquals("FALLBACK", degraded.label());
        // 降级原因可查询
        assertTrue(degradationLog.all().stream().anyMatch(
                r -> r.requestId().equals("d1") && r.reason() == RejectReason.EXECUTION_TIMEOUT));
        // 降级不污染正常结果
        assertEquals(DecisionStatus.OK, normal.status());
        assertNotEquals("FALLBACK", normal.label());
    }

    @Test
    void internalErrorAndUnavailableResultDegradeSeparately() {
        LocalModel throwing = new LocalModel() {
            @Override
            public String version() {
                return "boom";
            }

            @Override
            public int contractVersion() {
                return 1;
            }

            @Override
            public double score(Map<String, Double> features) {
                throw new IllegalStateException("simulated internal failure");
            }
        };
        LocalModel nan = new LocalModel() {
            @Override
            public String version() {
                return "nan";
            }

            @Override
            public int contractVersion() {
                return 1;
            }

            @Override
            public double score(Map<String, Double> features) {
                return Double.NaN;
            }
        };
        ScoringGateway gw = newGateway(props(4, 5, 16, 500, 1000),
                throwing, nan, fastModel("v1", 1));

        ScoreResponse internal = gw.score(req("e1", "boom", 1));
        ScoreResponse unavailable = gw.score(req("e2", "nan", 1));
        ScoreResponse normal = gw.score(req("e3", "v1", 1));
        printDecisionBasis(List.of(internal, unavailable, normal));

        assertEquals(DecisionStatus.DEGRADED, internal.status());
        assertEquals(RejectReason.INTERNAL_ERROR, internal.reason());
        assertEquals(DecisionStatus.DEGRADED, unavailable.status());
        assertEquals(RejectReason.RESULT_UNAVAILABLE, unavailable.reason());
        // 降级结果与正常结果可区分
        assertEquals("FALLBACK", internal.label());
        assertEquals(ScoringGateway.FALLBACK_SCORE, unavailable.score());
        // 正常结论不受影响
        assertEquals(DecisionStatus.OK, normal.status());
        // 两类降级原因均可查询
        assertTrue(degradationLog.all().stream()
                .anyMatch(r -> r.reason() == RejectReason.INTERNAL_ERROR));
        assertTrue(degradationLog.all().stream()
                .anyMatch(r -> r.reason() == RejectReason.RESULT_UNAVAILABLE));
    }
}
