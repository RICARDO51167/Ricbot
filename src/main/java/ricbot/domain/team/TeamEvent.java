package ricbot.domain.team;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TeamEvent(
        int schemaVersion,
        String eventId,
        String sessionId,
        String taskId,
        String type,
        String actor,
        String message,
        Map<String, Object> metadata,
        String createdAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String TEAM_STARTED = "TEAM_STARTED";
    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_PRODUCING = "TASK_PRODUCING";
    public static final String WORKER_RESULT_SUBMITTED = "WORKER_RESULT_SUBMITTED";
    public static final String VERIFICATION_STARTED = "VERIFICATION_STARTED";
    public static final String VERIFICATION_PASSED = "VERIFICATION_PASSED";
    public static final String VERIFICATION_REJECTED = "VERIFICATION_REJECTED";
    public static final String REVISION_REQUESTED = "REVISION_REQUESTED";
    public static final String HUMAN_NEEDED = "HUMAN_NEEDED";
    public static final String TASK_ABORTED = "TASK_ABORTED";
    public static final String TEAM_ARCHIVED = "TEAM_ARCHIVED";
    public static final String TEAM_RESUMED = "TEAM_RESUMED";
    public static final String ARTIFACT_RECORDED = "ARTIFACT_RECORDED";

    public TeamEvent {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported team event schema: " + schemaVersion);
        }
        eventId = eventId != null && !eventId.isBlank() ? eventId : newId();
        sessionId = clean(sessionId);
        taskId = clean(taskId);
        type = normalizeType(type);
        actor = actor != null && !actor.isBlank() ? actor.trim() : TeamRole.LEADER.name();
        message = clean(message);
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public TeamEvent(
            String eventId,
            String sessionId,
            String taskId,
            String type,
            String actor,
            String message,
            Map<String, Object> metadata,
            String createdAt
    ) {
        this(CURRENT_SCHEMA_VERSION, eventId, sessionId, taskId, type, actor, message, metadata, createdAt);
    }

    public static TeamEvent of(String sessionId, String taskId, TeamRole role, String type, String message) {
        return new TeamEvent(CURRENT_SCHEMA_VERSION, null, sessionId, taskId, type,
                role != null ? role.name() : TeamRole.LEADER.name(), message, Map.of(), null);
    }

    public static TeamEvent of(String sessionId, String taskId, TeamRole role, String type, String message, Map<String, Object> metadata) {
        return new TeamEvent(CURRENT_SCHEMA_VERSION, null, sessionId, taskId, type,
                role != null ? role.name() : TeamRole.LEADER.name(), message, metadata, null);
    }

    public String id() {
        return eventId;
    }

    public TeamRole role() {
        return parseRole(actor);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", schemaVersion);
        out.put("eventId", eventId);
        out.put("id", eventId);
        out.put("sessionId", sessionId);
        out.put("taskId", taskId);
        out.put("type", type);
        out.put("actor", actor);
        out.put("role", role().name());
        out.put("message", message);
        out.put("metadata", metadata);
        out.put("createdAt", createdAt);
        return out;
    }

    public static TeamEvent fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new TeamEvent(
                integer(raw.get("schemaVersion"), CURRENT_SCHEMA_VERSION),
                first(raw, "eventId", "id"),
                string(raw.get("sessionId")),
                string(raw.get("taskId")),
                string(raw.get("type")),
                !string(raw.get("actor")).isBlank() ? string(raw.get("actor")) : string(raw.get("role")),
                string(raw.get("message")),
                metadata(raw.get("metadata")),
                string(raw.get("createdAt"))
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

    private static TeamRole parseRole(Object raw) {
        try {
            return raw != null ? TeamRole.valueOf(String.valueOf(raw).replace('-', '_').toUpperCase(java.util.Locale.ROOT)) : TeamRole.LEADER;
        } catch (Exception e) {
            return TeamRole.LEADER;
        }
    }

    private static Map<String, Object> metadata(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return out;
    }

    private static String normalizeType(String raw) {
        String value = raw != null ? raw.trim() : "";
        if (value.isBlank()) {
            return "EVENT";
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "session_created", "team_started" -> TEAM_STARTED;
            case "task_created" -> TASK_CREATED;
            case "producing_started", "task_producing" -> TASK_PRODUCING;
            case "worker_result", "worker_result_submitted" -> WORKER_RESULT_SUBMITTED;
            case "verifying_started", "verification_started" -> VERIFICATION_STARTED;
            case "verification_pass", "verification_passed" -> VERIFICATION_PASSED;
            case "verification_reject", "verification_rejected" -> VERIFICATION_REJECTED;
            case "revision_requested" -> REVISION_REQUESTED;
            case "verification_needs_human", "human_needed" -> HUMAN_NEEDED;
            case "task_aborted" -> TASK_ABORTED;
            case "team_archived" -> TEAM_ARCHIVED;
            case "team_resumed" -> TEAM_RESUMED;
            case "artifact_recorded" -> ARTIFACT_RECORDED;
            default -> value.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String first(Map<?, ?> raw, String first, String second) {
        String value = string(raw.get(first));
        return !value.isBlank() ? value : string(raw.get(second));
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String newId() {
        return "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
