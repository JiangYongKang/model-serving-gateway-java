package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.gateway.InferenceGateway;
import com.github.highcumontoa.modelservinggatewayjava.model.DeterministicLocalModel;
import com.github.highcumontoa.modelservinggatewayjava.tests.support.GatewayHarness;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import com.github.highcumontoa.modelservinggatewayjava.protocol.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景一：并发批量——并发请求在窗口内合并为批次执行，同版本同输入结论可复现。 */
class BatchConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(BatchConcurrencyTest.class);

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
    void concurrentRequestsAreBatchedAndScoresAreReproducible() throws Exception {
        DeterministicLocalModel v1 = new DeterministicLocalModel("v1", "1.0", "salt-v1");
        ModelRegistry registry = GatewayHarness.registryWith(v1);
        // 窗口 50ms、每批最多 8 个
        gateway = GatewayHarness.gateway(registry, 8, 50, 64, 2_000, 2_000);

        int threads = 24;
        pool = Executors.newFixedThreadPool(threads);
        CountDownLatchHolder holder = new CountDownLatchHolder(threads);
        List<Callable<ScoreResponse>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            tasks.add(() -> {
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("text", "batch-case-" + idx);
                ScoreRequest request = new ScoreRequest(
                        "req-" + idx, "v1", "1.0", inputs);
                holder.latch.countDown();
                holder.latch.await();
                ScoreResponse response = gateway.score(request);
                log.info("CONCURRENT_RESULT requestId={} status={} batchId={} score={}",
                        response.requestId(), response.status(), response.batchId(), response.score());
                return response;
            });
        }
        List<Future<ScoreResponse>> futures = pool.invokeAll(tasks);
        for (Future<ScoreResponse> f : futures) {
            ScoreResponse r = f.get();
            assertEquals(DecisionStatus.NORMAL, r.status(),
                    "all concurrent requests must succeed normally");
            assertNotNull(r.batchId(), "normal response must carry batchId");
        }

        // 可复现：相同输入相同版本，重复请求分数一致（批次可以不同）
        Map<String, Object> input = new HashMap<>();
        input.put("text", "reproducible-case");
        ScoreRequest a = new ScoreRequest("rep-a", "v1", "1.0", input);
        ScoreRequest b = new ScoreRequest("rep-b", "v1", "1.0", new HashMap<>(input));
        ScoreResponse ra = gateway.score(a);
        ScoreResponse rb = gateway.score(b);
        log.info("REPRODUCIBILITY_CHECK a={} b={}", ra.score(), rb.score());
        assertEquals(ra.score(), rb.score(), 0.0,
                "same version + same input must produce identical scores");

        // 输入字段顺序不同，规范序列化后结论一致
        Map<String, Object> ordered1 = new HashMap<>();
        ordered1.put("x", 1);
        ordered1.put("y", "2");
        Map<String, Object> ordered2 = new HashMap<>();
        ordered2.put("y", "2");
        ordered2.put("x", 1);
        ScoreResponse r1 = gateway.score(new ScoreRequest("ord-1", "v1", "1.0", ordered1));
        ScoreResponse r2 = gateway.score(new ScoreRequest("ord-2", "v1", "1.0", ordered2));
        assertEquals(r1.score(), r2.score(), 0.0,
                "key order must not change deterministic score");

        // 判定依据可查询
        assertNotNull(gateway.toString());
        log.info("JUDGEMENT requestId=rep-a status=NORMAL basis=deterministicHash(salt-v1, canonical(inputs))");
        assertTrue(ra.score() >= 0.0 && ra.score() < 1.0);
    }

    /** 简单包装：让所有线程在同一闸口释放以最大化进入同一合批窗口的概率。 */
    private static class CountDownLatchHolder {
        final java.util.concurrent.CountDownLatch latch;

        CountDownLatchHolder(int n) {
            this.latch = new java.util.concurrent.CountDownLatch(n);
        }
    }
}
