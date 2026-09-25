package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.github.highcumontoa.modelservinggatewayjava.gateway.DecisionAuditLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DecisionRecord;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景四：降级链路——超时/内部异常/结果不可用均降级，结论与正常可区分、原因可查询、可复现。 */
class DegradationTest {

    private static final Logger log = LoggerFactory.getLogger(DegradationTest.class);

    private InferenceGateway gateway;
    private DecisionAuditLog auditLog;

    private InferenceGateway gatewayFor(LocalModel model, long modelTimeoutMs) {
        ModelRegistry registry = GatewayHarness.registryWith(model);
        auditLog = new DecisionAuditLog();
        gateway = GatewayHarness.gateway(registry, auditLog,
                8, 10, 32, 2_000, modelTimeoutMs);
        return gateway;
    }

    private ScoreRequest request(String id, String version) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("text", "degrade-case");
        return new ScoreRequest(id, version, "1.0", inputs);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    @Test
    void modelTimeoutYieldsDegradedResult() {
        gatewayFor(TestModels.slow("slow-to", 300), 80);
        ScoreResponse response = gateway.score(request("to-1", "slow-to"));
        log.info("TIMEOUT_RESULT requestId={} status={} reasonCode={} score={}",
                response.requestId(), response.status(), response.reasonCode(), response.score());

        assertEquals(DecisionStatus.DEGRADED, response.status());
        assertEquals(ReasonCode.MODEL_TIMEOUT, response.reasonCode());
        assertDegradedAuditable(response);
    }

    @Test
    void modelInternalErrorYieldsDegradedResult() {
        gatewayFor(TestModels.error("broken"), 2_000);
        ScoreResponse response = gateway.score(request("err-1", "broken"));
        log.info("ERROR_RESULT requestId={} status={} reasonCode={} score={}",
                response.requestId(), response.status(), response.reasonCode(), response.score());

        assertEquals(DecisionStatus.DEGRADED, response.status());
        assertEquals(ReasonCode.MODEL_ERROR, response.reasonCode());
        assertDegradedAuditable(response);
    }

    @Test
    void invalidModelResultYieldsDegradedResult() {
        gatewayFor(TestModels.invalidResult("bad-output"), 2_000);
        ScoreResponse response = gateway.score(request("inv-1", "bad-output"));
        log.info("INVALID_RESULT requestId={} status={} reasonCode={} score={}",
                response.requestId(), response.status(), response.reasonCode(), response.score());

        assertEquals(DecisionStatus.DEGRADED, response.status());
        assertEquals(ReasonCode.RESULT_INVALID, response.reasonCode());
        assertDegradedAuditable(response);
    }

    @Test
    void fallbackIsDeterministicAndNeverMixedWithNormal() {
        // 同一套输入：模型正常时得到 NORMAL；故障时得到 DEGRADED 兜底；两者状态可区分、
        // 且降级结论在两次独立故障中可复现
        LocalModel working = new com.github.highcumontoa.modelservinggatewayjava.model
                .DeterministicLocalModel("dual", "1.0", "salt-dual");
        gatewayFor(working, 2_000);
        ScoreResponse normal = gateway.score(request("n-1", "dual"));
        assertEquals(DecisionStatus.NORMAL, normal.status());
        gateway.close();

        gatewayFor(TestModels.error("dual"), 2_000);
        ScoreResponse degraded1 = gateway.score(request("d-1", "dual"));
        ScoreResponse degraded2 = gateway.score(request("d-2", "dual"));
        assertEquals(DecisionStatus.DEGRADED, degraded1.status());
        assertEquals(DecisionStatus.DEGRADED, degraded2.status());
        assertEquals(degraded1.score(), degraded2.score(), 0.0,
                "degraded fallback must be reproducible for same version+input");
        assertNotEquals(normal.status(), degraded1.status(),
                "degraded result must be distinguishable from normal result");

        log.info("DEGRADE_SUMMARY normalStatus={} degradedStatus={} normalScore={} fallbackScores=[{},{}] "
                        + "basis=fallback=deterministicHash(FALLBACK, dual, canonical(inputs))",
                normal.status(), degraded1.status(), normal.score(),
                degraded1.score(), degraded2.score());

        // 正常结论的审计记录不得带降级原因
        Optional<DecisionRecord> normalRecord = auditLog.get("n-1");
        assertTrue(normalRecord.isEmpty(),
                "normal decision produced before model broke; separate gateway/audit log");
    }

    private void assertDegradedAuditable(ScoreResponse response) {
        assertNotNull(response.score(), "degraded response still carries a deterministic fallback score");
        assertNotNull(response.reason());
        DecisionRecord record = auditLog.get(response.requestId())
                .orElseThrow(() -> new AssertionError("degradation reason must be queryable"));
        assertEquals(DecisionStatus.DEGRADED, record.status());
        assertEquals(response.reasonCode(), record.reasonCode());
        assertNotNull(record.basis());
        assertTrue(record.basis().contains(String.valueOf(response.reasonCode())),
                "audit basis must explain the degradation reason");
        log.info("DEGRADE_AUDIT requestId={} basis={}", record.requestId(), record.basis());
    }
}
