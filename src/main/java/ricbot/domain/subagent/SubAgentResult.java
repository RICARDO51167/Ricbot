package ricbot.domain.subagent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SubAgentResult(
        String taskId,
        SubAgentRole role,
        String summary,
        List<String> findings,
        List<String> risks,
        List<String> suggestedTests,
        List<String> relatedFiles,
        double confidence,
        String createdAt
) {
    public SubAgentResult {
        taskId = taskId != null && !taskId.isBlank() ? taskId : "subtask_unknown";
        role = role != null ? role : SubAgentRole.EXPLORER;
        summary = clean(summary);
        findings = findings != null ? List.copyOf(nonBlank(findings)) : List.of();
        risks = risks != null ? List.copyOf(nonBlank(risks)) : List.of();
        suggestedTests = suggestedTests != null ? List.copyOf(nonBlank(suggestedTests)) : List.of();
        relatedFiles = relatedFiles != null ? List.copyOf(nonBlank(relatedFiles)) : List.of();
        confidence = Math.max(0d, Math.min(1d, confidence));
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("role", role.name());
        out.put("summary", summary);
        out.put("findings", findings);
        out.put("risks", risks);
        out.put("suggestedTests", suggestedTests);
        out.put("relatedFiles", relatedFiles);
        out.put("confidence", confidence);
        out.put("createdAt", createdAt);
        return out;
    }

    public static SubAgentResult fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new SubAgentResult(
                string(raw.get("taskId")),
                parseRole(raw.get("role")),
                string(raw.get("summary")),
                stringList(raw.get("findings")),
                stringList(raw.get("risks")),
                stringList(raw.get("suggestedTests")),
                stringList(raw.get("relatedFiles")),
                number(raw.get("confidence"), 0.5d),
                string(raw.get("createdAt"))
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

    private static double number(Object raw, double fallback) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return raw != null ? Double.parseDouble(String.valueOf(raw)) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
