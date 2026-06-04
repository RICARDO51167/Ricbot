package ricbot.integration.api.console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ConsoleTimelineAssembler {
    List<Map<String, Object>> assemble(String sessionId, List<Map<String, Object>> rawEvents, String categoryFilter) {
        return assemble(sessionId, rawEvents, categoryFilter, "");
    }

    List<Map<String, Object>> assemble(String sessionId, List<Map<String, Object>> rawEvents, String categoryFilter, String runIdFilter) {
        Set<String> allowedCategories = categories(categoryFilter);
        String selectedRunId = string(runIdFilter);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> semanticKeys = new LinkedHashSet<>();
        for (Map<String, Object> raw : rawEvents != null ? rawEvents : List.<Map<String, Object>>of()) {
            Map<String, Object> event = enrich(sessionId, raw);
            String id = string(event.get("id"));
            String semantic = semanticKey(event);
            if (!id.isBlank() && !ids.add(id)) {
                continue;
            }
            if (!semantic.isBlank() && !semanticKeys.add(semantic)) {
                continue;
            }
            if (!allowedCategories.isEmpty() && !allowedCategories.contains(string(event.get("category")))) {
                continue;
            }
            if (!selectedRunId.isBlank() && !selectedRunId.equals(string(event.get("runId")))) {
                continue;
            }
            out.add(event);
        }
        return out.stream()
                .sorted(Comparator.comparing(event -> {
                    String time = string(event.get("time"));
                    return time.isBlank() ? "9999-12-31T23:59:59Z" : time;
                }))
                .toList();
    }

    Map<String, Object> enrich(String sessionId, Map<String, Object> raw) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (raw != null) {
            event.putAll(raw);
        }
        Map<String, Object> payload = map(event.get("payload"));
        String type = string(event.get("type"));
        String name = firstNonBlank(event.get("name"), payload.get("type"), payload.get("event_type"), payload.get("eventType"));
        if (name.isBlank()) {
            name = nameFromType(type, event, payload);
        }
        String category = category(type, name);
        event.putIfAbsent("sessionId", firstNonBlank(event.get("sessionId"), payload.get("sessionId"), payload.get("session_id"), sessionId));
        event.putIfAbsent("runId", firstNonBlank(event.get("runId"), payload.get("runId"), payload.get("run_id")));
        event.put("name", name);
        event.put("category", category);
        event.put("status", status(string(event.get("status")), category, name));
        event.putIfAbsent("actor", actor(category, name));
        event.putIfAbsent("source", source(type, category));
        event.put("payload", payload);
        return event;
    }

    private String semanticKey(Map<String, Object> event) {
        String time = string(event.get("time"));
        String name = string(event.get("name"));
        String category = string(event.get("category"));
        String runId = string(event.get("runId"));
        if ("run".equals(category) && !runId.isBlank() && name.startsWith("run_")) {
            return runId + "|" + category + "|" + name;
        }
        if (time.isBlank() || name.isBlank() || category.isBlank()) {
            return "";
        }
        return time + "|" + category + "|" + name;
    }

    private Set<String> categories(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isBlank() && !"all".equals(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private String nameFromType(String type, Map<String, Object> event, Map<String, Object> payload) {
        return switch (type) {
            case "tool_call" -> firstNonBlank(payload.get("event_type"), payload.get("toolName"), payload.get("name"), "tool_call");
            case "approval_event" -> "approval_event";
            case "changeset_event" -> "changeset_event";
            case "trace_event" -> firstNonBlank(payload.get("type"), event.get("title"), "trace_event");
            case "user_message", "assistant_message", "system_message", "tool_message" -> type;
            default -> !type.isBlank() ? type : "system_event";
        };
    }

    private String category(String type, String name) {
        String text = (type + " " + name).toLowerCase(Locale.ROOT);
        if (text.contains("error") || text.contains("timeout") || text.contains("failed")) {
            return "error";
        }
        if (text.contains("approval")) {
            return "approval";
        }
        if (text.contains("changeset") || text.contains("diff")) {
            return "changeset";
        }
        if (text.contains("tool")) {
            return "tool";
        }
        if (text.contains("run") || text.contains("model") || text.contains("capability")) {
            return "run";
        }
        return "system";
    }

    private String status(String raw, String category, String name) {
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        String text = (raw + " " + name).toLowerCase(Locale.ROOT);
        if (text.contains("error") || text.contains("failed") || text.contains("timeout")) {
            return "ERROR";
        }
        if (text.contains("success") || text.contains("pass") || text.contains("finish") || text.contains("approved")) {
            return "SUCCESS";
        }
        if (text.contains("cancel")) {
            return "CANCELLED";
        }
        if (text.contains("warn") || text.contains("pending") || text.contains("approval")) {
            return "WARN";
        }
        if ("approval".equals(category)) {
            return "PENDING";
        }
        return "INFO";
    }

    private String actor(String category, String name) {
        if ("approval".equals(category) || name.contains("cancel")) {
            return "console";
        }
        if ("run".equals(category) || "tool".equals(category)) {
            return "agent";
        }
        return "system";
    }

    private String source(String type, String category) {
        if ("run_event".equals(type)) {
            return "run_trace";
        }
        if ("trace_event".equals(type)) {
            return "trace_store";
        }
        if ("approval".equals(category)) {
            return "approval";
        }
        if ("changeset".equals(category)) {
            return "changeset";
        }
        return "console";
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private String firstNonBlank(Object... values) {
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

    private String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
