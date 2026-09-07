package ricbot.application.runtime;

import ricbot.domain.runtime.*;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable exactly-once-oriented boundary for externally visible write effects. */
public final class EffectRuntime {
    private static final ScheduledExecutorService LEASE_RENEWER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "effect-resource-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });
    private final DurableRuntimeStore store;
    private final ToolEffectPort tools;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final CrashInjector crashes;

    public EffectRuntime(DurableRuntimeStore store, ToolEffectPort tools, Clock clock,
                         String owner, Duration leaseDuration) {
        this(store, tools, clock, owner, leaseDuration, CrashInjector.NONE);
    }

    public EffectRuntime(DurableRuntimeStore store, ToolEffectPort tools, Clock clock,
                         String owner, Duration leaseDuration, CrashInjector crashes) {
        this.store = Objects.requireNonNull(store, "store");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.leaseDuration = leaseDuration != null ? leaseDuration : Duration.ofSeconds(30);
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
    }

    public Outcome execute(EffectIntent intent) {
        EffectRecord existing = store.effect(intent.effectId()).orElse(null);
        if (existing != null && existing.status() == EffectRecord.Status.SUCCEEDED) return Outcome.completed(existing);
        if (existing != null && existing.status() == EffectRecord.Status.FAILED) return Outcome.completed(existing);
        if (existing != null && existing.status() == EffectRecord.Status.DISPATCHING) {
            existing = store.saveEffect(copy(existing, EffectRecord.Status.UNKNOWN,
                    existing.executionEvidence(), existing.resultReference(),
                    "dispatch outcome was not durably observed"));
        }
        if (existing != null && existing.status() == EffectRecord.Status.UNKNOWN) {
            Optional<EffectExecution> reconciled;
            try {
                reconciled = tools.reconcile(intent, existing.executionEvidence());
            } catch (RuntimeException unavailable) {
                reconciled = Optional.empty();
            }
            if (reconciled.isPresent()) return Outcome.completed(succeeded(existing, reconciled.get()));
            return Outcome.confirmationRequired(existing);
        }
        if (existing == null) {
            existing = store.saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED, 1, -1,
                    Map.of(), "", "", clock.instant()));
        }
        if (!store.acquireResources(intent.effectId(), owner, intent.resourceClaims(), clock.instant(), leaseDuration)) {
            return Outcome.retryRequired(existing);
        }
        crashes.at(CrashInjector.Point.RESOURCE_LEASE_ACQUIRED,
                Map.of("effectId", intent.effectId(), "resources", intent.resourceClaims()));
        AtomicBoolean leaseLost = new AtomicBoolean();
        long renewalMillis = Math.max(10L, leaseDuration.toMillis() / 3L);
        ScheduledFuture<?> renewal = LEASE_RENEWER.scheduleAtFixedRate(() -> {
            try {
                if (!store.renewResources(intent.effectId(), owner, intent.resourceClaims(),
                        clock.instant(), leaseDuration)) leaseLost.set(true);
            } catch (RuntimeException failure) {
                leaseLost.set(true);
            }
        }, renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
        try {
            EffectRecord dispatching = store.saveEffect(copy(existing, EffectRecord.Status.DISPATCHING, Map.of(), "", ""));
            try {
                crashes.at(CrashInjector.Point.EFFECT_BEFORE_DISPATCH,
                        Map.of("effectId", intent.effectId()));
                EffectExecution execution = tools.dispatch(intent);
                crashes.at(CrashInjector.Point.EFFECT_AFTER_DISPATCH,
                        Map.of("effectId", intent.effectId(), "resultReference", execution.resultReference()));
                if (leaseLost.get()) {
                    EffectRecord unknown = store.saveEffect(copy(dispatching, EffectRecord.Status.UNKNOWN,
                            execution.evidence(), execution.resultReference(), "resource lease was lost during dispatch"));
                    return Outcome.confirmationRequired(unknown);
                }
                return Outcome.completed(succeeded(dispatching, execution));
            } catch (UncertainEffectException uncertain) {
                EffectRecord unknown = store.saveEffect(copy(dispatching, EffectRecord.Status.UNKNOWN,
                        uncertain.evidence(), "", uncertain.getMessage()));
                return Outcome.confirmationRequired(unknown);
            } catch (DefinitiveEffectException definitive) {
                EffectRecord failed = store.saveEffect(copy(dispatching, EffectRecord.Status.FAILED,
                        definitive.evidence(), "", message(definitive)));
                return Outcome.completed(failed);
            } catch (RuntimeException failure) {
                // Once write dispatch has begun, an unclassified exception cannot prove that the
                // external mutation did not occur. Fail closed as UNKNOWN and require reconcile or
                // explicit confirmation. Ports may use DefinitiveEffectException only when they can
                // prove that no externally visible write happened.
                EffectRecord unknown = store.saveEffect(copy(dispatching, EffectRecord.Status.UNKNOWN,
                        failureEvidence(failure), "", message(failure)));
                return Outcome.confirmationRequired(unknown);
            }
        } finally {
            renewal.cancel(false);
            store.releaseResources(intent.effectId(), owner);
        }
    }

    private EffectRecord succeeded(EffectRecord current, EffectExecution result) {
        return store.saveEffect(copy(current, EffectRecord.Status.SUCCEEDED,
                result.evidence(), result.resultReference(), ""));
    }
    private EffectRecord copy(EffectRecord value, EffectRecord.Status status, Map<String, Object> evidence,
                              String resultReference, String failure) {
        return new EffectRecord(value.intent(), status, value.attempt(), value.committedSuperstep(),
                evidence, resultReference, failure, clock.instant());
    }
    private static String message(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
    private static Map<String, Object> failureEvidence(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ricbot.infra.execution.ExecutionInterruptedException interrupted) {
                java.util.LinkedHashMap<String, Object> evidence = new java.util.LinkedHashMap<>(interrupted.evidence());
                evidence.put("exceptionType", failure.getClass().getName());
                return Map.copyOf(evidence);
            }
            current = current.getCause();
        }
        return Map.of("exceptionType", failure.getClass().getName());
    }

    public record EffectExecution(String resultReference, Map<String, Object> evidence) {
        public EffectExecution { resultReference = resultReference != null ? resultReference : ""; evidence = Map.copyOf(evidence != null ? evidence : Map.of()); }
    }
    public record Outcome(EffectRecord record, boolean requiresConfirmation, boolean retryRequired) {
        static Outcome completed(EffectRecord record) { return new Outcome(record, false, false); }
        static Outcome confirmationRequired(EffectRecord record) { return new Outcome(record, true, false); }
        static Outcome retryRequired(EffectRecord record) { return new Outcome(record, false, true); }
    }
    public interface ToolEffectPort {
        EffectExecution dispatch(EffectIntent intent);
        default Optional<EffectExecution> reconcile(EffectIntent intent, Map<String, Object> evidence) { return Optional.empty(); }
        default void cancel(EffectIntent intent) { }
    }
    public static final class UncertainEffectException extends RuntimeException {
        private final Map<String, Object> evidence;
        public UncertainEffectException(String message, Map<String, Object> evidence, Throwable cause) {
            super(message, cause); this.evidence = Map.copyOf(evidence != null ? evidence : Map.of());
        }
        public Map<String, Object> evidence() { return evidence; }
    }
    public static final class DefinitiveEffectException extends RuntimeException {
        private final Map<String, Object> evidence;
        public DefinitiveEffectException(String message, Map<String, Object> evidence, Throwable cause) {
            super(message, cause); this.evidence = Map.copyOf(evidence != null ? evidence : Map.of());
        }
        public Map<String, Object> evidence() { return evidence; }
    }
}
