package ricbot.domain.runtime.dto;

import ricbot.domain.agent.graph.dto.GraphExecutionState;

import java.util.List;

public record ReplayView(
        String runId,
        long requestedSequence,
        long committedSequence,
        GraphExecutionState state,
        String eventDigest,
        String projectionDigest,
        boolean projectionMatches,
        List<RuntimeEventEnvelope> events
) {
    public ReplayView { events = List.copyOf(events != null ? events : List.of()); }
}
