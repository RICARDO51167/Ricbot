package ricbot.domain.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TeamTaskReport(
        String teamSessionId,
        String taskId,
        String title,
        TeamTaskStatus status,
        TeamTaskHealth health,
        int totalSteps,
        int completedSteps,
        int failedSteps,
        int pendingSteps,
        int linkedAuditRecords,
        String latestEvent,
        String latestChangeSet,
        String latestVerifier,
        long durationMillis,
        List<String> warnings,
        List<String> suggestedNextActions,
        Map<String, Object> compactSummary
) {
    public TeamTaskReport {
        teamSessionId = clean(teamSessionId);
        taskId = clean(taskId);
        title = clean(title);
        status = status != null ? status : TeamTaskStatus.UNKNOWN;
        health = health != null ? health : TeamTaskHealth.UNKNOWN;
        totalSteps = Math.max(0, totalSteps);
        completedSteps = Math.max(0, completedSteps);
        failedSteps = Math.max(0, failedSteps);
        pendingSteps = Math.max(0, pendingSteps);
        linkedAuditRecords = Math.max(0, linkedAuditRecords);
        latestEvent = clean(latestEvent);
        latestChangeSet = clean(latestChangeSet);
        latestVerifier = clean(latestVerifier);
        durationMillis = Math.max(0L, durationMillis);
        warnings = copy(warnings);
        suggestedNextActions = copy(suggestedNextActions);
        compactSummary = compactSummary != null ? Map.copyOf(compactSummary) : Map.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamSessionId", teamSessionId);
        out.put("taskId", taskId);
        out.put("title", title);
        out.put("status", status.name());
        out.put("health", health.name());
        out.put("totalSteps", totalSteps);
        out.put("completedSteps", completedSteps);
        out.put("failedSteps", failedSteps);
        out.put("pendingSteps", pendingSteps);
        out.put("linkedAuditRecords", linkedAuditRecords);
        out.put("latestEvent", latestEvent);
        out.put("latestChangeSet", latestChangeSet);
        out.put("latestVerifier", latestVerifier);
        out.put("durationMillis", durationMillis);
        out.put("warnings", warnings);
        out.put("suggestedNextActions", suggestedNextActions);
        out.put("compactSummary", compactSummary);
        return out;
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = clean(value);
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
