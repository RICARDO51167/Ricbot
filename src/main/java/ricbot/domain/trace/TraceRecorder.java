package ricbot.domain.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TraceRecorder {

    private final List<Map<String, Object>> runEvents;

    private TraceRecorder(List<Map<String, Object>> runEvents) {
        this.runEvents = runEvents != null ? runEvents : new ArrayList<>();
    }

    public static TraceRecorder forRunEvents(List<Map<String, Object>> runEvents) {
        return new TraceRecorder(runEvents);
    }

    public Map<String, Object> recordRunEvent(String eventType, Map<String, Object> metadata) {
        return record(eventType, metadata);
    }

    public Map<String, Object> recordToolEvent(String toolId, Map<String, Object> metadata) {
        Map<String, Object> values = copy(metadata);
        values.put("tool_id", clean(toolId));
        return record("tool_call", values);
    }

    public Map<String, Object> recordApprovalEvent(String approvalId, Map<String, Object> metadata) {
        Map<String, Object> values = copy(metadata);
        String eventType = clean(String.valueOf(values.getOrDefault("event_type", "approval_event")));
        values.remove("event_type");
        values.put("approval_id", clean(approvalId));
        return record(eventType.isBlank() ? "approval_event" : eventType, values);
    }

    private Map<String, Object> record(String eventType, Map<String, Object> metadata) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", clean(eventType));
        event.put("at", Instant.now().toString());
        event.putAll(copy(metadata));
        runEvents.add(event);
        return event;
    }

    private static Map<String, Object> copy(Map<String, Object> metadata) {
        return metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
