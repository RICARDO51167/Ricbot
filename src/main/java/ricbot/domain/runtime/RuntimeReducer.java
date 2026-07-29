package ricbot.domain.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.runtime.dto.RuntimeAggregate;
import ricbot.domain.runtime.dto.RuntimeDigest;
import ricbot.domain.runtime.dto.RuntimeEventEnvelope;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure, deterministic event reducer. Unknown schema versions have already failed closed in the upcaster. */
public final class RuntimeReducer {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public RuntimeAggregate reduce(String runId, List<RuntimeEventEnvelope> events) {
        GraphExecutionState graph = null;
        Map<String, JsonNode> activations = new TreeMap<>();
        Map<String, JsonNode> tasks = new TreeMap<>();
        Map<String, JsonNode> results = new TreeMap<>();
        Map<String, JsonNode> deliveries = new TreeMap<>();
        Map<String, JsonNode> approvals = new TreeMap<>();
        Map<String, JsonNode> effects = new TreeMap<>();
        Map<String, JsonNode> patches = new TreeMap<>();
        Map<String, JsonNode> verifiers = new TreeMap<>();
        long previous = 0;
        long through = 0;
        for (RuntimeEventEnvelope event : events != null ? events : List.<RuntimeEventEnvelope>of()) {
            if (!event.runId().equals(runId)) throw new IllegalStateException("event belongs to another run");
            if (event.globalSequence() <= previous) throw new IllegalStateException("runtime event order is not strict");
            previous = event.globalSequence();
            through = previous;
            JsonNode payload = event.payload();
            JsonNode state = payload.path("state");
            if (!state.isMissingNode() && !state.isNull()) {
                GraphExecutionState next = value(state, GraphExecutionState.class);
                if (!next.runId().equals(runId)) throw new IllegalStateException("checkpoint run id mismatch");
                String recorded = payload.path("stateDigest").asText("");
                String rebuilt = RuntimeDigest.sha256(next);
                if (recorded.isBlank() || !recorded.equals(rebuilt)) {
                    throw new IllegalStateException("checkpoint event digest mismatch at " + event.globalSequence()
                            + " recorded=" + recorded + " rebuilt=" + rebuilt);
                }
                if (graph != null && next.transition() < graph.transition()) {
                    throw new IllegalStateException("checkpoint transition moved backwards");
                }
                graph = next;
                activations.clear();
                for (var activation : next.activeNodes()) {
                    activations.put(activation.activationId(), MAPPER.valueToTree(activation));
                }
            }
            reduceTyped(event, payload, tasks, results, deliveries, approvals, effects, patches, verifiers);
            JsonNode acknowledged = payload.path("acknowledgedDeliveryIds");
            if (acknowledged.isArray()) for (JsonNode id : acknowledged) {
                JsonNode existing = deliveries.get(id.asText());
                if (existing instanceof com.fasterxml.jackson.databind.node.ObjectNode mutable) {
                    mutable.put("acknowledged", true);
                }
            }
        }
        return new RuntimeAggregate(runId, graph, activations, tasks, results, deliveries, approvals, effects,
                patches, verifiers, through);
    }

    private static void reduceTyped(RuntimeEventEnvelope event, JsonNode payload,
                                    Map<String, JsonNode> tasks, Map<String, JsonNode> results,
                                    Map<String, JsonNode> deliveries, Map<String, JsonNode> approvals,
                                    Map<String, JsonNode> effects, Map<String, JsonNode> patches,
                                    Map<String, JsonNode> verifiers) {
        String type = event.eventType();
        if (type.startsWith("TASK_")) {
            putTask(payload.path("task").isMissingNode() ? payload : payload.path("task"), tasks);
            JsonNode result = payload.path("result");
            if (!result.isMissingNode()) putResult(result, results);
            JsonNode delivery = payload.path("delivery");
            if (!delivery.isMissingNode()) put(delivery, "deliveryId", deliveries);
        }
        if (type.equals("DELIVERY_ENQUEUED")) put(payload, "deliveryId", deliveries);
        if (type.equals("DELIVERY_ACKNOWLEDGED")) {
            String deliveryId = payload.path("deliveryId").asText("");
            JsonNode existing = deliveries.get(deliveryId);
            if (existing instanceof com.fasterxml.jackson.databind.node.ObjectNode mutable) {
                mutable.put("acknowledged", true);
            }
        }
        if (type.startsWith("APPROVAL_")) put(payload, "requestId", approvals);
        if (type.startsWith("SIDE_EFFECT_")) put(payload, "idempotencyKey", effects);
        if (type.contains("PATCH") || type.contains("CHANGE_SET")) {
            JsonNode fact = type.startsWith("GRAPH:") ? payload.path("data") : payload;
            String key = first(fact, "digest", "patchId", "changeSetId", "id");
            if (!key.isBlank()) patches.put(key, fact.deepCopy());
        }
        if (type.startsWith("VERIFIER_")) put(payload, "reportId", verifiers);
    }

    private static void putTask(JsonNode node, Map<String, JsonNode> target) { put(node, "spec", "taskId", target); }
    private static void putResult(JsonNode node, Map<String, JsonNode> target) {
        String taskId = node.path("taskId").asText("");
        if (!taskId.isBlank()) target.put(taskId + ":" + node.path("attempt").asInt(1), node.deepCopy());
    }
    private static void put(JsonNode node, String key, Map<String, JsonNode> target) {
        String value = node.path(key).asText("");
        if (!value.isBlank()) target.put(value, node.deepCopy());
    }
    private static void put(JsonNode node, String object, String key, Map<String, JsonNode> target) {
        JsonNode nested = node.path(object);
        String value = nested.path(key).asText("");
        if (!value.isBlank()) target.put(value, node.deepCopy());
    }
    private static String first(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }
    private static <T> T value(JsonNode node, Class<T> type) {
        try { return MAPPER.treeToValue(node, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot reduce runtime event payload", failure); }
    }
}
