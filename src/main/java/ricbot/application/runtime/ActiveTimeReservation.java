package ricbot.application.runtime;

import ricbot.domain.runtime.DurableRuntimeStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One-second renewable durable active-time reservation around a provider/tool dispatch. */
final class ActiveTimeReservation implements AutoCloseable {
    private static final long QUANTUM_MILLIS = 1_000L;
    private static final ScheduledExecutorService RENEWER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "budget-active-time-renewer");
        thread.setDaemon(true);
        return thread;
    });

    private final DurableRuntimeStore store;
    private final String reservationId;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final ScheduledFuture<?> renewal;
    private boolean closed;

    private ActiveTimeReservation(DurableRuntimeStore store, String runId, String reservationId, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
        store.reserveActiveTime(runId, reservationId, QUANTUM_MILLIS, startedAt);
        this.renewal = RENEWER.scheduleAtFixedRate(() -> {
            if (failure.get() != null) return;
            try { store.reserveActiveTime(runId, reservationId, QUANTUM_MILLIS, clock.instant()); }
            catch (RuntimeException rejected) { failure.compareAndSet(null, rejected); }
        }, QUANTUM_MILLIS, QUANTUM_MILLIS, TimeUnit.MILLISECONDS);
    }

    static ActiveTimeReservation start(DurableRuntimeStore store, String runId,
                                       String reservationId, Clock clock) {
        return new ActiveTimeReservation(store, runId, reservationId, clock);
    }

    RuntimeException failure() { return failure.get(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        renewal.cancel(false);
        long elapsed;
        try { elapsed = Math.max(0L, Duration.between(startedAt, clock.instant()).toMillis()); }
        catch (RuntimeException ignored) { elapsed = 0L; }
        store.settleActiveTime(reservationId, elapsed, clock.instant());
    }
}
