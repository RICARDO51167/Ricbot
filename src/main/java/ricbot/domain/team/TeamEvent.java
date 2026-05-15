package ricbot.domain.team;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TeamEvent(
        String id,
        String sessionId,
        String taskId,
        TeamRole role,
        String type,
        String message,
        String createdAt
) {
    public TeamEvent {
        id = id != null && !id.isBlank() ? id : newId();
        sessionId = clean(sessionId);
        taskId = clean(taskId);
        role = role != null ? role : TeamRole.LEADER;
        type = type != null && !type.isBlank() ? type.trim() : "event";
        message = clean(message);
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public static TeamEvent of(String sessionId, String taskId, TeamRole role, String type, String message) {
        return new TeamEvent(null, sessionId, taskId, role, type, message, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("sessionId", sessionId);
        out.put("taskId", taskId);
        out.put("role", role.name());
        out.put("type", type);
        out.put("message", message);
        out.put("createdAt", createdAt);
        return out;
    }

    public static TeamEvent fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new TeamEvent(
                string(raw.get("id")),
                string(raw.get("sessionId")),
                string(raw.get("taskId")),
                parseRole(raw.get("role")),
                string(raw.get("type")),
                string(raw.get("message")),
                string(raw.get("createdAt"))
        );
    }

    private static TeamRole parseRole(Object raw) {
        try {
            return raw != null ? TeamRole.valueOf(String.valueOf(raw)) : TeamRole.LEADER;
        } catch (Exception e) {
            return TeamRole.LEADER;
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String newId() {
        return "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
