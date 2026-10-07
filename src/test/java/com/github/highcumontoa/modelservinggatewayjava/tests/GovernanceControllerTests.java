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
 * 按版本治理端点测试：运行状态查询/重置、运行中调参。
 * 查询与重置不干扰正常调度，也不改变已有请求的判定结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GovernanceControllerTests {

    private static final Logger log = LoggerFactory.getLogger(GovernanceControllerTests.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void statsCapacityAndResetEndpointsWork() throws Exception {
        log.info("业务覆盖：治理端点 —— 评分后按版本查询状态、运行中调参、重置计数");
        // 先产生一次正常判定，触发 v1 治理器创建
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g1","modelVersion":"v1","contractVersion":1,
                                 "features":{"income":2.0,"debt":0.5}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        // 查询运行状态：排队深度、在途数、计数与健康状态可见
        MvcResult stats = mvc.perform(get("/api/v1/versions/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.health").value("HEALTHY"))
                .andExpect(jsonPath("$.okCount").value(1))
                .andExpect(jsonPath("$.retryAfterMs").value(0))
                .andReturn();
        log.info("v1 stats: {}", stats.getResponse().getContentAsString());

        // 未治理过的版本：查询返回 404，不创建治理器
        mvc.perform(get("/api/v1/versions/v9/stats"))
                .andExpect(status().isNotFound());

        // 运行中调参：无需重启即生效，可查询确认
        mvc.perform(put("/api/v1/versions/v1/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxBatchSize":4,"batchWindowMs":10,"queueCapacity":32,
                                 "maxQueueWaitMs":300,"executionTimeoutMs":800,
                                 "failureThreshold":5,"recoveryMs":5000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueCapacity").value(32));
        MvcResult capacity = mvc.perform(get("/api/v1/versions/v1/capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueCapacity").value(32))
                .andExpect(jsonPath("$.executionTimeoutMs").value(800))
                .andReturn();
        log.info("v1 capacity after update: {}", capacity.getResponse().getContentAsString());

        // 非法参数（非正数）拒绝
        mvc.perform(put("/api/v1/versions/v1/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxBatchSize":0,"batchWindowMs":10,"queueCapacity":32,
                                 "maxQueueWaitMs":300,"executionTimeoutMs":800,
                                 "failureThreshold":5,"recoveryMs":5000}
                                """))
                .andExpect(status().isBadRequest());

        // 调参后正常判定不受影响
        mvc.perform(post("/api/v1/score").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g2","modelVersion":"v1","contractVersion":1,
                                 "features":{"income":2.0,"debt":0.5}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        // 重置：计数清零，健康状态为 HEALTHY
        MvcResult reset = mvc.perform(post("/api/v1/versions/v1/stats/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.okCount").value(0))
                .andExpect(jsonPath("$.health").value("HEALTHY"))
                .andReturn();
        log.info("v1 stats after reset: {}", reset.getResponse().getContentAsString());

        // 全量状态查询
        MvcResult all = mvc.perform(get("/api/v1/versions/stats"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("all stats: {}", all.getResponse().getContentAsString());
    }
}
