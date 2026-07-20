package ricbot.domain.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured wrapper around the JSON map persisted in session files.
 *
 * The storage format remains JSONL maps for compatibility, but message creation
 * and common validation rules live here instead of being repeated by callers.
 */
public final class SessionMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    private final Map<String, Object> data;

    private SessionMessage(Map<String, Object> data) {
        this.data = normalize(data);
    }

    public static SessionMessage fromMap(Map<String, Object> raw) {
        return new SessionMessage(raw);
    }

    public static SessionMessage of(String role, Object content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(Session.KEY_ROLE, normalizeRole(role));
        data.put(Session.KEY_CONTENT, content);
        return new SessionMessage(data);
    }

    public static SessionMessage user(Object content) {
        return of(ROLE_USER, content);
    }

    public static SessionMessage assistant(String content, List<Map<String, Object>> toolCalls) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(Session.KEY_ROLE, ROLE_ASSISTANT);
        data.put(Session.KEY_CONTENT, content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            data.put(Session.KEY_TOOL_CALLS, new ArrayList<>(toolCalls));
        }
        return new SessionMessage(data);
    }

    public static SessionMessage tool(String toolCallId, String name, Object content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(Session.KEY_ROLE, ROLE_TOOL);
        data.put(Session.KEY_TOOL_CALL_ID, toolCallId);
        data.put(Session.KEY_NAME, name);
        data.put(Session.KEY_CONTENT, content);
        return new SessionMessage(data);
    }

    public String role() {
        return String.valueOf(data.getOrDefault(Session.KEY_ROLE, ""));
    }

    public Object content() {
        return data.get(Session.KEY_CONTENT);
    }

    public boolean hasToolCalls() {
        Object toolCalls = data.get(Session.KEY_TOOL_CALLS);
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    public List<String> toolCallIds() {
        Object raw = data.get(Session.KEY_TOOL_CALLS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object id = map.get(Session.KEY_ID);
                if (id != null && !String.valueOf(id).isBlank()) {
                    ids.add(String.valueOf(id));
                }
            }
        }
        return ids;
    }

    public String toolCallId() {
        Object id = data.get(Session.KEY_TOOL_CALL_ID);
        return id != null ? String.valueOf(id) : "";
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(data);
    }

    public static boolean isKnownRole(String role) {
        return ROLE_SYSTEM.equals(role)
                || ROLE_USER.equals(role)
                || ROLE_ASSISTANT.equals(role)
                || ROLE_TOOL.equals(role);
    }

    private static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = raw != null ? new LinkedHashMap<>(raw) : new LinkedHashMap<>();
        out.put(Session.KEY_ROLE, normalizeRole(String.valueOf(out.getOrDefault(Session.KEY_ROLE, ROLE_USER))));
        out.putIfAbsent(Session.KEY_TIMESTAMP, Instant.now().toString());
        if (!out.containsKey(Session.KEY_CONTENT) && !ROLE_ASSISTANT.equals(out.get(Session.KEY_ROLE))) {
            out.put(Session.KEY_CONTENT, "");
        }
        return out;
    }

    private static String normalizeRole(String role) {
        String normalized = role != null ? role.trim() : "";
        return isKnownRole(normalized) ? normalized : ROLE_USER;
    }
}
