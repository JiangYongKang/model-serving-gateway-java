package com.github.highcumontoa.modelservinggatewayjava.web;

import com.github.highcumontoa.modelservinggatewayjava.gateway.DecisionAuditLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DecisionRecord;
import com.github.highcumontoa.modelservinggatewayjava.gateway.InferenceGateway;
import com.github.highcumontoa.modelservinggatewayjava.protocol.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 统一推理入口；拒绝/降级在 HTTP 层用 4xx/5xx 与 200 区分，但三类结论在响应体内始终可分辨。 */
@RestController
@RequestMapping("/api/v1/inference")
public class InferenceController {

    private final InferenceGateway gateway;
    private final DecisionAuditLog auditLog;

    public InferenceController(InferenceGateway gateway, DecisionAuditLog auditLog) {
        this.gateway = gateway;
        this.auditLog = auditLog;
    }

    @PostMapping("/score")
    public ResponseEntity<ScoreResponse> score(@RequestBody ScoreRequest request) {
        ScoreResponse response = gateway.score(request);
        return ResponseEntity.status(httpStatus(response.status())).body(response);
    }

    /** 查询单个请求的判定依据（含降级原因）。 */
    @GetMapping("/decisions/{requestId}")
    public ResponseEntity<DecisionRecord> decision(@PathVariable String requestId) {
        return auditLog.get(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 最近判定记录（正常/降级/拒绝均含原因码与依据）。 */
    @GetMapping("/decisions")
    public List<DecisionRecord> recent() {
        return auditLog.recent();
    }

    private static int httpStatus(DecisionStatus status) {
        return switch (status) {
            case NORMAL, DEGRADED -> 200;
            case REJECTED -> 422;
        };
    }
}
