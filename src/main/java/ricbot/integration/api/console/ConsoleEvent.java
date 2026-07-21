package ricbot.integration.api.console;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ConsoleEvent(
        String id,
        String sessionId,
        String runId,
        String type,
        String name,
        String category,
        String status,
        String time,
        String title,
        String summary,
        String actor,
        String source,
        Map<String, Object> payload
) {
    public ConsoleEvent {
        id = clean(id).isBlank() ? "console_event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : clean(id);
        sessionId = clean(sessionId);
        runId = clean(runId);
        type = clean(type).isBlank() ? "system_event" : clean(type);
        name = clean(name).isBlank() ? type : clean(name);
        category = clean(category).isBlank() ? "system" : clean(category).toLowerCase(java.util.Locale.ROOT);
        status = clean(status).isBlank() ? "INFO" : clean(status);
        time = clean(time).isBlank() ? Instant.now().toString() : clean(time);
        title = clean(title).isBlank() ? name : clean(title);
        summary = clean(summary);
        actor = clean(actor).isBlank() ? "system" : clean(actor);
        source = clean(source).isBlank() ? "runtime_projection" : clean(source);
        payload = payload != null
                ? java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload))
                : Map.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("sessionId", sessionId);
        out.put("runId", runId);
        out.put("type", type);
        out.put("name", name);
        out.put("category", category);
        out.put("status", status);
        out.put("time", time);
        out.put("title", title);
        out.put("summary", summary);
        out.put("actor", actor);
        out.put("source", source);
        out.put("payload", payload);
        return out;
    }

    public static ConsoleEvent fromMap(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> payload = map(raw.get("payload"));
        return new ConsoleEvent(
                string(raw.get("id")),
                firstNonBlank(raw.get("sessionId"), raw.get("session_id"), payload.get("sessionId"), payload.get("session_id")),
                firstNonBlank(raw.get("runId"), raw.get("run_id"), payload.get("runId"), payload.get("run_id")),
                string(raw.get("type")),
                string(raw.get("name")),
                string(raw.get("category")),
                string(raw.get("status")),
                firstNonBlank(raw.get("time"), raw.get("timestamp"), raw.get("createdAt")),
                string(raw.get("title")),
                string(raw.get("summary")),
                string(raw.get("actor")),
                string(raw.get("source")),
                payload
        );
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = string(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
