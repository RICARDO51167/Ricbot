package ricbot.domain.runtime;

import java.util.Map;

/** A source Run tree projected at one database-journal cutoff. */
public record ForkSnapshot(RunState root, Map<String, RunState> descendants, long journalSequence) {
    public ForkSnapshot {
        if (root == null) throw new IllegalArgumentException("root is required");
        descendants = Map.copyOf(descendants != null ? descendants : Map.of());
        if (journalSequence < 0) throw new IllegalArgumentException("journalSequence cannot be negative");
    }

    public RunState require(String runId) {
        if (root.spec().runId().equals(runId)) return root;
        RunState state = descendants.get(runId);
        if (state == null) throw new IllegalStateException("fork source descendant is missing: " + runId);
        return state;
    }
}
