package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.config.LocalModelBootstrap;
import com.github.highcumontoa.modelservinggatewayjava.gateway.InferenceGateway;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import com.github.highcumontoa.modelservinggatewayjava.protocol.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ReasonCode;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.tests.support.GatewayHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 场景三：版本路由与兼容——缺失/下线/契约不兼容必须返回可区分错误，禁止静默默认版本；同版本可复现。 */
class VersionRoutingTest {

    private static final Logger log = LoggerFactory.getLogger(VersionRoutingTest.class);

    private InferenceGateway gateway;

    private InferenceGateway gateway() {
        ModelRegistry registry = new LocalModelBootstrap().modelRegistry();
        return GatewayHarness.gateway(registry, 8, 10, 32, 2_000, 2_000);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    private ScoreResponse send(String id, String version, String contract,
                               Map<String, Object> inputs) {
        ScoreRequest request = new ScoreRequest(id, version, contract, inputs);
        ScoreResponse response = gateway.score(request);
        log.info("ROUTE_RESULT requestId={} requestedVersion={} contract={} status={} reasonCode={} reason={}",
                id, version, contract, response.status(), response.reasonCode(), response.reason());
        return response;
    }

    @Test
    void missingRetiredAndIncompatibleVersionsGiveDistinctErrors() {
        gateway = gateway();

        Map<String, Object> textInput = new HashMap<>();
        textInput.put("text", "hello");
        Map<String, Object> features = new HashMap<>();
        features.put("features", List.of(0.1, 0.2, 0.3));

        // 1) 版本缺失
        ScoreResponse notFound = send("r-1", "v9", "1.0", textInput);
        assertEquals(DecisionStatus.REJECTED, notFound.status());
        assertEquals(ReasonCode.VERSION_NOT_FOUND, notFound.reasonCode());

        // 2) 版本号为空——同样不得静默使用默认版本
        ScoreResponse missing = send("r-2", null, "1.0", textInput);
        assertEquals(DecisionStatus.REJECTED, missing.status());
        assertEquals(ReasonCode.VERSION_NOT_FOUND, missing.reasonCode());

        // 3) 版本已下线
        ScoreResponse retired = send("r-3", "v0", "1.0", textInput);
        assertEquals(DecisionStatus.REJECTED, retired.status());
        assertEquals(ReasonCode.VERSION_RETIRED, retired.reasonCode());

        // 4) 契约版本不匹配（v2 只接受 2.0，不能用 1.0 静默执行）
        ScoreResponse wrongContract = send("r-4", "v2", "1.0", features);
        assertEquals(DecisionStatus.REJECTED, wrongContract.status());
        assertEquals(ReasonCode.CONTRACT_INCOMPATIBLE, wrongContract.reasonCode());

        // 5) 契约版本正确但缺少 v2 必需输入字段
        ScoreResponse missingField = send("r-5", "v2", "2.0", textInput);
        assertEquals(DecisionStatus.REJECTED, missingField.status());
        assertEquals(ReasonCode.CONTRACT_INCOMPATIBLE, missingField.reasonCode());

        // 正常基线：v1/1.0 与 v2/2.0 可用，错误响应没有任何分数混入
        ScoreResponse ok1 = send("r-6", "v1", "1.0", textInput);
        ScoreResponse ok2 = send("r-7", "v2", "2.0", features);
        assertEquals(DecisionStatus.NORMAL, ok1.status());
        assertEquals(DecisionStatus.NORMAL, ok2.status());
        assertNotNull(ok1.score());
        assertNotNull(ok2.score());

        log.info("ROUTE_SUMMARY basis=精确版本注册表, 缺失->VERSION_NOT_FOUND, 下线->VERSION_RETIRED, 契约/输入->CONTRACT_INCOMPATIBLE, 不存在任何默认版本兜底");
    }

    @Test
    void sameVersionSameInputIsReproducibleAcrossCalls() {
        gateway = gateway();
        Map<String, Object> input = new HashMap<>();
        input.put("text", "deterministic-routing-case");

        ScoreResponse first = send("c-1", "v1", "1.0", input);
        ScoreResponse second = send("c-2", "v1", "1.0", new HashMap<>(input));
        ScoreResponse third = send("c-3", "v1", "1.0", new HashMap<>(input));

        assertEquals(DecisionStatus.NORMAL, first.status());
        assertEquals(first.score(), second.score(), 0.0);
        assertEquals(first.score(), third.score(), 0.0);
        log.info("REPRO_SUMMARY requestIds=[c-1,c-2,c-3] version=v1 scores=[{},{},{}] basis=deterministicHash(salt-v1, canonical(inputs))",
                first.score(), second.score(), third.score());
    }
}
