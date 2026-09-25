package com.github.highcumontoa.modelservinggatewayjava.protocol;

import java.util.Map;

/** 统一入口评分请求。 */
public record ScoreRequest(
        String requestId,
        String modelVersion,
        String contractVersion,
        Map<String, Object> inputs) {
}
