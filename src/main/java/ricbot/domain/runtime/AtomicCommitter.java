package ricbot.domain.runtime;

import java.time.Clock;
import java.util.List;
import java.util.Map;

public final class AtomicCommitter {
    private final DurableRuntimeStore store;
    private final Clock clock;
    private final CrashInjector crashes;

    public AtomicCommitter(DurableRuntimeStore store, Clock clock) {
        this(store, clock, CrashInjector.NONE);
    }

    public AtomicCommitter(DurableRuntimeStore store, Clock clock, CrashInjector crashes) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
    }

    public RunState commit(Activation activation, RunState expected, Reduction reduction,
                           PhaseResult result, List<String> consumedEventIds) {
        crashes.at(CrashInjector.Point.COMMIT_BEFORE,
                Map.of("runId", activation.runId(), "activationId", activation.activationId()));
        RunState committed = store.commit(new CommitBatch(activation, expected, reduction, result.writes(), result.commands(),
                consumedEventIds, "SUPERSTEP_COMMITTED",
                Map.of("phase", expected.phase().name(), "superstep", reduction.state().superstep()),
                clock.instant()));
        crashes.at(CrashInjector.Point.COMMIT_AFTER,
                Map.of("runId", activation.runId(), "commitSequence", committed.commitSequence()));
        return committed;
    }
}
