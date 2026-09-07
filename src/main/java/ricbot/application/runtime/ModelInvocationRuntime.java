package ricbot.application.runtime;

import ricbot.domain.runtime.DurableRuntimeStore;
import ricbot.domain.runtime.ModelInvocation;
import ricbot.domain.runtime.dto.RuntimeDigest;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Crash-aware model boundary backed by the independent model invocation ledger. */
public final class ModelInvocationRuntime {
    private final DurableRuntimeStore store;
    private final ModelProviderPort provider;
    private final Clock clock;
    private final ricbot.domain.runtime.CrashInjector crashes;
    private final ConcurrentHashMap<String, ActiveCall> activeCalls = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();

    public ModelInvocationRuntime(DurableRuntimeStore store, ModelProviderPort provider, Clock clock) {
        this(store, provider, clock, ricbot.domain.runtime.CrashInjector.NONE);
    }

    public ModelInvocationRuntime(DurableRuntimeStore store, ModelProviderPort provider, Clock clock,
                                  ricbot.domain.runtime.CrashInjector crashes) {
        this.store = Objects.requireNonNull(store, "store");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.crashes = crashes != null ? crashes : ricbot.domain.runtime.CrashInjector.NONE;
    }

    public ModelInvocation invoke(ModelCall call) {
        ModelInvocation existing = store.modelInvocation(call.invocationId()).orElse(null);
        if (existing != null) {
            if (existing.status() == ModelInvocation.Status.OBSERVED
                    || existing.status() == ModelInvocation.Status.FAILED) return existing;
            if (existing.status() == ModelInvocation.Status.DISPATCHING) {
                existing = store.saveModelInvocation(new ModelInvocation(existing.invocationId(), existing.runId(),
                        existing.activationId(), existing.attempt(), ModelInvocation.Status.UNKNOWN,
                        existing.requestDigest(), existing.reservedTokens(), existing.unknownPolicy(),
                        existing.providerRequestId(), existing.responseReference(), existing.responseDigest(),
                        existing.response(), true, false, 0,
                        "dispatch outcome was not durably observed", clock.instant()));
            }
            if (existing.status() == ModelInvocation.Status.UNKNOWN) {
                Optional<ModelObservation> reconciled;
                try {
                    reconciled = provider.reconcile(existing.providerRequestId());
                } catch (RuntimeException unavailable) {
                    reconciled = Optional.empty();
                }
                if (reconciled.isPresent()) return observed(existing, reconciled.get());
                return existing;
            }
        }
        if (existing == null) {
            String providerRequestId = clean(provider.prepareRequestId(call));
            if (providerRequestId.isBlank()) providerRequestId = "request:" + call.invocationId();
            existing = store.saveModelInvocation(new ModelInvocation(call.invocationId(), call.runId(),
                    call.activationId(), call.attempt(), ModelInvocation.Status.PREPARED,
                    RuntimeDigest.sha256(call.request()), call.reservedTokens(), call.unknownPolicy(),
                    providerRequestId, "", "", Map.of(), call.possibleDuplicateCharge(), false, 0, "", clock.instant()));
        }
        if (cancelledRuns.contains(call.runId())) {
            return store.saveModelInvocation(copy(existing, ModelInvocation.Status.FAILED,
                    existing.providerRequestId(), Map.of(), false, 0, "model invocation cancelled before dispatch"));
        }
        ModelInvocation dispatching = store.saveModelInvocation(copy(existing, ModelInvocation.Status.DISPATCHING,
                existing.providerRequestId(), Map.of(), false, 0, ""));
        ActiveCall active = new ActiveCall(Thread.currentThread(), dispatching.providerRequestId());
        ActiveCall previous = activeCalls.putIfAbsent(call.runId(), active);
        if (previous != null) throw new IllegalStateException("a model invocation is already active for run " + call.runId());
        ActiveTimeReservation activeTime = null;
        try {
            synchronized (active) {
                if (cancelledRuns.contains(call.runId()) || active.cancelRequested) {
                    return store.saveModelInvocation(copy(dispatching, ModelInvocation.Status.FAILED,
                            dispatching.providerRequestId(), Map.of(), false, 0,
                            "model invocation cancelled before dispatch"));
                }
                active.dispatchStarted = true;
            }
            activeTime = ActiveTimeReservation.start(store, call.runId(),
                    "model:" + call.invocationId(), clock);
            ModelObservation observation = provider.dispatch(call);
            crashes.at(ricbot.domain.runtime.CrashInjector.Point.MODEL_BEFORE_OBSERVATION_PERSIST,
                    Map.of("invocationId", dispatching.invocationId()));
            ModelInvocation observed = observed(dispatching, observation);
            crashes.at(ricbot.domain.runtime.CrashInjector.Point.MODEL_AFTER_OBSERVATION_PERSIST,
                    Map.of("invocationId", observed.invocationId()));
            return observed;
        } catch (UncertainDispatchException uncertain) {
            return store.saveModelInvocation(copy(dispatching, ModelInvocation.Status.UNKNOWN,
                    uncertain.providerRequestId(), Map.of(), false, 0, uncertain.getMessage()));
        } catch (RetryableDispatchException retryable) {
            return store.saveModelInvocation(copy(dispatching, ModelInvocation.Status.FAILED,
                    retryable.providerRequestId(), Map.of(), true,
                    retryable.retryAfter().toMillis(), retryable.getMessage()));
        } catch (RuntimeException failure) {
            return store.saveModelInvocation(copy(dispatching, ModelInvocation.Status.FAILED,
                    dispatching.providerRequestId(), Map.of(), false, 0, message(failure)));
        } finally {
            if (activeTime != null) activeTime.close();
            activeCalls.remove(call.runId(), active);
        }
    }

