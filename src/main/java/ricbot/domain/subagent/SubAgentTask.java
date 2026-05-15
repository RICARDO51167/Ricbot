package ricbot.domain.subagent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SubAgentTask(
        String id,
        SubAgentRole role,
        String goal,
        String inputContext,
        String expectedOutputSchema,
        List<String> relatedFiles,
        String createdAt,
        String status
) {
    public SubAgentTask {
        id = id != null && !id.isBlank() ? id : newId();
        role = role != null ? role : SubAgentRole.EXPLORER;
        goal = clean(goal);
        inputContext = clean(inputContext);
        expectedOutputSchema = clean(expectedOutputSchema);
        relatedFiles = relatedFiles != null ? List.copyOf(nonBlank(relatedFiles)) : List.of();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
        status = status != null && !status.isBlank() ? status : "CREATED";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("role", role.name());
        out.put("goal", goal);
        out.put("inputContext", inputContext);
        out.put("expectedOutputSchema", expectedOutputSchema);
        out.put("relatedFiles", relatedFiles);
        out.put("createdAt", createdAt);
        out.put("status", status);
        return out;
    }

    public static SubAgentTask fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new SubAgentTask(
                string(raw.get("id")),
                parseRole(raw.get("role")),
                string(raw.get("goal")),
                string(raw.get("inputContext")),
                string(raw.get("expectedOutputSchema")),
                stringList(raw.get("relatedFiles")),
                string(raw.get("createdAt")),
                string(raw.get("status"))
        );
    }

    private static SubAgentRole parseRole(Object raw) {
        try {
            return raw != null ? SubAgentRole.valueOf(String.valueOf(raw)) : SubAgentRole.EXPLORER;
        } catch (Exception e) {
            return SubAgentRole.EXPLORER;
        }
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !out.contains(value.trim())) {
                out.add(value.trim());
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String newId() {
        return "subtask_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
