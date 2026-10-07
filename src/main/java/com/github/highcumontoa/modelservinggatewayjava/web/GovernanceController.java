package com.github.highcumontoa.modelservinggatewayjava.web;

import com.github.highcumontoa.modelservinggatewayjava.governance.GovernorRegistry;
import com.github.highcumontoa.modelservinggatewayjava.governance.VersionCapacity;
import com.github.highcumontoa.modelservinggatewayjava.governance.VersionGovernor;
import com.github.highcumontoa.modelservinggatewayjava.governance.VersionStats;
import org.springframework.http.HttpStatus;
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
 * 查询与重置为只读/计数操作，不干扰正常调度，
 * 也不改变任何已有请求的判定结果。
 */
@RestController
@RequestMapping("/api/v1/versions")
public class GovernanceController {

    private final GovernorRegistry governors;

    public GovernanceController(GovernorRegistry governors) {
        this.governors = governors;
    }

    /** 全部已治理版本的运行状态。 */
    @GetMapping("/stats")
    public List<VersionStats> allStats() {
        return governors.allStats();
    }

    /** 单版本运行状态：排队深度、在途数、分类计数、健康状态与重试剩余时间。 */
    @GetMapping("/{version}/stats")
    public ResponseEntity<VersionStats> stats(@PathVariable String version) {
        VersionGovernor governor = governors.find(version);
        if (governor == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(governor.stats());
    }

    /** 重置单版本计数并恢复健康（压测场景）；不影响在途请求的判定。 */
    @PostMapping("/{version}/stats/reset")
    public ResponseEntity<VersionStats> resetStats(@PathVariable String version) {
        VersionGovernor governor = governors.find(version);
        if (governor == null) {
            return ResponseEntity.notFound().build();
        }
        governor.resetStats();
        return ResponseEntity.ok(governor.stats());
    }

    /** 查询单版本当前生效的承载参数。 */
    @GetMapping("/{version}/capacity")
    public ResponseEntity<VersionCapacity> capacity(@PathVariable String version) {
        VersionGovernor governor = governors.find(version);
        if (governor == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(governor.capacity());
    }

    /**
     * 运行中调整单版本承载参数：整体替换快照，无需重启；
     * 新批次立即看到新参数，在途批次仍按原参数完成。
     */
    @PutMapping("/{version}/capacity")
    public ResponseEntity<VersionCapacity> updateCapacity(@PathVariable String version,
                                                          @RequestBody VersionCapacity capacity) {
        if (!valid(capacity)) {
            return ResponseEntity.badRequest().build();
        }
        if (!governors.updateCapacity(version, capacity)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(capacity);
    }

    private static boolean valid(VersionCapacity c) {
        return c != null
                && c.maxBatchSize() > 0
                && c.batchWindowMs() > 0
                && c.queueCapacity() > 0
                && c.maxQueueWaitMs() > 0
                && c.executionTimeoutMs() > 0
                && c.failureThreshold() > 0
                && c.recoveryMs() > 0;
    }
}
