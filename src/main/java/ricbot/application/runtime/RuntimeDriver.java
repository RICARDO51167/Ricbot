package ricbot.application.runtime;

import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;

import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import ricbot.infra.runtime.SqliteRuntimeStore;

/** Deliberately policy-free driver: execute READY activations and stop at a wait or terminal state. */
public final class RuntimeDriver implements AutoCloseable {
    private static final Duration ACTIVATION_LEASE = Duration.ofSeconds(30);
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition dueOrSignal = wakeLock.newCondition();
    private final SqliteRuntimeStore store;
    private final String instanceId;
    private final ScheduledExecutorService heartbeats;

    public RuntimeDriver() { this(null, ""); }

    public RuntimeDriver(SqliteRuntimeStore store, String instanceId) {
        this.store = store;
        this.instanceId = instanceId != null ? instanceId.trim() : "";
        this.heartbeats = store == null ? null : Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ricbot-activation-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public GraphExecutionState drive(AgentGraphRuntime runtime) {
        while (true) {
            if (runtime.state().status() == GraphExecutionStatus.READY) {
                String runId = runtime.state().runId();
                if (!claim(runId)) return runtime.state();
                ScheduledFuture<?> renewal = startRenewal(runId);
                try {
                    runtime.executeOne(Map.of());
                } finally {
                    if (renewal != null) renewal.cancel(false);
                    release(runId);
                }
                continue;
            }
            if (runtime.state().status() != GraphExecutionStatus.RETRY_WAIT) break;
            Instant due = runtime.nextRetryAt().orElseThrow(() ->
                    new IllegalStateException("retry-wait run has no persisted due time"));
            awaitUntil(due);
            runtime.activateDueRetries(Instant.now());
        }
        return runtime.state();
    }

    private boolean claim(String runId) {
        return store == null || store.claimReadyActivations(runId, instanceId, Instant.now(),
                Instant.now().plus(ACTIVATION_LEASE));
    }

    private ScheduledFuture<?> startRenewal(String runId) {
        if (heartbeats == null) return null;
        return heartbeats.scheduleAtFixedRate(() -> {
            try { store.renewActivationClaims(runId, instanceId, Instant.now().plus(ACTIVATION_LEASE)); }
            catch (RuntimeException ignored) { /* Completion or ownership loss is decided by the DB CAS. */ }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void release(String runId) {
        if (store != null) store.releaseActivationClaims(runId, instanceId);
    }

    public void wake() {
        wakeLock.lock();
        try { dueOrSignal.signalAll(); }
        finally { wakeLock.unlock(); }
    }

    private void awaitUntil(Instant due) {
        wakeLock.lock();
        try {
            while (Instant.now().isBefore(due)) {
                long nanos = Math.max(1L, Duration.between(Instant.now(), due).toNanos());
                try { dueOrSignal.awaitNanos(nanos); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("runtime driver interrupted while waiting for durable retry",
                            interrupted);
                }
            }
        } finally {
            wakeLock.unlock();
        }
    }

    @Override public void close() {
        wake();
        if (heartbeats == null) return;
        heartbeats.shutdownNow();
        try {
            heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
