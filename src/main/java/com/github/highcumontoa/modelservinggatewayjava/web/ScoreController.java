package com.github.highcumontoa.modelservinggatewayjava.web;

import com.github.highcumontoa.modelservinggatewayjava.api.DecisionStatus;
import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreRequest;
import com.github.highcumontoa.modelservinggatewayjava.api.ScoreResponse;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DegradationLog;
import com.github.highcumontoa.modelservinggatewayjava.gateway.DegradationRecord;
import com.github.highcumontoa.modelservinggatewayjava.gateway.ScoringGateway;
import com.github.highcumontoa.modelservinggatewayjava.model.ModelRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一评分入口。
 * 响应体始终为 {@link ScoreResponse}，拒绝/降级原因通过 status+reason 可区分；
 * HTTP 状态码仅作为传输层提示，判定语义以响应体为准。
 */
@RestController
@RequestMapping("/api/v1")
public class ScoreController {

    private final ScoringGateway gateway;
    private final ModelRegistry registry;
    private final DegradationLog degradationLog;

    public ScoreController(ScoringGateway gateway, ModelRegistry registry,
                           DegradationLog degradationLog) {
        this.gateway = gateway;
        this.registry = registry;
        this.degradationLog = degradationLog;
    }

    @PostMapping("/score")
    public ResponseEntity<ScoreResponse> score(@RequestBody ScoreRequest request) {
        ScoreResponse response = gateway.score(request);
        return ResponseEntity.status(httpStatusOf(response)).body(response);
    }

    /** 在线模型版本列表。 */
    @GetMapping("/models")
    public List<String> models() {
        return registry.onlineVersions();
    }

    /** 降级事件查询：降级原因可追溯。 */
    @GetMapping("/degradations")
    public List<DegradationRecord> degradations() {
        return degradationLog.all();
    }

    private static HttpStatus httpStatusOf(ScoreResponse response) {
        if (response.status() != DecisionStatus.REJECTED) {
            return HttpStatus.OK;
        }
        return switch (response.reason()) {
            case QUEUE_FULL, WAIT_TIMEOUT -> HttpStatus.TOO_MANY_REQUESTS;
            case VERSION_MISSING, CONTRACT_INCOMPATIBLE -> HttpStatus.BAD_REQUEST;
            case VERSION_UNKNOWN -> HttpStatus.NOT_FOUND;
            case VERSION_RETIRED -> HttpStatus.GONE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
