package ricbot.application.runtime;

import ricbot.domain.runtime.RuntimeInstanceRecord;
import ricbot.domain.runtime.RuntimeInstanceStatus;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit process lifecycle. Store construction intentionally performs no crash recovery. */
public final class RuntimeLifecycleManager implements AutoCloseable {
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    private final SqliteRuntimeStore store;
    private final Clock clock;
    private final Duration heartbeatInterval;
    private final Duration leaseDuration;
    private final String hostId;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile RuntimeInstanceRecord instance;

    public RuntimeLifecycleManager(SqliteRuntimeStore store) {
        this(store, Clock.systemUTC(), DEFAULT_HEARTBEAT, DEFAULT_LEASE, localHostId());
    }

    RuntimeLifecycleManager(SqliteRuntimeStore store, Clock clock, Duration heartbeatInterval,
                            Duration leaseDuration, String hostId) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.hostId = required(hostId, "hostId");
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ricbot-runtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized RuntimeInstanceRecord start() {
        if (!started.compareAndSet(false, true)) return instance;
        Instant now = clock.instant();
        ProcessHandle process = ProcessHandle.current();
        Instant processStart = process.info().startInstant().orElse(now);
        instance = store.registerRuntimeInstance(new RuntimeInstanceRecord(UUID.randomUUID().toString(), hostId,
                process.pid(), processStart, now, now.plus(leaseDuration), RuntimeInstanceStatus.ACTIVE, 0));
        recoverDeadOwners(now);
        heartbeats.scheduleAtFixedRate(this::heartbeatSafely, heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        return instance;
    }

    public RuntimeInstanceRecord instance() {
        RuntimeInstanceRecord current = instance;
        if (current == null) throw new IllegalStateException("runtime lifecycle has not started");
        return current;
    }

    public int recoverDeadOwners() { return recoverDeadOwners(clock.instant()); }

    private int recoverDeadOwners(Instant now) {
        int recovered = 0;
        for (RuntimeInstanceRecord candidate : store.expiredRuntimeInstances(now)) {
            if (processStillMatches(candidate)) continue;
            RuntimeInstanceRecord expired;
            try {
                expired = store.saveRuntimeInstance(candidate.expire(now), candidate.version(),
                        RuntimeInstanceStatus.ACTIVE);
            } catch (IllegalStateException raced) {
                continue;
            }
            recovered += store.recoverExpiredSideEffects(expired.instanceId(), now);
        }
        return recovered;
    }

    private boolean processStillMatches(RuntimeInstanceRecord candidate) {
        if (!hostId.equals(candidate.hostId())) return false;
        return ProcessHandle.of(candidate.pid()).filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(start -> start.equals(candidate.processStartedAt()))
                .orElse(false);
    }

    private synchronized void heartbeatSafely() {
        if (!started.get()) return;
        RuntimeInstanceRecord current = instance;
        if (current == null || current.status() != RuntimeInstanceStatus.ACTIVE) return;
        Instant now = clock.instant();
        try {
            instance = store.saveRuntimeInstance(current.heartbeat(now, now.plus(leaseDuration)), current.version(),
                    RuntimeInstanceStatus.ACTIVE);
            recoverDeadOwners(now);
        } catch (RuntimeException ignored) {
            // A missed heartbeat is represented by the durable expiry. The dispatcher will stop on lease loss.
        }
    }

    @Override public void close() {
        if (!started.compareAndSet(true, false)) return;
        heartbeats.shutdownNow();
        try {
            heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            RuntimeInstanceRecord current = instance;
            if (current != null && current.status() == RuntimeInstanceStatus.ACTIVE) {
                try {
                    instance = store.saveRuntimeInstance(current.close(clock.instant()), current.version(),
                            RuntimeInstanceStatus.ACTIVE);
                } catch (RuntimeException ignored) {
                    // Lease expiry remains the crash-recovery authority if graceful close cannot commit.
                }
            }
        }
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String localHostId() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { return "localhost"; }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
