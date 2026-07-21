package ricbot.domain.team;

import java.util.List;

public record WorkerExecutionInput(
        String taskId,
        String teamSessionId,
        TeamRole role,
        String goal,
        String workspacePath,
        String whiteboardSummary,
        List<String> relatedFiles,
        List<String> constraints,
        String summary,
        List<String> findings,
        List<String> risks,
        List<String> suggestedTests,
        List<TeamArtifact> artifacts,
        double confidence,
        String status
) {
    public WorkerExecutionInput {
        taskId = clean(taskId);
        teamSessionId = clean(teamSessionId);
        role = role != null ? role : TeamRole.EXPLORER;
        goal = clean(goal);
        workspacePath = clean(workspacePath);
        whiteboardSummary = clean(whiteboardSummary);
        relatedFiles = copy(relatedFiles);
        constraints = copy(constraints);
        summary = clean(summary);
        findings = copy(findings);
        risks = copy(risks);
        suggestedTests = copy(suggestedTests);
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        confidence = Math.max(0d, Math.min(1d, confidence));
        status = clean(status);
    }

    public static WorkerExecutionInput ofTask(TeamTask task, String workspacePath, String whiteboardSummary) {
        return new WorkerExecutionInput(
                task != null ? task.id() : "",
                task != null ? task.sessionId() : "",
                task != null ? task.role() : TeamRole.EXPLORER,
                task != null ? task.goal() : "",
                workspacePath,
                whiteboardSummary,
                List.of(),
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0d,
                ""
        );
    }

    private static List<String> copy(List<String> values) {
        return values != null ? values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList() : List.of();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
