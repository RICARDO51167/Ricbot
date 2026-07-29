package ricbot.domain.agent.graph.dto;

import java.time.Instant;
import java.util.Objects;

/** Persisted retry decision for exactly one failed activation attempt. */
public record GraphRetrySchedule(
        NodeActivation activation,
        Instant availableAt,
        String failureKind,
        String message
) {
    public GraphRetrySchedule {
        activation = Objects.requireNonNull(activation, "activation");
        availableAt = Objects.requireNonNull(availableAt, "availableAt");
        failureKind = clean(failureKind);
        message = clean(message);
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
