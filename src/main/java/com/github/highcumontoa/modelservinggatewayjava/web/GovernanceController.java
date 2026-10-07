package com.github.highcumontoa.modelservinggatewayjava.web;

import com.github.highcumontoa.modelservinggatewayjava.batch.VersionCapacity;
import com.github.highcumontoa.modelservinggatewayjava.gateway.ScoringGateway;
import com.github.highcumontoa.modelservinggatewayjava.gateway.VersionStatsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 按版本容量治理与运行观测端点。
 * 查询/重置只读或只清计数器，不影响在途请求判定与正常调度。
 */
@RestController
@RequestMapping("/api/v1/versions")
public class GovernanceController {

    private static final Logger log = LoggerFactory.getLogger(GovernanceController.class);

    private final ScoringGateway gateway;

    public GovernanceController(ScoringGateway gateway) {
        this.gateway = gateway;
    }

    /** 全部已观测版本的运行状态。 */
    @GetMapping("/stats")
    public List<VersionStatsSnapshot> allStats() {
        return gateway.allStats();
    }

    /** 单版本运行状态：排队深度、在途数、拒绝/降级计数、健康状态与重试剩余时间。 */
    @GetMapping("/{version}/stats")
    public VersionStatsSnapshot stats(@PathVariable String version) {
        return gateway.statsSnapshot(version);
    }

    /** 重置单版本累计计数（压测用）。 */
    @PostMapping("/{version}/stats/reset")
    public VersionStatsSnapshot resetStats(@PathVariable String version) {
        log.info("resetting stats: version={}", version);
        gateway.resetStats(version);
        return gateway.statsSnapshot(version);
    }

    /** 重置全部版本累计计数。 */
    @PostMapping("/stats/reset")
    public List<VersionStatsSnapshot> resetAllStats() {
        log.info("resetting stats: all versions");
        gateway.resetAllStats();
        return gateway.allStats();
    }

    /** 查询某版本当前生效的承载参数。 */
    @GetMapping("/{version}/capacity")
    public VersionCapacity capacity(@PathVariable String version) {
        return gateway.capacityOf(version);
    }

    /** 运行中调整某版本承载参数：整体替换快照，无需重启，不打断在途请求。 */
    @PutMapping("/{version}/capacity")
    public ResponseEntity<VersionCapacity> updateCapacity(@PathVariable String version,
                                                          @RequestBody VersionCapacity capacity) {
        try {
            gateway.updateCapacity(version, capacity);
        } catch (IllegalArgumentException e) {
            log.info("capacity update rejected: version={} reason={}", version, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(gateway.capacityOf(version));
    }
}
