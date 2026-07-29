package ricbot.domain.agent.graph;

import ricbot.domain.agent.graph.dto.GraphChannelWrite;
import ricbot.domain.agent.graph.interfacep.StateReducer;
import ricbot.domain.agent.usage.UsageLedger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class StateReducers {
    private StateReducers() {}

    public static StateReducer replace() {
        return (current, writes) -> writes.isEmpty() ? current : writes.get(writes.size() - 1).value();
    }

    public static StateReducer replaceOnce() {
        return (current, writes) -> {
            if (writes.size() > 1) throw new GraphReductionException("replaceOnce received multiple writes");
            if (current != null && !writes.isEmpty() && !Objects.equals(current, writes.get(0).value())) {
                throw new GraphReductionException("replaceOnce channel is already set");
            }
            return writes.isEmpty() ? current : writes.get(0).value();
        };
    }

    public static StateReducer orderedAppend() {
        return (current, writes) -> {
            List<Object> merged = new ArrayList<>();
            if (current instanceof Collection<?> collection) merged.addAll(collection);
            else if (current != null) throw new GraphReductionException("orderedAppend current value is not a collection");
            for (GraphChannelWrite write : writes) {
                if (write.value() instanceof Collection<?> collection) merged.addAll(collection);
                else merged.add(write.value());
            }
            return Collections.unmodifiableList(merged);
        };
    }

    /** Adds typed UsageDelta/UsageLedger values and also accepts their JSON map representation on resume. */
    public static StateReducer sumUsage() {
        return (current, writes) -> {
            UsageLedger ledger = UsageLedger.from(current);
            for (GraphChannelWrite write : writes) ledger = ledger.plus(UsageLedger.from(write.value()));
            return ledger;
        };
    }

    /** Append-only artifact references, deduplicated by artifactId across retries. */
    public static StateReducer artifactsById() {
        return (current, writes) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            if (current instanceof Collection<?> collection) collection.forEach(value -> putArtifact(values, value));
            else if (current != null) throw new GraphReductionException("artifactRefs current value is not a collection");
            for (GraphChannelWrite write : writes) {
                if (write.value() instanceof Collection<?> collection) collection.forEach(value -> putArtifact(values, value));
                else putArtifact(values, write.value());
            }
            return List.copyOf(values.values());
        };
    }

    private static void putArtifact(Map<String, Object> values, Object value) {
        String id;
        if (value instanceof ricbot.domain.agent.artifact.ArtifactRef ref) id = ref.artifactId();
        else if (value instanceof Map<?, ?> map) id = String.valueOf(map.containsKey("artifactId") ? map.get("artifactId") : "");
        else throw new GraphReductionException("artifact reference has no typed identity");
        if (id.isBlank()) throw new GraphReductionException("artifactId is required");
        Object previous = values.putIfAbsent(id, value);
        if (previous != null && !Objects.equals(previous, value)) {
            throw new GraphReductionException("conflicting artifact reference: " + id);
        }
    }

    /** Exactly-once delivery reducer keyed by logical task id and attempt. */
    public static StateReducer taskResultsByAttempt() {
        return (current, writes) -> {
            Map<String, Object> byAttempt = new LinkedHashMap<>();
            if (current instanceof Collection<?> collection) {
                for (Object value : collection) putTaskResult(byAttempt, value);
            } else if (current != null) throw new GraphReductionException("task results current value is not a collection");
            for (GraphChannelWrite write : writes) {
                if (write.value() instanceof Collection<?> collection) {
                    for (Object value : collection) putTaskResult(byAttempt, value);
                } else putTaskResult(byAttempt, write.value());
            }
            return byAttempt.values().stream().sorted(java.util.Comparator
                    .comparingInt(StateReducers::planOrder).thenComparing(StateReducers::taskIdentity)).toList();
        };
    }

    private static void putTaskResult(Map<String, Object> target, Object value) {
        String identity = taskIdentity(value);
        Object existing = target.putIfAbsent(identity, value);
        if (existing != null && !Objects.equals(existing, value)) {
            throw new GraphReductionException("conflicting task result delivery: " + identity);
        }
    }

    private static String taskIdentity(Object value) {
        if (value instanceof ricbot.domain.task.TaskResult result) return result.taskId() + ":" + result.attempt();
        if (value instanceof Map<?, ?> map) {
            Object taskId = map.containsKey("taskId") ? map.get("taskId") : map.get("task_id");
            Object attempt = map.get("attempt");
            return String.valueOf(taskId) + ":" + (attempt instanceof Number number ? number.intValue() : 1);
        }
        throw new GraphReductionException("task result has no typed identity");
    }

    private static int planOrder(Object value) {
        if (value instanceof ricbot.domain.task.TaskResult result) return result.planOrder();
        if (value instanceof Map<?, ?> map) {
            Object order = map.containsKey("planOrder") ? map.get("planOrder") : map.get("plan_order");
            return order instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
        }
        return Integer.MAX_VALUE;
    }

    public static StateReducer mapMergeByKey() {
        return (current, writes) -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (current instanceof Map<?, ?> map) copyMap(map, merged);
            else if (current != null) throw new GraphReductionException("mapMergeByKey current value is not a map");
            Set<String> written = new LinkedHashSet<>();
            for (GraphChannelWrite write : writes) {
                if (!(write.value() instanceof Map<?, ?> map)) {
                    throw new GraphReductionException("mapMergeByKey write is not a map");
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (!written.add(key)) throw new GraphReductionException("mapMergeByKey conflict for key: " + key);
                    if (merged.containsKey(key) && !Objects.equals(merged.get(key), entry.getValue())) {
                        throw new GraphReductionException("mapMergeByKey conflicts with existing key: " + key);
                    }
                    merged.put(key, entry.getValue());
                }
            }
            return Collections.unmodifiableMap(merged);
        };
    }

    public static StateReducer setUnion() {
        return (current, writes) -> {
            Set<Object> merged = new LinkedHashSet<>();
            if (current instanceof Collection<?> collection) merged.addAll(collection);
            else if (current != null) throw new GraphReductionException("setUnion current value is not a collection");
            for (GraphChannelWrite write : writes) {
                if (write.value() instanceof Collection<?> collection) merged.addAll(collection);
                else merged.add(write.value());
            }
            return Collections.unmodifiableSet(merged);
        };
    }

    private static void copyMap(Map<?, ?> source, Map<String, Object> target) {
        source.forEach((key, value) -> target.put(String.valueOf(key), value));
    }

    public static final class GraphReductionException extends IllegalStateException {
        public GraphReductionException(String message) { super(message); }
    }
}
