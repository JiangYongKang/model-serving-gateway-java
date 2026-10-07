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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容量治理与观测端点协议测试：
 * 运行中调整承载参数、按版本查询运行状态、重置计数。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GovernanceControllerTests {

    private static final Logger log = LoggerFactory.getLogger(GovernanceControllerTests.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void capacityIsQueryableAndUpdatableAtRuntime() throws Exception {
        log.info("CASE 端点：查询并运行中更新 v1 承载参数");
        MvcResult before = mvc.perform(get("/api/v1/versions/v1/capacity"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("default capacity: {}", before.getResponse().getContentAsString());

        mvc.perform(put("/api/v1/versions/v1/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxBatchSize":4,"batchWindowMs":10,"queueCapacity":32,
                                 "maxQueueWaitMs":100,"executionTimeoutMs":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueCapacity").value(32))
                .andExpect(jsonPath("$.executionTimeoutMs").value(300));

        mvc.perform(get("/api/v1/versions/v1/capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBatchSize").value(4));

        // 非法参数（非正数）必须被拒绝
        mvc.perform(put("/api/v1/versions/v1/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxBatchSize":0,"batchWindowMs":10,"queueCapacity":32,
                                 "maxQueueWaitMs":100,"executionTimeoutMs":300}
                                """))
                .andExpect(status().isBadRequest());

        // 还原默认参数，避免影响共享上下文中其他用例
        mvc.perform(put("/api/v1/versions/v1/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxBatchSize":8,"batchWindowMs":20,"queueCapacity":64,
                                 "maxQueueWaitMs":200,"executionTimeoutMs":500}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void statsAreQueryableAndResettableOverHttp() throws Exception {
        log.info("CASE 端点：打分后按版本查询运行状态并重置");
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g1","modelVersion":"v2","contractVersion":2,
                                 "features":{"income":2.0,"debt":0.5,"tenure":3.0}}
                                """))
                .andExpect(status().isOk());

        MvcResult stats = mvc.perform(get("/api/v1/versions/v2/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("v2"))
                .andExpect(jsonPath("$.healthState").value("HEALTHY"))
                .andExpect(jsonPath("$.okCount").value(1))
                .andReturn();
        log.info("v2 stats: {}", stats.getResponse().getContentAsString());

        MvcResult all = mvc.perform(get("/api/v1/versions/stats"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("all stats: {}", all.getResponse().getContentAsString());

        mvc.perform(post("/api/v1/versions/v2/stats/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.okCount").value(0));

        // 重置后正常打分不受影响
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g2","modelVersion":"v2","contractVersion":2,
                                 "features":{"income":2.0,"debt":0.5,"tenure":3.0}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }
}
