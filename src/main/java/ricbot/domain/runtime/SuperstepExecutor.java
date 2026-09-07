package ricbot.domain.runtime;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class SuperstepExecutor {
    private final DurableRuntimeStore store;
    private final PhaseExecutor phases;
    private final RuntimeReducer reducer;
    private final AtomicCommitter committer;
    private final CrashInjector crashes;

    public SuperstepExecutor(DurableRuntimeStore store, PhaseExecutor phases, RuntimeReducer reducer,
                             AtomicCommitter committer) {
        this(store, phases, reducer, committer, CrashInjector.NONE);
    }

    public SuperstepExecutor(DurableRuntimeStore store, PhaseExecutor phases, RuntimeReducer reducer,
                             AtomicCommitter committer, CrashInjector crashes) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.phases = java.util.Objects.requireNonNull(phases, "phases");
        this.reducer = java.util.Objects.requireNonNull(reducer, "reducer");
        this.committer = java.util.Objects.requireNonNull(committer, "committer");
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
    }

    public RunState execute(Activation activation) {
        RunView view = store.get(activation.runId()).orElseThrow(() -> new IllegalStateException("run disappeared"));
        RunState running = view.state();
        if (running.status() != RunStatus.RUNNING || running.phase() != activation.phase()) {
            throw new IllegalStateException("claimed activation does not match RUNNING state");
        }
        List<ExternalEvent> pendingBefore = store.pendingInbox(running.spec().runId());
        boolean ingesting = running.phase() == RuntimePhase.INGEST;
        PhaseResult result;
        if (hasCancellation(pendingBefore)) {
            result = boundaryResult(running, pendingBefore);
        } else if (!ingesting && !pendingBefore.isEmpty()) {
            result = boundaryResult(running, pendingBefore);
        } else {
            result = phases.execute(new PhaseContext(running, activation.activationId(),
                    ingesting ? pendingBefore : List.of()));
        }
        crashes.at(CrashInjector.Point.PENDING_WRITE_STAGED,
                java.util.Map.of("runId", activation.runId(), "phase", running.phase().name()));
        java.util.Set<String> knownIds = pendingBefore.stream().map(ExternalEvent::eventId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ExternalEvent> observed = new ArrayList<>(pendingBefore);
        PhaseResult prepared = result;
        while (true) {
            List<ExternalEvent> pending = store.pendingInbox(running.spec().runId());
            List<ExternalEvent> late = pending.stream()
                    .filter(event -> knownIds.add(event.eventId())).toList();
            if (!late.isEmpty()) {
                observed.addAll(late);
                prepared = appendBoundary(prepared, running, late);
            }
            Reduction reduction = reducer.reduce(running, prepared.writes(), prepared.commands());
            List<String> consumed = new ArrayList<>();
            if (ingesting) consumed.addAll(pendingBefore.stream().map(ExternalEvent::eventId).toList());
            if (hasCancellation(observed)) {
                consumed.addAll(pending.stream().map(ExternalEvent::eventId).toList());
            }
            try {
                return committer.commit(activation, running, reduction, prepared,
                        consumed.stream().distinct().toList());
            } catch (InboxChangedException ignored) {
                // The phase result remains valid. Re-reduce it with the newly durable Inbox event.
            }
        }
    }

    private PhaseResult boundaryResult(RunState state, List<ExternalEvent> events) {
        return new PhaseResult(List.of(), List.of(boundaryCommand(state, events)));
    }

    private PhaseResult appendBoundary(PhaseResult result, RunState state, List<ExternalEvent> events) {
        List<RuntimeCommand> commands = new ArrayList<>(result.commands());
        commands.add(boundaryCommand(state, events));
        return new PhaseResult(result.writes(), commands);
    }

    private RuntimeCommand boundaryCommand(RunState state, List<ExternalEvent> events) {
        ExternalEvent.CancelRequested cancel = events.stream()
                .filter(ExternalEvent.CancelRequested.class::isInstance)
                .map(ExternalEvent.CancelRequested.class::cast).findFirst().orElse(null);
        if (cancel != null) {
            java.util.Set<String> confirmed = events.stream()
                    .filter(ExternalEvent.EffectConfirmation.class::isInstance)
                    .map(ExternalEvent.EffectConfirmation.class::cast)
                    .map(event -> String.valueOf(event.payload().getOrDefault("effectId", "")))
                    .collect(java.util.stream.Collectors.toSet());
            List<String> effects = store.unresolvedEffects(state.spec().runId()).stream()
                    .map(record -> record.intent().effectId()).filter(id -> !confirmed.contains(id))
                    .sorted().toList();
            return new RuntimeCommand.CancelAtBoundary(cancel.correlationId(), effects);
        }
        return new RuntimeCommand.ExternalInterrupt(events.stream().map(ExternalEvent::eventId).toList());
    }

    private static boolean hasCancellation(List<ExternalEvent> events) {
        return events.stream().anyMatch(ExternalEvent.CancelRequested.class::isInstance);
    }
}
