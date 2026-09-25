package com.github.highcumontoa.modelservinggatewayjava.batch;

import com.github.highcumontoa.modelservinggatewayjava.api.RejectReason;
import com.github.highcumontoa.modelservinggatewayjava.gateway.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量调度器：
 * <ul>
 *   <li>并发请求在有限窗口（batchWindowMs）内合并为批量执行，降低单次调用开销；</li>
 *   <li>队列容量有界，溢出立即以 QUEUE_FULL 拒绝，不无限等待、不静默丢弃；</li>
 *   <li>排队等待超过 maxQueueWaitMs 的请求以 WAIT_TIMEOUT 拒绝；</li>
 *   <li>单批执行超过 executionTimeoutMs 时，未完成项以 EXECUTION_TIMEOUT 异常完成，
 *       由上层决定降级策略。</li>
 * </ul>
 */
public class BatchScheduler<T, R> {

    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

    /** 队列中的单个待处理请求。 */
    public record BatchItem<T, R>(T item, CompletableFuture<R> future, long enqueuedAtNanos) {
    }

    /** 一个已形成的批次。执行器负责完成每个 item 的 future。 */
    public static final class Batch<T, R> {
        private final String id;
        private final List<BatchItem<T, R>> items;

        Batch(String id, List<BatchItem<T, R>> items) {
            this.id = id;
            this.items = items;
        }

        public String id() {
            return id;
        }

        public List<BatchItem<T, R>> items() {
            return items;
        }

        public int size() {
            return items.size();
        }
    }

    /** 批量执行器：对一批请求求值，并完成每个 item 的 future（正常或异常）。 */
    @FunctionalInterface
    public interface BatchExecutor<T, R> {
        void execute(Batch<T, R> batch);
    }

    private final BatchProperties properties;
    private final BatchExecutor<T, R> executor;
    private final LinkedBlockingQueue<BatchItem<T, R>> queue;
    private final AtomicLong batchSeq = new AtomicLong();
    private final Thread dispatcher;
    private volatile boolean running = true;

    public BatchScheduler(BatchProperties properties, BatchExecutor<T, R> executor) {
        this.properties = properties;
        this.executor = executor;
        this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());
        this.dispatcher = Thread.ofVirtual()
                .name("batch-dispatcher")
                .start(this::dispatchLoop);
    }

    /**
     * 提交请求。队列已满时立即以 QUEUE_FULL 异常完成返回的 future，
     * 调用方不会无限阻塞。
     */
    public CompletableFuture<R> submit(T item) {
        CompletableFuture<R> future = new CompletableFuture<>();
        BatchItem<T, R> pending = new BatchItem<>(item, future, System.nanoTime());
        if (!running || !queue.offer(pending)) {
            future.completeExceptionally(
                    new GatewayException(RejectReason.QUEUE_FULL,
                            "queue capacity " + properties.getQueueCapacity() + " exceeded"));
        }
        return future;
    }

    private void dispatchLoop() {
        while (running) {
            try {
                BatchItem<T, R> first = queue.take();
                List<BatchItem<T, R>> items = new ArrayList<>();
                if (!acceptOrReject(first, items)) {
                    continue;
                }
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(properties.getBatchWindowMs());
                while (items.size() < properties.getMaxBatchSize()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    BatchItem<T, R> next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    acceptOrReject(next, items);
                }
                runBatch(items);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 等待超时的请求以 WAIT_TIMEOUT 拒绝，不进入批次。 */
    private boolean acceptOrReject(BatchItem<T, R> pending, List<BatchItem<T, R>> items) {
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.enqueuedAtNanos());
        if (waitedMs > properties.getMaxQueueWaitMs()) {
            pending.future().completeExceptionally(
                    new GatewayException(RejectReason.WAIT_TIMEOUT,
                            "waited " + waitedMs + "ms > limit " + properties.getMaxQueueWaitMs() + "ms"));
            return false;
        }
        items.add(pending);
        return true;
    }

    private void runBatch(List<BatchItem<T, R>> items) {
        String batchId = "batch-" + batchSeq.incrementAndGet();
        Batch<T, R> batch = new Batch<>(batchId, List.copyOf(items));
        log.info("forming batch id={} size={} items={}", batchId, batch.size(),
                items.stream().map(i -> i.item().toString()).toList());
        // 在独立虚拟线程中执行，dispatcher 以超时等待，避免单个慢批次卡死调度
        Thread execThread = Thread.ofVirtual().name(batchId).start(() -> {
            try {
                executor.execute(batch);
            } catch (Throwable t) {
                for (BatchItem<T, R> item : batch.items()) {
                    item.future().completeExceptionally(t);
                }
            }
        });
        try {
            CompletableFuture<?>[] futures = batch.items().stream()
                    .map(BatchItem::future)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures)
                    .get(properties.getExecutionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 先以 EXECUTION_TIMEOUT 完成所有未完成项，再中断执行线程，
            // 避免被中断的模型抢先写入正常结果（complete 只会生效一次）
            for (BatchItem<T, R> item : batch.items()) {
                item.future().completeExceptionally(
                        new GatewayException(RejectReason.EXECUTION_TIMEOUT,
                                "batch " + batchId + " exceeded "
                                        + properties.getExecutionTimeoutMs() + "ms"));
            }
            execThread.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // 个别 item 异常完成属正常路径，由调用方处理
        }
    }

    public void shutdown() {
        running = false;
        dispatcher.interrupt();
    }
}
