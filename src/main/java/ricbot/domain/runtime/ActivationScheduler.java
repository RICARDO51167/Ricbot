package ricbot.domain.runtime;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** Claims durable activations; expiration recovery replaces a business RECOVERING state. */
public final class ActivationScheduler {
    private static final int TIMER_BATCH_LIMIT = 64;
    private final DurableRuntimeStore store;
    private final String owner;
    private final Clock clock;
    private final Duration leaseDuration;
    private final CrashInjector crashes;

    public ActivationScheduler(DurableRuntimeStore store, String owner, Clock clock, Duration leaseDuration) {
        this(store, owner, clock, leaseDuration, CrashInjector.NONE);
    }

    public ActivationScheduler(DurableRuntimeStore store, String owner, Clock clock, Duration leaseDuration,
                               CrashInjector crashes) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.owner = required(owner);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.leaseDuration = leaseDuration != null ? leaseDuration : Duration.ofSeconds(30);
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
        if (this.leaseDuration.isNegative() || this.leaseDuration.isZero()) throw new IllegalArgumentException("leaseDuration must be positive");
    }

    public Optional<Activation> claim(String runId) {
        tick();
        Optional<Activation> claimed = store.claim(runId, owner, clock.instant(), leaseDuration);
        claimed.ifPresent(activation -> crashes.at(CrashInjector.Point.ACTIVATION_LEASE_ACQUIRED,
                java.util.Map.of("runId", runId, "activationId", activation.activationId())));
        return claimed;
    }

    public void tick() {
        store.recoverExpiredActivations(clock.instant());
        var now = clock.instant();
        RuntimeReducer reducer = new RuntimeReducer();
        for (RunView view : store.dueTimers(now, TIMER_BATCH_LIMIT)) {
            WaitReason.RetryWait retry = (WaitReason.RetryWait) view.state().waitReason();
            String digest = ricbot.domain.runtime.dto.RuntimeDigest.sha256(retry.correlationId()).substring(0, 16);
            ExternalEvent event = new ExternalEvent.TimerExpired("timer:" + view.state().spec().runId()
                    + ":" + digest + ":" + retry.attempt(), retry.correlationId(), now,
                    java.util.Map.of("attempt", retry.attempt(), "dueAt", retry.dueAt().toString()));
            try {
                store.acceptEvent(ExternalEventBatch.single(ExternalEventCommit.reduce(
                        view.state(), event, java.util.List.of(), reducer)));
            } catch (IllegalStateException raced) {
                // Another scheduler accepted the deterministic timer or advanced the Run.
            }
        }
    }

    public boolean renew(Activation activation) {
        return store.renew(activation, owner, clock.instant(), leaseDuration);
    }

    public Duration leaseDuration() { return leaseDuration; }

    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("owner is required");
        return clean;
    }
}
