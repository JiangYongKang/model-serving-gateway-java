package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.gateway.InferenceGateway;
import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import com.github.highcumontoa.modelservinggatewayjava.protocol.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ReasonCode;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.tests.support.GatewayHarness;
import com.github.highcumontoa.modelservinggatewayjava.tests.support.TestModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景二：过载控制——排队深度超限拒绝 QUEUE_FULL；排队时间超限拒绝 WAIT_TIMEOUT；原因可区分。 */
class OverloadRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(OverloadRejectionTest.class);

    private InferenceGateway gateway;
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void queueDepthLimitRejectsWithDistinctQueueFullReason() throws Exception {
        LocalModel slow = TestModels.slow("slow-v1", 300);
        ModelRegistry registry = GatewayHarness.registryWith(slow);
        // 每批 2、窗口 10ms、排队深度 2、等待上限 5s、模型超时 5s
        gateway = GatewayHarness.gateway(registry, 2, 10, 2, 5_000, 5_000);

        int total = 10;
        pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(total);
        List<Future<ScoreResponse>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("text", "overload-depth-" + idx);
                ScoreRequest request = new ScoreRequest(
                        "depth-" + idx, "slow-v1", "1.0", inputs);
                start.countDown();
                start.await();
                return gateway.score(request);
            }));
        }
        List<ScoreResponse> responses = new ArrayList<>();
        for (Future<ScoreResponse> f : futures) {
            responses.add(f.get());
        }

        long normal = responses.stream().filter(r -> r.status() == DecisionStatus.NORMAL).count();
        long rejected = responses.stream().filter(r -> r.status() == DecisionStatus.REJECTED).count();
        long queueFull = responses.stream()
                .filter(r -> r.reasonCode() == ReasonCode.QUEUE_FULL).count();

        responses.forEach(r -> log.info("OVERLOAD_RESULT requestId={} status={} reasonCode={} reason={}",
                r.requestId(), r.status(), r.reasonCode(), r.reason()));
        log.info("OVERLOAD_SUMMARY basis=queueDepth=2 正常执行2+排队2, 其余必须被拒绝 normal={} rejected={} queueFull={}",
                normal, rejected, queueFull);

        assertTrue(queueFull >= 1, "must reject some requests with QUEUE_FULL");
        assertEquals(queueFull, rejected, "all rejections in this case must be QUEUE_FULL");
        assertEquals(total, normal + rejected, "every request must get a conclusive response");
        responses.stream().filter(r -> r.status() == DecisionStatus.REJECTED).forEach(r -> {
            assertNotNull(r.reason());
            assertFalse(r.reason().isBlank(), "rejection must carry an explainable reason");
        });
    }

    @Test
    void queueWaitTimeoutRejectsWithDistinctWaitTimeoutReason() throws Exception {
        LocalModel slow = TestModels.slow("slow-v2", 400);
        ModelRegistry registry = GatewayHarness.registryWith(slow);
        // 每批 1、窗口 5ms、排队深度 10、等待上限 60ms、模型超时 5s
        gateway = GatewayHarness.gateway(registry, 1, 5, 10, 60, 5_000);

        int total = 5;
        pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(total);
        List<Future<ScoreResponse>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("text", "overload-wait-" + idx);
                ScoreRequest request = new ScoreRequest(
                        "wait-" + idx, "slow-v2", "1.0", inputs);
                start.countDown();
                start.await();
                return gateway.score(request);
            }));
        }
        List<ScoreResponse> responses = new ArrayList<>();
        for (Future<ScoreResponse> f : futures) {
            responses.add(f.get());
        }

        long waitTimeout = responses.stream()
                .filter(r -> r.reasonCode() == ReasonCode.WAIT_TIMEOUT).count();
        long queueFull = responses.stream()
                .filter(r -> r.reasonCode() == ReasonCode.QUEUE_FULL).count();
        long normal = responses.stream().filter(r -> r.status() == DecisionStatus.NORMAL).count();

        responses.forEach(r -> log.info("WAIT_RESULT requestId={} status={} reasonCode={} reason={}",
                r.requestId(), r.status(), r.reasonCode(), r.reason()));
        log.info("WAIT_SUMMARY basis=执行槽400ms繁忙, 排队上限60ms到期 -> WAIT_TIMEOUT normal={} waitTimeout={} queueFull={}",
                normal, waitTimeout, queueFull);

        assertTrue(waitTimeout >= 1, "must reject some requests with WAIT_TIMEOUT");
        assertEquals(0, queueFull, "queue depth is large enough; no QUEUE_FULL expected");
        assertEquals(0, responses.stream().filter(r -> r.status() == DecisionStatus.DEGRADED).count(),
                "overload rejection must not be reported as degraded");
    }
}
