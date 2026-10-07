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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 批量调度器（按版本一条车道，互不干扰）：
 * <ul>
 *   <li>并发请求在有限窗口内合并为批量执行，降低单次调用开销；</li>
 *   <li>承载参数来自 {@link Supplier} 快照：每次提交、每批形成各读取一次，
 *       同一批判定看到同一套参数；运行中替换快照即生效，无需重启；</li>
 *   <li>队列容量有界（按当前快照判定），溢出立即以 QUEUE_FULL 拒绝；</li>
 *   <li>排队等待超过 maxQueueWaitMs 的请求以 WAIT_TIMEOUT 拒绝；</li>
 *   <li>单批执行超过 executionTimeoutMs 时，未完成项以 EXECUTION_TIMEOUT 异常完成。</li>
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

    private final String name;
    private final Supplier<VersionCapacity> capacity;
    private final BatchExecutor<T, R> executor;
    private final LinkedBlockingQueue<BatchItem<T, R>> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong batchSeq = new AtomicLong();
    private final Thread dispatcher;
    private volatile boolean running = true;

    public BatchScheduler(String name, Supplier<VersionCapacity> capacity,
                          BatchExecutor<T, R> executor) {
        this.name = name;
        this.capacity = capacity;
        this.executor = executor;
        this.dispatcher = Thread.ofVirtual()
                .name(name + "-dispatcher")
                .start(this::dispatchLoop);
    }

    /**
     * 提交请求。按提交时的容量快照判定，队列已满立即以 QUEUE_FULL
     * 异常完成返回的 future，调用方不会无限阻塞。
     */
    public CompletableFuture<R> submit(T item) {
        CompletableFuture<R> future = new CompletableFuture<>();
        VersionCapacity cap = capacity.get();
        int depth = queueDepth.incrementAndGet();
        if (!running || depth > cap.queueCapacity()) {
            queueDepth.decrementAndGet();
            future.completeExceptionally(
                    new GatewayException(RejectReason.QUEUE_FULL,
                            "lane " + name + " queue capacity " + cap.queueCapacity()
                                    + " exceeded"));
            return future;
        }
        queue.offer(new BatchItem<>(item, future, System.nanoTime()));
        return future;
    }

    /** 当前排队深度（gauge，观测用）。 */
    public int queueDepth() {
        return queueDepth.get();
    }

    /** 当前正在执行的请求数（gauge，观测用）。 */
    public int inFlight() {
        return inFlight.get();
    }

    private void dispatchLoop() {
        while (running) {
            try {
                BatchItem<T, R> first = queue.take();
                queueDepth.decrementAndGet();
                // 每批形成时读取一次容量快照：本批的窗口/批量/等待/超时判定
                // 全部使用同一套参数，不受运行中调整影响
                VersionCapacity cap = capacity.get();
                List<BatchItem<T, R>> items = new ArrayList<>();
                if (!acceptOrReject(first, items, cap)) {
                    continue;
                }
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(cap.batchWindowMs());
                while (items.size() < cap.maxBatchSize()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    BatchItem<T, R> next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    queueDepth.decrementAndGet();
                    acceptOrReject(next, items, cap);
                }
                runBatch(items, cap);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 等待超时的请求以 WAIT_TIMEOUT 拒绝，不进入批次。 */
    private boolean acceptOrReject(BatchItem<T, R> pending, List<BatchItem<T, R>> items,
                                   VersionCapacity cap) {
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.enqueuedAtNanos());
        if (waitedMs > cap.maxQueueWaitMs()) {
            pending.future().completeExceptionally(
                    new GatewayException(RejectReason.WAIT_TIMEOUT,
                            "lane " + name + " waited " + waitedMs + "ms > limit "
                                    + cap.maxQueueWaitMs() + "ms"));
            return false;
        }
        items.add(pending);
        return true;
    }

    private void runBatch(List<BatchItem<T, R>> items, VersionCapacity cap) {
        String batchId = name + "-batch-" + batchSeq.incrementAndGet();
        Batch<T, R> batch = new Batch<>(batchId, List.copyOf(items));
        inFlight.addAndGet(items.size());
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
                    .get(cap.executionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 先以 EXECUTION_TIMEOUT 完成所有未完成项，再中断执行线程，
            // 避免被中断的模型抢先写入正常结果（complete 只会生效一次）
            for (BatchItem<T, R> item : batch.items()) {
                item.future().completeExceptionally(
                        new GatewayException(RejectReason.EXECUTION_TIMEOUT,
                                "batch " + batchId + " exceeded "
                                        + cap.executionTimeoutMs() + "ms"));
            }
            execThread.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // 个别 item 异常完成属正常路径，由调用方处理
        } finally {
            inFlight.addAndGet(-items.size());
        }
    }

    public void shutdown() {
        running = false;
        dispatcher.interrupt();
    }
}
