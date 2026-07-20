package ricbot.domain.agent;

import java.time.Instant;
import java.util.Objects;

/** Lineage created by branching a durable run at an exact event sequence. */
public record RunFork(
        String parentSessionKey,
        String parentRunId,
        long parentSequence,
        RunState parentState,
        String childSessionKey,
        String childRunId,
        RunState childState,
        Instant forkedAt
) {
    public RunFork {
        parentSessionKey = requireText(parentSessionKey, "parentSessionKey");
        parentRunId = requireText(parentRunId, "parentRunId");
        if (parentSequence <= 0) {
            throw new IllegalArgumentException("parentSequence must be positive");
        }
        parentState = Objects.requireNonNull(parentState, "parentState");
        childSessionKey = requireText(childSessionKey, "childSessionKey");
        childRunId = requireText(childRunId, "childRunId");
        childState = Objects.requireNonNull(childState, "childState");
        forkedAt = Objects.requireNonNullElseGet(forkedAt, Instant::now);
    }

    private static String requireText(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }
}
