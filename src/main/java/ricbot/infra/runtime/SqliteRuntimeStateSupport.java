package ricbot.infra.runtime;

import ricbot.domain.runtime.ExternalEvent;
import ricbot.domain.runtime.RunSpec;
import ricbot.domain.runtime.RunState;
import ricbot.domain.runtime.RunStatus;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.domain.runtime.RuntimeReducer;
import ricbot.domain.runtime.WaitReason;

import java.util.List;
import java.util.Map;

/** Domain-state planning kept outside the SQLite persistence adapter. */
final class SqliteRuntimeStateSupport {
    private SqliteRuntimeStateSupport() { }

    static RunState copy(RunState state, RunStatus status, RuntimePhase phase, long commit,
                         long superstep, WaitReason wait, boolean cancelRequested) {
        return new RunState(RunState.SCHEMA_VERSION, RunState.GRAPH_VERSION, state.spec(), status, phase,
                superstep, commit, state.channels(), wait, cancelRequested, state.failureCode(),
                state.failureMessage(), state.childRunIds(), state.artifactReferences());
    }

    static RunState initial(RunSpec spec, List<String> unresolvedDependencies) {
        return dependencyGated(RunState.initial(spec), unresolvedDependencies);
    }

    static RunState dependencyGated(RunState initial, List<String> unresolvedDependencies) {
        List<String> unresolved = List.copyOf(unresolvedDependencies != null
                ? unresolvedDependencies : List.of());
        if (unresolved.isEmpty()) return initial;
        return new RunState(RunState.SCHEMA_VERSION, RunState.GRAPH_VERSION, initial.spec(),
                RunStatus.WAITING, RuntimePhase.WAIT, initial.superstep(), initial.commitSequence(),
                initial.channels(), new WaitReason.ChildRunWait(
                "dependencies:" + initial.spec().runId(), unresolved), initial.cancelRequested(),
                initial.failureCode(), initial.failureMessage(), initial.childRunIds(),
                initial.artifactReferences());
    }

    static RunState accept(RunState current, ExternalEvent event, List<RuntimeCommand> commands) {
        return new RuntimeReducer().accept(current, event, commands);
    }
}
