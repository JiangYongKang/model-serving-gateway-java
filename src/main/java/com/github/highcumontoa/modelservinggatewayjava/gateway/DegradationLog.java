package com.github.highcumontoa.modelservinggatewayjava.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 降级事件日志：每次降级都记录原因与上下文，
 * 可通过 /api/v1/degradations 查询，保证降级原因可追溯。
 */
@Component
public class DegradationLog {

    private static final Logger log = LoggerFactory.getLogger(DegradationLog.class);

    private final List<DegradationRecord> records = new CopyOnWriteArrayList<>();

    public void record(DegradationRecord record) {
        log.warn("degradation recorded: requestId={} version={} reason={} detail={}",
                record.requestId(), record.modelVersion(), record.reason(), record.detail());
        records.add(record);
    }

    public List<DegradationRecord> all() {
        return List.copyOf(records);
    }
}
