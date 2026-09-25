package com.github.highcumontoa.modelservinggatewayjava.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判定审计日志：进程内有界存储，按 requestId 查询；并发安全。
 * 记录每条结论（正常 / 降级 / 拒绝）及其判定依据，供降级原因查询。
 */
@Component
public class DecisionAuditLog {

    private static final Logger log = LoggerFactory.getLogger(DecisionAuditLog.class);
    private static final int CAPACITY = 1024;

    private final ConcurrentHashMap<String, DecisionRecord> byRequestId = new ConcurrentHashMap<>();
    private final ArrayDeque<String> order = new ArrayDeque<>();

    public void record(DecisionRecord record) {
        synchronized (order) {
            byRequestId.put(record.requestId(), record);
            order.addLast(record.requestId());
            while (order.size() > CAPACITY) {
                String evicted = order.removeFirst();
                byRequestId.remove(evicted);
            }
        }
        log.info("DECISION requestId={} version={} batchId={} status={} reasonCode={} basis={}",
                record.requestId(), record.modelVersion(), record.batchId(),
                record.status(), record.reasonCode(), record.basis());
    }

    public Optional<DecisionRecord> get(String requestId) {
        return Optional.ofNullable(byRequestId.get(requestId));
    }

    public List<DecisionRecord> recent() {
        synchronized (order) {
            List<DecisionRecord> records = new ArrayList<>(order.size());
            for (String id : order) {
                DecisionRecord r = byRequestId.get(id);
                if (r != null) {
                    records.add(r);
                }
            }
            return records;
        }
    }
}
