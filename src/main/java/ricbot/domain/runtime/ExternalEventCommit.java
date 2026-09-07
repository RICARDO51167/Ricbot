package ricbot.domain.runtime;

import ricbot.domain.runtime.dto.RuntimeDigest;

import java.util.List;
import java.util.Objects;

/** Reducer-produced, replayable transaction plan for one durable external event. */
public record ExternalEventCommit(String runId, long expectedCommitSequence,
                                  String expectedStateDigest, ExternalEvent event,
                                  List<RuntimeCommand> commands, Reduction reduction) {
    public ExternalEventCommit {
        runId = runId != null ? runId.trim() : "";
        if (runId.isBlank()) throw new IllegalArgumentException("runId is required");
        if (expectedCommitSequence < 0L) throw new IllegalArgumentException(
                "expectedCommitSequence must be non-negative");
        expectedStateDigest = expectedStateDigest != null ? expectedStateDigest.trim() : "";
        if (expectedStateDigest.isBlank()) throw new IllegalArgumentException(
                "expectedStateDigest is required");
        event = Objects.requireNonNull(event, "event");
        commands = List.copyOf(commands != null ? commands : List.of());
        reduction = Objects.requireNonNull(reduction, "reduction");
        if (!reduction.childRuns().isEmpty()) {
            throw new IllegalArgumentException("external-event reductions cannot create child Runs");
        }
        if (!runId.equals(reduction.state().spec().runId())) {
            throw new IllegalArgumentException("event commit runId mismatch");
        }
        if (reduction.state().commitSequence() != expectedCommitSequence + 1L) {
            throw new IllegalArgumentException("event reduction must advance exactly one commit");
        }
    }

    public static ExternalEventCommit reduce(RunState expected, ExternalEvent event,
                                             List<RuntimeCommand> commands, RuntimeReducer reducer) {
        RunState accepted = Objects.requireNonNull(reducer, "reducer").accept(expected, event, commands);
        return new ExternalEventCommit(expected.spec().runId(), expected.commitSequence(),
                RuntimeDigest.sha256(expected), event, commands, new Reduction(accepted, List.of()));
    }
}
