package ricbot.application.runtime;

import ricbot.domain.runtime.dto.RuntimeEventEnvelope;
import ricbot.domain.runtime.RuntimeEventSubscriber;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 
 * 持久化至少一次事件投递；订阅者偏移量仅在成功处理后才前进。
 */
public final class RuntimeEventTailer implements AutoCloseable {
    private final SqliteRuntimeStore store;
    private final String subscriberId;
    private final RuntimeEventSubscriber subscriber;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RuntimeEventTailer(SqliteRuntimeStore store, String subscriberId,
                              RuntimeEventSubscriber subscriber, Duration pollInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.subscriberId = required(subscriberId, "subscriberId");
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        // 使用默认轮询间隔，如果提供的间隔无效
        Duration interval = pollInterval != null && !pollInterval.isZero() && !pollInterval.isNegative()
                ? pollInterval : Duration.ofMillis(100);
        
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ricbot-event-tailer-" + safeName(this.subscriberId));
            thread.setDaemon(true);
            return thread;
        });
        // 立即启动并定期执行 drainSafely 方法
        executor.scheduleWithFixedDelay(this::drainSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 唤醒尾随器以立即处理积压的事件。
     */
    public void wake() {
        if (!closed.get()) {
            executor.execute(this::drainSafely);
        }
    }

    /**
     * 安全地 drained 事件队列。
     * 如果当前正在 draining 或已关闭，则直接返回。
     * 循环读取事件并逐个处理，直到没有更多事件或达到批次上限。
     * 发生异常时，偏移量保持不变，下次重试。
     */
    private void drainSafely() {
        // 防止并发重复执行
        if (closed.get() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            while (!closed.get()) {
                long offset = store.subscriberOffset(subscriberId);
                List<RuntimeEventEnvelope> events = store.runtimeEventsAfter(offset, 128);
                
                if (events.isEmpty()) {
                    return;
                }
                
                for (RuntimeEventEnvelope event : events) {
                    if (closed.get()) {
                        return;
                    }
                    subscriber.onEvent(event);
                    
                    if (closed.get()) {
                        return;
                    }
                    // 只有处理成功后才更新偏移量
                    store.saveSubscriberOffset(subscriberId, event.globalSequence());
                }
                
                // 如果本次获取的事件少于批次大小，说明已无更多事件，退出循环
                if (events.size() < 128) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            // 捕获运行时异常，保持偏移量不变以便下次重试失败的事件
            // 日志记录在实际应用中可能需要添加
        } finally {
            // 重置 draining 标志，允许下一次调度执行
            draining.set(false);
        }
    }

    @Override 
    public void close() {
        // 确保只关闭一次
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        
        // 清理订阅者资源（如果实现了 AutoCloseable）
        if (subscriber instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 清理失败不应影响运行时关闭流程
            }
        }
    }

    /**
     * 将字符串转换为安全的线程名称格式。
     */
    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 验证并清理必填字段。
     */
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }
}
