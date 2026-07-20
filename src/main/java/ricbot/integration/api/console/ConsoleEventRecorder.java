package ricbot.integration.api.console;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ConsoleEventRecorder {
    private final ConsoleEventBus bus;

    public ConsoleEventRecorder(ConsoleEventBus bus) {
        this.bus = bus;
    }

    public ConsoleEvent recordAction(
            String sessionId,
            String runId,
            String action,
            String targetType,
            String targetId,
            String result,
            String actor,
            String summary,
            Map<String, Object> payload
    ) {
        String name = normalizeName(action);
        Map<String, Object> safePayload = new LinkedHashMap<>(payload != null ? payload : Map.of());
        safePayload.putIfAbsent("action", action != null ? action : "");
        safePayload.putIfAbsent("targetType", targetType != null ? targetType : "");
        safePayload.putIfAbsent("targetId", targetId != null ? targetId : "");
        safePayload.putIfAbsent("result", result != null ? result : "");
        String category = category(targetType, name, result);
        ConsoleEvent event = new ConsoleEvent(
                stableId(name, sessionId, runId, targetId),
                sessionId,
                runId,
                type(category),
                name,
                category,
                status(result, name),
                Instant.now().toString(),
                title(name),
                summary != null ? summary : "",
                actor != null && !actor.isBlank() ? actor : "console",
                "console_event_store",
                safePayload
        );
        if (bus != null) {
            bus.publish(event);
        }
        return event;
    }

    public ConsoleEvent recordRunEvent(String sessionId, String runId, String name, String status, String summary, Map<String, Object> payload) {
        ConsoleEvent event = new ConsoleEvent(
                stableId(name, sessionId, runId, ""),
                sessionId,
                runId,
                "run_event",
                normalizeName(name),
                category("RUN", name, status),
                status(status, name),
                Instant.now().toString(),
                title(name),
                summary != null ? summary : "",
                "agent",
                "console_event_store",
                payload != null ? payload : Map.of()
        );
        if (bus != null) {
            bus.publish(event);
        }
        return event;
    }

    private static String stableId(String name, String sessionId, String runId, String targetId) {
        String basis = String.join(":",
                clean(sessionId),
                clean(runId),
                clean(targetId),
                clean(name),
                UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        return "console_event_" + basis.replaceAll("[^A-Za-z0-9._:-]", "-");
    }

    private static String normalizeName(String value) {
        String text = clean(value);
        return text.isBlank() ? "system_event" : text.replace('.', '_').replace('-', '_');
    }

    private static String category(String targetType, String name, String result) {
        String text = (clean(targetType) + " " + clean(name) + " " + clean(result)).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("model_not_configured") || text.contains("blank_input")) {
            return "system";
        }
        if (text.contains("error") || text.contains("failed") || text.contains("not_found") || text.contains("conflict")) {
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
        if (text.contains("run") || text.contains("model") || text.contains("blank_input")) {
            return "run";
        }
        return "system";
    }

    private static String status(String result, String name) {
        String text = (clean(result) + " " + clean(name)).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("failed") || text.contains("error") || text.contains("not_found") || text.contains("conflict")) {
            return "ERROR";
        }
        if (text.contains("pending") || text.contains("queued") || text.contains("warning")) {
            return "WARN";
        }
        if (text.contains("cancel")) {
            return "CANCELLED";
        }
        if (text.contains("success") || text.contains("approved") || text.contains("finished")) {
            return "SUCCESS";
        }
        return "INFO";
    }

    private static String type(String category) {
        return switch (category) {
            case "run" -> "run_event";
            case "tool" -> "tool_event";
            case "approval" -> "approval_event";
            case "changeset" -> "changeset_event";
            case "error" -> "system_event";
            default -> "system_event";
        };
    }

    private static String title(String name) {
        String normalized = normalizeName(name);
        return normalized.replace('_', ' ');
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
