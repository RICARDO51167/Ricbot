package ricbot.domain.team;

import java.time.Instant;
import java.util.List;

public record TeamWorkerResult(
        TeamWorkerStatus status,
        List<String> changedFiles,
        String summary,
        List<String> implementationSteps,
        String errorMessage,
        long durationMillis,
        String rawAgentResultId,
        String traceId
) {
    public TeamWorkerResult {
        status = status != null ? status : TeamWorkerStatus.NO_CHANGES;
        changedFiles = changedFiles != null ? List.copyOf(changedFiles) : List.of();
        summary = summary != null ? summary.trim() : "";
        implementationSteps = implementationSteps != null ? List.copyOf(implementationSteps) : List.of();
        errorMessage = errorMessage != null ? errorMessage.trim() : "";
        durationMillis = Math.max(0L, durationMillis);
        rawAgentResultId = rawAgentResultId != null ? rawAgentResultId.trim() : "";
        traceId = traceId != null ? traceId.trim() : "";
    }

    public static TeamWorkerResult failed(String message, long durationMillis) {
        return new TeamWorkerResult(TeamWorkerStatus.FAILED, List.of(),
                "Team worker failed.", List.of(), message, durationMillis, "", "");
    }

    public WorkerExecutionResult toWorkerExecutionResult(TeamTask task, String workspacePath, String whiteboardSummary) {
        TeamTask safeTask = task != null
                ? task
                : new TeamTask("", "", TeamRole.DEVELOPER, "", TeamTaskState.CREATED, "", List.of(), null, "", null, null);
        List<String> findings = changedFiles.isEmpty()
                ? List.of(status == TeamWorkerStatus.FAILED ? "Worker failed: " + errorMessage : "No user changes produced.")
                : List.of("Changed files: " + String.join(", ", changedFiles));
        List<String> risks = status == TeamWorkerStatus.FAILED
                ? List.of(errorMessage.isBlank() ? "worker failed" : errorMessage)
                : List.of();
        return new WorkerExecutionResult(
                safeTask.id(),
                safeTask.sessionId(),
                safeTask.role(),
                safeTask.goal(),
                workspacePath,
                whiteboardSummary,
                changedFiles,
                List.of(),
                List.of("mode=team-worker"),
                !summary.isBlank() ? summary : status.name(),
                findings,
                risks,
                List.of(),
                List.of(),
                List.of("status=" + status.name(), "durationMillis=" + durationMillis,
                        rawAgentResultId.isBlank() ? "rawAgentResultId=none" : "rawAgentResultId=" + rawAgentResultId),
                implementationSteps,
                List.of(),
                nextActions(safeTask),
                changedFiles.isEmpty() ? "" : "Run /workspace diff " + safeTask.id() + " and /change create.",
                status == TeamWorkerStatus.FAILED ? 0.1d : status == TeamWorkerStatus.APPLIED ? 0.78d : 0.45d,
                status.name(),
                Instant.now().toString()
        );
    }

    private List<String> nextActions(TeamTask task) {
        return switch (status) {
            case APPLIED -> List.of("/workspace diff " + task.id(), "/change create " + task.id(), "/team run-verifier " + task.id());
            case NO_CHANGES -> List.of("Review worker summary and rerun with a more specific task.");
            case FAILED -> List.of("Inspect worker error and rerun after fixing the cause.");
        };
    }
}
