package ricbot.domain.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkerExecutionResult(
        String taskId,
        String teamSessionId,
        TeamRole role,
        String goal,
        String workspacePath,
        String whiteboardSummary,
        List<String> relatedFiles,
        List<String> verifiedExperience,
        List<String> constraints,
        String summary,
        List<String> findings,
        List<String> risks,
        List<String> suggestedTests,
        List<TeamArtifact> artifacts,
        double confidence,
        String status,
        String createdAt
) {
    public WorkerExecutionResult {
        taskId = clean(taskId);
        teamSessionId = clean(teamSessionId);
        role = role != null ? role : TeamRole.EXPLORER;
        goal = clean(goal);
        workspacePath = clean(workspacePath);
        whiteboardSummary = clean(whiteboardSummary);
        relatedFiles = copy(relatedFiles);
        verifiedExperience = copy(verifiedExperience);
        constraints = copy(constraints);
        summary = clean(summary);
        findings = copy(findings);
        risks = copy(risks);
        suggestedTests = copy(suggestedTests);
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        confidence = Math.max(0d, Math.min(1d, confidence));
        status = !clean(status).isBlank() ? status.trim() : "COMPLETED";
        createdAt = !clean(createdAt).isBlank() ? createdAt : Instant.now().toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("teamSessionId", teamSessionId);
        out.put("role", role.name());
        out.put("goal", goal);
        out.put("workspacePath", workspacePath);
        out.put("whiteboardSummary", whiteboardSummary);
        out.put("relatedFiles", relatedFiles);
        out.put("verifiedExperience", verifiedExperience);
        out.put("constraints", constraints);
        out.put("summary", summary);
        out.put("findings", findings);
        out.put("risks", risks);
        out.put("suggestedTests", suggestedTests);
        out.put("artifacts", artifacts.stream().map(TeamArtifact::toMap).toList());
        out.put("confidence", confidence);
        out.put("status", status);
        out.put("createdAt", createdAt);
        return out;
    }

    public static WorkerExecutionResult fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new WorkerExecutionResult(
                string(raw.get("taskId")),
                string(raw.get("teamSessionId")),
                parseRole(raw.get("role")),
                string(raw.get("goal")),
                string(raw.get("workspacePath")),
                string(raw.get("whiteboardSummary")),
                stringList(raw.get("relatedFiles")),
                stringList(raw.get("verifiedExperience")),
                stringList(raw.get("constraints")),
                string(raw.get("summary")),
                stringList(raw.get("findings")),
                stringList(raw.get("risks")),
                stringList(raw.get("suggestedTests")),
                artifacts(raw.get("artifacts")),
                doubleValue(raw.get("confidence")),
                string(raw.get("status")),
                string(raw.get("createdAt"))
        );
    }

    private static TeamRole parseRole(Object raw) {
        try {
            return raw != null ? TeamRole.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : TeamRole.EXPLORER;
        } catch (Exception e) {
            return TeamRole.EXPLORER;
        }
    }

    private static List<TeamArtifact> artifacts(Object raw) {
        List<TeamArtifact> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    TeamArtifact artifact = TeamArtifact.fromMap(map);
                    if (artifact != null) {
                        out.add(artifact);
                    }
                }
            }
        }
        return out;
    }

    private static List<String> copy(List<String> values) {
        return values != null ? values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList() : List.of();
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

    private static double doubleValue(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return raw != null ? Double.parseDouble(String.valueOf(raw)) : 0d;
        } catch (Exception e) {
            return 0d;
        }
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
