package ricbot.domain.agent.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Indivisible conversational unit. Tool calls and every associated result stay together. */
public record ContextSegment(String segmentId, Kind kind, List<Map<String, Object>> messages, Set<String> toolCallIds) {
    public enum Kind { SYSTEM, USER_TURN, ASSISTANT, TOOL_INTERACTION }
    public ContextSegment {
        segmentId = segmentId != null && !segmentId.isBlank() ? segmentId : UUID.randomUUID().toString();
        kind = kind != null ? kind : Kind.ASSISTANT;
        messages = List.copyOf(messages != null ? messages : List.of());
        toolCallIds = Set.copyOf(toolCallIds != null ? toolCallIds : Set.of());
    }

    public static List<ContextSegment> parse(List<Map<String, Object>> source) {
        List<ContextSegment> result = new ArrayList<>();
        for (int index = 0; index < source.size();) {
            Map<String, Object> message = source.get(index);
            String role = String.valueOf(message.getOrDefault("role", ""));
            Set<String> calls = callIds(message);
            if (!calls.isEmpty()) {
                List<Map<String, Object>> unit = new ArrayList<>();
                unit.add(message);
                Set<String> pending = new LinkedHashSet<>(calls);
                int cursor = index + 1;
                while (cursor < source.size() && !pending.isEmpty()) {
                    Map<String, Object> next = source.get(cursor);
                    if (!"tool".equals(String.valueOf(next.getOrDefault("role", "")))) break;
                    String callId = String.valueOf(next.getOrDefault("tool_call_id", ""));
                    if (!pending.remove(callId)) break;
                    unit.add(next);
                    cursor++;
                }
                if (!pending.isEmpty()) throw new IllegalArgumentException("orphan tool call(s): " + pending);
                result.add(new ContextSegment(id(message, index), Kind.TOOL_INTERACTION, unit, calls));
                index = cursor;
                continue;
            }
            if ("tool".equals(role)) {
                throw new IllegalArgumentException("orphan tool result: " + message.getOrDefault("tool_call_id", ""));
            }
            Kind kind = "system".equals(role) ? Kind.SYSTEM : "user".equals(role) ? Kind.USER_TURN : Kind.ASSISTANT;
            result.add(new ContextSegment(id(message, index), kind, List.of(message), Set.of()));
            index++;
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked") private static Set<String> callIds(Map<String, Object> message) {
        Set<String> ids = new LinkedHashSet<>();
        Object raw = message.get("tool_calls");
        if (raw instanceof List<?> calls) for (Object value : calls) if (value instanceof Map<?, ?> call) {
            Object rawId = call.get("id");
            String id = rawId != null ? String.valueOf(rawId) : "";
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }
    private static String id(Map<String, Object> message, int index) {
        Object value = message.get("id");
        return value != null && !String.valueOf(value).isBlank() ? String.valueOf(value) : "segment-" + index;
    }
}
