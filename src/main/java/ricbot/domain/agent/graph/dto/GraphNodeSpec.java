package ricbot.domain.agent.graph.dto;

import ricbot.domain.agent.graph.enump.GraphFailurePolicy;

import java.time.Duration;
import java.util.Objects;

public record GraphNodeSpec(
        String nodeId,
        String executorId,
        Duration timeout,
        GraphRetryPolicy retryPolicy,
        GraphFailurePolicy failurePolicy,
        boolean terminal
) {
    public GraphNodeSpec {
        nodeId = required(nodeId, "nodeId");
        executorId = executorId != null && !executorId.isBlank() ? executorId.trim() : nodeId;
        timeout = Objects.requireNonNullElse(timeout, Duration.ofMinutes(5));
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        retryPolicy = Objects.requireNonNullElseGet(retryPolicy, GraphRetryPolicy::none);
        failurePolicy = Objects.requireNonNullElse(failurePolicy, GraphFailurePolicy.FAIL_STOP);
    }

    public static GraphNodeSpec standard(String id) {
        return new GraphNodeSpec(id, id, Duration.ofMinutes(5), GraphRetryPolicy.none(),
                GraphFailurePolicy.FAIL_STOP, false);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
