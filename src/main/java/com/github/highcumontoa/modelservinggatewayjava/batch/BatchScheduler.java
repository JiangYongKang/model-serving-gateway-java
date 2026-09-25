package com.github.highcumontoa.modelservinggatewayjava.batch;

import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ReasonCode;
import com.github.highcumontoa.modelservinggatewayjava.protocol.ScoreRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单版本批量调度器。
 *
 * <p>过载控制（两道明确的拒绝边界，均不静默丢弃）：
 * <ul>
 *   <li>提交时排队深度达到上限 → 立即以 {@link ReasonCode#QUEUE_FULL} 拒绝；</li>
 *   <li>入队后排队时间超过上限 → 以 {@link ReasonCode#WAIT_TIMEOUT} 拒绝。</li>
 * </ul>
 *
 * <p>合批规则：并发到达的请求在 {@code batchWindowMs} 窗口内、或凑满 {@code maxBatchSize} 时合并执行。
 *
 * <p>执行保护：单次模型调用超过 {@code modelTimeoutMs} → {@link ReasonCode#MODEL_TIMEOUT}；
 * 模型抛异常 → {@link ReasonCode#MODEL_ERROR}；返回值缺失/长度不符/NaN → {@link ReasonCode#RESULT_INVALID}。
 */
public class BatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

    private final LocalModel model;
    private final int maxBatchSize;
    private final long batchWindowMs;
    private final int queueDepth;
    private final long maxQueueWaitMs;
    private final long modelTimeoutMs;

    private final ScheduledExecutorService timer;
    private final ScheduledExecutorService modelExecutor;
    private final AtomicLong batchSeq = new AtomicLong();

    private final Object lock = new Object();
    private final List<Entry> pending = new ArrayList<>();
    private boolean windowScheduled = false;
    private volatile boolean running = true;
    /** 已提交给执行线程但未完成的批次数；>0 时不再出队，保证排队上限真实约束等待。 */
    private final java.util.concurrent.atomic.AtomicInteger inflight =
            new java.util.concurrent.atomic.AtomicInteger();

    public BatchScheduler(LocalModel model, int maxBatchSize, long batchWindowMs,
                          int queueDepth, long maxQueueWaitMs, long modelTimeoutMs) {
        this.model = model;
        this.maxBatchSize = maxBatchSize;
        this.batchWindowMs = batchWindowMs;
        this.queueDepth = queueDepth;
        this.maxQueueWaitMs = maxQueueWaitMs;
        this.modelTimeoutMs = modelTimeoutMs;
        String base = "batch-" + model.version() + "-";
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, base + "timer");
            t.setDaemon(true);
            return t;
        });
        this.modelExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, base + "worker");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<BatchOutcome> submit(ScoreRequest request) {
        CompletableFuture<BatchOutcome> future = new CompletableFuture<>();
        Entry entry = new Entry(request, future, System.nanoTime());
        boolean triggerNow = false;
        synchronized (lock) {
            if (!running) {
                future.completeExceptionally(new SchedulerFailure(ReasonCode.MODEL_ERROR,
                        "scheduler for version " + model.version() + " is closed"));
                return future;
            }
            if (pending.size() >= queueDepth) {
                log.warn("REJECT requestId={} version={} reason=QUEUE_FULL queueDepth={} pending={}",
                        request.requestId(), model.version(), queueDepth, pending.size());
                future.completeExceptionally(new SchedulerFailure(ReasonCode.QUEUE_FULL,
                        "queue depth limit " + queueDepth + " reached for version " + model.version()));
                return future;
            }
            pending.add(entry);
            if (!windowScheduled) {
                windowScheduled = true;
                timer.schedule(this::flushWindow, batchWindowMs, TimeUnit.MILLISECONDS);
            }
            if (pending.size() >= maxBatchSize) {
                triggerNow = true;
            }
        }
        // 排队等待上限：到期仍未出队则明确拒绝
        timer.schedule(() -> expireWait(entry), maxQueueWaitMs, TimeUnit.MILLISECONDS);
        if (triggerNow) {
            timer.execute(this::flushWindow);
        }
        log.info("ENQUEUE requestId={} version={} contract={} inputs={} pending={}",
                request.requestId(), request.modelVersion(), request.contractVersion(),
                request.inputs(), pendingSize());
        return future;
    }

    private int pendingSize() {
        synchronized (lock) {
            return pending.size();
        }
    }

    private void expireWait(Entry entry) {
        boolean removed;
        synchronized (lock) {
            removed = pending.remove(entry);
        }
        if (removed) {
            long waited = (System.nanoTime() - entry.enqueueNanos) / 1_000_000;
            log.warn("REJECT requestId={} version={} reason=WAIT_TIMEOUT waitedMs={} limitMs={}",
                    entry.request.requestId(), model.version(), waited, maxQueueWaitMs);
            entry.future.completeExceptionally(new SchedulerFailure(ReasonCode.WAIT_TIMEOUT,
                    "queue wait " + waited + "ms exceeded limit " + maxQueueWaitMs
                            + "ms for version " + model.version()));
        }
    }

    private void flushWindow() {
        List<Entry> drained;
        synchronized (lock) {
            windowScheduled = false;
            if (pending.isEmpty()) {
                return;
            }
            if (inflight.get() > 0) {
                // 执行线程仍忙：保持排队，让 queueWaitMs 上限生效，稍后重试
                windowScheduled = true;
                timer.schedule(this::flushWindow, batchWindowMs, TimeUnit.MILLISECONDS);
                return;
            }
            int n = Math.min(maxBatchSize, pending.size());
            drained = new ArrayList<>(pending.subList(0, n));
            pending.subList(0, n).clear();
            // 剩余请求重新开启一个合批窗口
            if (!pending.isEmpty() && !windowScheduled) {
                windowScheduled = true;
                timer.schedule(this::flushWindow, batchWindowMs, TimeUnit.MILLISECONDS);
            }
        }
        runBatch(drained);
    }

    private void runBatch(List<Entry> batch) {
        String batchId = model.version() + "-" + batchSeq.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = batch.stream().map(e -> e.request.requestId()).toList();
        List<java.util.Map<String, Object>> inputs =
                batch.stream().map(e -> e.request.inputs()).toList();
        inflight.incrementAndGet();
        log.info("BATCH_GROUP batchId={} version={} size={} requests={}",
                batchId, model.version(), batch.size(), ids);

        CompletableFuture<List<Double>> result = new CompletableFuture<>();
        java.util.concurrent.Future<?> task = modelExecutor.submit(() -> {
            try {
                List<Double> scores = model.scoreBatch(inputs);
                result.complete(scores);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        timer.schedule(() -> {
            // 先落定 MODEL_TIMEOUT 结论，再中断工作线程，保证原因码不被中断异常抢占
            result.completeExceptionally(new SchedulerFailure(ReasonCode.MODEL_TIMEOUT,
                    "model " + model.version() + " execution exceeded " + modelTimeoutMs + "ms",
                    batchId));
            task.cancel(true);
        }, modelTimeoutMs, TimeUnit.MILLISECONDS);

        result.whenComplete((scores, err) -> {
            task.cancel(true);
            inflight.decrementAndGet();
            // 执行槽位空出：立即尝试排空仍在排队的请求
            timer.execute(this::flushWindow);
            if (err != null) {
                SchedulerFailure failure = unwrap(err);
                log.warn("BATCH_FAIL batchId={} version={} reason={} detail={} requests={}",
                        batchId, model.version(), failure.reasonCode(),
                        String.valueOf(failure.getMessage()), ids);
                for (Entry e : batch) {
                    e.future.completeExceptionally(new SchedulerFailure(failure.reasonCode(),
                            failure.getMessage() + " (batchId=" + batchId + ")", batchId));
                }
                return;
            }
            if (!isValid(scores, batch.size())) {
                log.warn("BATCH_FAIL batchId={} version={} reason=RESULT_INVALID scores={} requests={}",
                        batchId, model.version(), scores, ids);
                for (Entry e : batch) {
                    e.future.completeExceptionally(new SchedulerFailure(ReasonCode.RESULT_INVALID,
                            "model " + model.version() + " returned unusable result", batchId));
                }
                return;
            }
            log.info("BATCH_OK batchId={} version={} requests={} scores={}",
                    batchId, model.version(), ids, scores);
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).future.complete(new BatchOutcome(scores.get(i), batchId));
            }
        });
    }

    private static boolean isValid(List<Double> scores, int expected) {
        if (scores == null || scores.size() != expected) {
            return false;
        }
        for (Double s : scores) {
            if (s == null || s.isNaN() || s.isInfinite()) {
                return false;
            }
        }
        return true;
    }

    private static SchedulerFailure unwrap(Throwable err) {
        Throwable cur = err;
        while (cur != null) {
            if (cur instanceof SchedulerFailure sf) {
                return sf;
            }
            cur = cur.getCause();
        }
        return new SchedulerFailure(ReasonCode.MODEL_ERROR,
                "model execution error: " + err);
    }

    public void close() {
        running = false;
        timer.shutdownNow();
        modelExecutor.shutdownNow();
    }

    private record Entry(ScoreRequest request, CompletableFuture<BatchOutcome> future, long enqueueNanos) {
    }
}