    /** Best-effort cancellation after the durable CancelRequested event has been stored. */
    public void cancel(String runId) {
        String key = clean(runId);
        if (key.isBlank()) return;
        cancelledRuns.add(key);
        ActiveCall active = activeCalls.get(key);
        if (active == null) return;
        synchronized (active) {
            active.cancelRequested = true;
            if (!active.dispatchStarted) return;
            try { provider.cancel(active.providerRequestId); }
            catch (RuntimeException ignored) { /* interruption remains the fallback */ }
            active.thread.interrupt();
        }
    }

    public void detach(String runId) {
        String key = clean(runId);
        cancelledRuns.remove(key);
        activeCalls.remove(key);
    }

    private ModelInvocation observed(ModelInvocation current, ModelObservation observation) {
        return store.saveModelInvocation(new ModelInvocation(current.invocationId(), current.runId(),
                current.activationId(), current.attempt(), ModelInvocation.Status.OBSERVED,
                current.requestDigest(), current.reservedTokens(), current.unknownPolicy(), observation.providerRequestId(),
                observation.responseReference(), RuntimeDigest.sha256(observation.response()), observation.response(),
                current.possibleDuplicateCharge(), false, 0, "", clock.instant()));
    }

    private ModelInvocation copy(ModelInvocation value, ModelInvocation.Status status, String providerRequestId,
                                 Map<String, Object> response, boolean retryableFailure,
                                 long retryAfterMillis, String failure) {
        return new ModelInvocation(value.invocationId(), value.runId(), value.activationId(), value.attempt(), status,
                value.requestDigest(), value.reservedTokens(), value.unknownPolicy(), providerRequestId, value.responseReference(),
                value.responseDigest(), response, value.possibleDuplicateCharge(), retryableFailure,
                retryAfterMillis, failure, clock.instant());
    }

    private static String message(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }

    public record ModelCall(String invocationId, String runId, String activationId, int attempt,
                            long reservedTokens, Map<String, Object> request, boolean possibleDuplicateCharge,
                            ModelInvocation.UnknownPolicy unknownPolicy) {
        public ModelCall { request = Map.copyOf(request != null ? request : Map.of()); }
    }
    public record ModelObservation(String providerRequestId, String responseReference, Map<String, Object> response) {
        public ModelObservation { response = Map.copyOf(response != null ? response : Map.of()); }
    }
    public interface ModelProviderPort {
        /** Allocate a stable provider/client request identity before any network dispatch. */
        default String prepareRequestId(ModelCall call) { return "request:" + call.invocationId(); }
        ModelObservation dispatch(ModelCall call);
        default Optional<ModelObservation> reconcile(String providerRequestId) { return Optional.empty(); }
        default void cancel(String providerRequestId) { }
    }

    private static final class ActiveCall {
        private final Thread thread;
        private final String providerRequestId;
        private boolean dispatchStarted;
        private boolean cancelRequested;
        private ActiveCall(Thread thread, String providerRequestId) {
            this.thread = thread;
            this.providerRequestId = providerRequestId;
        }
    }
    public static final class UncertainDispatchException extends RuntimeException {
        private final String providerRequestId;
        public UncertainDispatchException(String message, String providerRequestId, Throwable cause) {
            super(message, cause); this.providerRequestId = providerRequestId != null ? providerRequestId : "";
        }
        public String providerRequestId() { return providerRequestId; }
    }
    public static final class RetryableDispatchException extends RuntimeException {
        private final String providerRequestId;
        private final Duration retryAfter;
        public RetryableDispatchException(String message, String providerRequestId,
                                          Duration retryAfter, Throwable cause) {
            super(message, cause);
            this.providerRequestId = providerRequestId != null ? providerRequestId : "";
            this.retryAfter = retryAfter != null && !retryAfter.isNegative()
                    ? retryAfter : Duration.ZERO;
        }
        public String providerRequestId() { return providerRequestId; }
        public Duration retryAfter() { return retryAfter; }
    }
}
