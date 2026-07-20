package ricbot.domain.workspace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record WorkspaceSession(
        String id,
        WorkspaceBackendType type,
        String baseWorkspace,
        String workspacePath,
        String branchName,
        String goal,
        WorkspaceSessionStatus status,
        String createdAt,
        String updatedAt,
        Map<String, Object> metadata
) {
    public WorkspaceSession {
        id = safeId(id);
        type = type != null ? type : WorkspaceBackendType.LOCAL;
        baseWorkspace = clean(baseWorkspace);
        workspacePath = clean(workspacePath);
        branchName = clean(branchName);
        goal = clean(goal);
        status = status != null ? status : WorkspaceSessionStatus.ACTIVE;
        String now = Instant.now().toString();
        createdAt = !clean(createdAt).isBlank() ? createdAt : now;
        updatedAt = !clean(updatedAt).isBlank() ? updatedAt : now;
        metadata = metadata != null ? sanitizeMap(metadata) : Map.of();
    }

    public WorkspaceSession withStatus(WorkspaceSessionStatus nextStatus) {
        return new WorkspaceSession(id, type, baseWorkspace, workspacePath, branchName, goal,
                nextStatus, createdAt, Instant.now().toString(), metadata);
    }

    public WorkspaceSession withMetadata(Map<String, Object> nextMetadata) {
        return new WorkspaceSession(id, type, baseWorkspace, workspacePath, branchName, goal,
                status, createdAt, Instant.now().toString(), nextMetadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type.name());
        out.put("baseWorkspace", baseWorkspace);
        out.put("workspacePath", workspacePath);
        out.put("branchName", branchName);
        out.put("goal", goal);
        out.put("status", status.name());
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        out.put("metadata", metadata);
        return out;
    }

    public static WorkspaceSession fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new WorkspaceSession(
                string(raw.get("id")),
                parseType(raw.get("type")),
                string(raw.get("baseWorkspace")),
                string(raw.get("workspacePath")),
                string(raw.get("branchName")),
                string(raw.get("goal")),
                parseStatus(raw.get("status")),
                string(raw.get("createdAt")),
                string(raw.get("updatedAt")),
                map(raw.get("metadata"))
        );
    }

    public static String newId() {
        return "workspace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static WorkspaceBackendType parseType(Object raw) {
        try {
            return raw != null ? WorkspaceBackendType.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : WorkspaceBackendType.LOCAL;
        } catch (Exception e) {
            return WorkspaceBackendType.LOCAL;
        }
    }

    private static WorkspaceSessionStatus parseStatus(Object raw) {
        try {
            return raw != null ? WorkspaceSessionStatus.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : WorkspaceSessionStatus.ACTIVE;
        } catch (Exception e) {
            return WorkspaceSessionStatus.ACTIVE;
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

    private static String safeId(String value) {
        String id = clean(value).replaceAll("[^A-Za-z0-9._-]+", "_");
        while (id.contains("..")) {
            id = id.replace("..", "_");
        }
        if (id.isBlank() || id.equals(".") || id.startsWith(".")) {
            return newId();
        }
        return id;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
