package ricbot.application.runtime;

import ricbot.domain.runtime.RuntimeEventEnvelope;
import ricbot.domain.runtime.RuntimeEventSubscriber;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable at-least-once event delivery; subscriber offset advances only after successful handling. */
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
        Duration interval = pollInterval != null && !pollInterval.isZero() && !pollInterval.isNegative()
                ? pollInterval : Duration.ofMillis(100);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ricbot-event-tailer-" + safeName(this.subscriberId));
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::drainSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void wake() {
        if (!closed.get()) executor.execute(this::drainSafely);
    }

    private void drainSafely() {
        if (closed.get() || !draining.compareAndSet(false, true)) return;
        try {
            while (!closed.get()) {
                long offset = store.subscriberOffset(subscriberId);
                List<RuntimeEventEnvelope> events = store.runtimeEventsAfter(offset, 128);
                if (events.isEmpty()) return;
                for (RuntimeEventEnvelope event : events) {
                    if (closed.get()) return;
                    subscriber.onEvent(event);
                    if (closed.get()) return;
                    store.saveSubscriberOffset(subscriberId, event.globalSequence());
                }
                if (events.size() < 128) return;
            }
        } catch (RuntimeException ignored) {
            // Offset remains unchanged for the failing event and the next poll retries it.
        } finally {
            draining.set(false);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (subscriber instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (Exception ignored) { /* Subscriber cleanup must not fail runtime shutdown. */ }
        }
    }

    private static String safeName(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
