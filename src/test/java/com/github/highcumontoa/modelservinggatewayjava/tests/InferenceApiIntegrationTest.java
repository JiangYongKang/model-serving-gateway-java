package com.github.highcumontoa.modelservinggatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端：统一入口 HTTP 协议、状态码区分、判定依据可查询。 */
@SpringBootTest
@AutoConfigureMockMvc
class InferenceApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private static String body(String requestId, String version, String contract,
                               Map<String, Object> inputs) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("modelVersion", version);
        payload.put("contractVersion", contract);
        payload.put("inputs", inputs);
        return MAPPER.writeValueAsString(payload);
    }

    @Test
    void scoreRoutingRejectionAndDecisionQueryOverHttp() throws Exception {
        // 正常：v1 / 契约 1.0
        MvcResult ok = mockMvc.perform(post("/api/v1/inference/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-ok", "v1", "1.0", Map.of("text", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.batchId").exists())
                .andReturn();
        JsonNode okJson = MAPPER.readTree(ok.getResponse().getContentAsString());
        double score = okJson.get("score").asDouble();

        // 同输入复现
        mockMvc.perform(post("/api/v1/inference/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-ok2", "v1", "1.0", Map.of("text", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.score").value(score));

        // 缺失版本 → 422 可区分原因
        mockMvc.perform(post("/api/v1/inference/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-nf", "v9", "1.0", Map.of("text", "hello"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("VERSION_NOT_FOUND"));

        // 已下线 → 422 可区分原因
        mockMvc.perform(post("/api/v1/inference/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-rt", "v0", "1.0", Map.of("text", "hello"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("VERSION_RETIRED"));

        // 契约不兼容 → 422 可区分原因
        mockMvc.perform(post("/api/v1/inference/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-cc", "v2", "1.0", Map.of("features", new double[]{1, 2}))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("CONTRACT_INCOMPATIBLE"));

        // 判定依据可查询
        mockMvc.perform(get("/api/v1/inference/decisions/it-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.basis").exists());

        MvcResult recent = mockMvc.perform(get("/api/v1/inference/decisions"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = MAPPER.readTree(recent.getResponse().getContentAsString());
        assertTrue(array.isArray());
        assertTrue(array.size() >= 4);
        assertEquals(score, okJson.get("score").asDouble(), 0.0);
    }
}
