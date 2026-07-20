package ricbot.domain.team;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TeamArtifact(
        String id,
        String taskId,
        String path,
        String summary,
        String kind,
        String createdAt
) {
    public TeamArtifact {
        id = id != null && !id.isBlank() ? id : newId();
        taskId = clean(taskId);
        path = clean(path);
        summary = clean(summary);
        kind = kind != null && !kind.isBlank() ? kind.trim() : "summary";
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public static TeamArtifact of(String taskId, String path, String summary) {
        return new TeamArtifact(null, taskId, path, summary, "summary", null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("taskId", taskId);
        out.put("path", path);
        out.put("summary", summary);
        out.put("kind", kind);
        out.put("createdAt", createdAt);
        return out;
    }

    public static TeamArtifact fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new TeamArtifact(
                string(raw.get("id")),
                string(raw.get("taskId")),
                string(raw.get("path")),
                string(raw.get("summary")),
                string(raw.get("kind")),
                string(raw.get("createdAt"))
        );
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String newId() {
        return "artifact_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
