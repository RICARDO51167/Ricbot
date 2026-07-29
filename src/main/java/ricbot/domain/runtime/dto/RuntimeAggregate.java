package ricbot.domain.runtime.dto;

import com.fasterxml.jackson.databind.JsonNode;
import ricbot.domain.agent.graph.dto.GraphExecutionState;

import java.util.Map;
import java.util.TreeMap;

/** Immutable event-reduced aggregate spanning every execution-relevant projection. */
public record RuntimeAggregate(
        String runId,
        GraphExecutionState graph,
        Map<String, JsonNode> activations,
        Map<String, JsonNode> tasks,
        Map<String, JsonNode> taskResults,
        Map<String, JsonNode> deliveries,
        Map<String, JsonNode> approvals,
        Map<String, JsonNode> sideEffects,
        Map<String, JsonNode> patches,
        Map<String, JsonNode> verifiers,
        long throughGlobalSequence
) {
    public RuntimeAggregate {
        activations = immutable(activations);
        tasks = immutable(tasks);
        taskResults = immutable(taskResults);
        deliveries = immutable(deliveries);
        approvals = immutable(approvals);
        sideEffects = immutable(sideEffects);
        patches = immutable(patches);
        verifiers = immutable(verifiers);
    }

    public Map<String, String> digests() {
        Map<String, String> values = new TreeMap<>();
        if (graph != null) values.put("RUN", RuntimeDigest.sha256(graph));
        values.put("ACTIVATION", RuntimeDigest.sha256(activations));
        values.put("TASK", RuntimeDigest.sha256(tasks));
        values.put("TASK_RESULT", RuntimeDigest.sha256(taskResults));
        values.put("DELIVERY", RuntimeDigest.sha256(deliveries));
        values.put("APPROVAL", RuntimeDigest.sha256(approvals));
        values.put("SIDE_EFFECT", RuntimeDigest.sha256(sideEffects));
        values.put("PATCH", RuntimeDigest.sha256(patches));
        values.put("VERIFIER", RuntimeDigest.sha256(verifiers));
        return Map.copyOf(values);
    }

    private static Map<String, JsonNode> immutable(Map<String, JsonNode> values) {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(values != null ? values : Map.of()));
    }
}
