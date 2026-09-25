package com.github.highcumontoa.modelservinggatewayjava.api;

import java.util.Map;

/** 统一入口评分请求。 */
public record ScoreRequest(
        String requestId,
        String modelVersion,
        Integer contractVersion,
        Map<String, Double> features) {
}
