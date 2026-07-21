package ricbot.domain.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TraceEvent(
        int schemaVersion,
        String traceId,
        String eventId,
        String parentEventId,
        String sessionId,
        String teamSessionId,
        String changeSetId,
        String approvalRequestId,
        TraceEventType type,
        String actor,
        String message,
        Map<String, Object> payload,
        String createdAt,
        Long durationMs
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TraceEvent {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported trace event schema: " + schemaVersion);
        }
        traceId = clean(traceId);
        eventId = eventId != null && !eventId.isBlank() ? eventId : newId();
        parentEventId = clean(parentEventId);
        sessionId = clean(sessionId);
        teamSessionId = clean(teamSessionId);
        changeSetId = clean(changeSetId);
        approvalRequestId = clean(approvalRequestId);
        type = type != null ? type : TraceEventType.TEAM_EVENT;
        actor = !clean(actor).isBlank() ? actor.trim() : "system";
        message = clean(message);
        payload = payload != null ? sanitizeMap(payload) : Map.of();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
        durationMs = durationMs != null && durationMs >= 0 ? durationMs : null;
    }

    public TraceEvent(
            String traceId,
            String eventId,
            String parentEventId,
            String sessionId,
            String teamSessionId,
            String changeSetId,
            String approvalRequestId,
            TraceEventType type,
            String actor,
            String message,
            Map<String, Object> payload,
            String createdAt,
            Long durationMs
    ) {
        this(CURRENT_SCHEMA_VERSION, traceId, eventId, parentEventId, sessionId, teamSessionId,
                changeSetId, approvalRequestId, type, actor, message, payload, createdAt, durationMs);
    }

    public static TraceEvent of(
            String traceId,
            String sessionId,
            TraceEventType type,
            String actor,
            String message,
            Map<String, Object> payload
    ) {
        return new TraceEvent(CURRENT_SCHEMA_VERSION, traceId, null, "", sessionId, "", "", "",
                type, actor, message, payload, null, null);
    }

    public TraceEvent withTraceId(String nextTraceId) {
        return new TraceEvent(schemaVersion, nextTraceId, eventId, parentEventId, sessionId, teamSessionId, changeSetId,
                approvalRequestId, type, actor, message, payload, createdAt, durationMs);
    }

    public TraceEvent withTeamSessionId(String nextTeamSessionId) {
        return new TraceEvent(schemaVersion, traceId, eventId, parentEventId, sessionId, nextTeamSessionId, changeSetId,
                approvalRequestId, type, actor, message, payload, createdAt, durationMs);
    }

    public TraceEvent withChangeSetId(String nextChangeSetId) {
        return new TraceEvent(schemaVersion, traceId, eventId, parentEventId, sessionId, teamSessionId, nextChangeSetId,
                approvalRequestId, type, actor, message, payload, createdAt, durationMs);
    }

    public TraceEvent withApprovalRequestId(String nextApprovalRequestId) {
        return new TraceEvent(schemaVersion, traceId, eventId, parentEventId, sessionId, teamSessionId, changeSetId,
                nextApprovalRequestId, type, actor, message, payload, createdAt, durationMs);
    }

    public TraceEvent withDurationMs(long nextDurationMs) {
        return new TraceEvent(schemaVersion, traceId, eventId, parentEventId, sessionId, teamSessionId, changeSetId,
                approvalRequestId, type, actor, message, payload, createdAt, nextDurationMs);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", schemaVersion);
        out.put("traceId", traceId);
        out.put("eventId", eventId);
        out.put("parentEventId", parentEventId);
        out.put("sessionId", sessionId);
        out.put("teamSessionId", teamSessionId);
        out.put("changeSetId", changeSetId);
        out.put("approvalRequestId", approvalRequestId);
        out.put("type", type.name());
        out.put("actor", actor);
        out.put("message", message);
        out.put("payload", payload);
        out.put("createdAt", createdAt);
        out.put("durationMs", durationMs);
        return out;
    }

    public static TraceEvent fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new TraceEvent(
                integer(raw.get("schemaVersion"), CURRENT_SCHEMA_VERSION),
                string(raw.get("traceId")),
                string(raw.get("eventId")),
                string(raw.get("parentEventId")),
                string(raw.get("sessionId")),
                string(raw.get("teamSessionId")),
                string(raw.get("changeSetId")),
                string(raw.get("approvalRequestId")),
                parseType(raw.get("type")),
                string(raw.get("actor")),
                string(raw.get("message")),
                map(raw.get("payload")),
                string(raw.get("createdAt")),
                longValue(raw.get("durationMs"))
        );
    }

    private static int integer(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        try {
            return raw != null ? Integer.parseInt(String.valueOf(raw)) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static TraceEventType parseType(Object raw) {
        try {
            return raw != null ? TraceEventType.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : TraceEventType.TEAM_EVENT;
        } catch (Exception e) {
            return TraceEventType.TEAM_EVENT;
        }
    }

    private static Long longValue(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return raw != null && !String.valueOf(raw).isBlank() ? Long.parseLong(String.valueOf(raw)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return sanitizeMap(map);
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
        }
        return out;
    }

    private static Object sanitizeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object item : iterable) {
                out.add(sanitizeValue(item));
            }
            return out;
        }
        return String.valueOf(value);
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String newId() {
        return "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
