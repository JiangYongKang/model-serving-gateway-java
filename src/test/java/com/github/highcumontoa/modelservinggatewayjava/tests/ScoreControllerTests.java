package com.github.highcumontoa.modelservinggatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一入口协议测试：验证 HTTP 状态码与响应体中可区分的原因。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScoreControllerTests {

    private static final Logger log = LoggerFactory.getLogger(ScoreControllerTests.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void normalScoreReturnsOk() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w1","modelVersion":"v1","contractVersion":1,
                                 "features":{"income":2.0,"debt":0.5}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.reason").value("NONE"))
                .andReturn();
        log.info("normal score response: {}", result.getResponse().getContentAsString());
    }

    @Test
    void routingErrorsMapToDistinctHttpStatuses() throws Exception {
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2\",\"features\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VERSION_MISSING"));
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3\",\"modelVersion\":\"v9\",\"features\":{}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("VERSION_UNKNOWN"));
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w4\",\"modelVersion\":\"v0\",\"features\":{}}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("VERSION_RETIRED"));
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w5\",\"modelVersion\":\"v1\",\"contractVersion\":2,\"features\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("CONTRACT_INCOMPATIBLE"));
    }

    @Test
    void modelsAndDegradationsAreQueryable() throws Exception {
        MvcResult models = mvc.perform(get("/api/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("v1"))
                .andExpect(jsonPath("$[1]").value("v2"))
                .andReturn();
        log.info("online models: {}", models.getResponse().getContentAsString());

        MvcResult degradations = mvc.perform(get("/api/v1/degradations"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("degradation records: {}", degradations.getResponse().getContentAsString());
    }
}
