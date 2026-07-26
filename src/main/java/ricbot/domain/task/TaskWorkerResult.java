package ricbot.domain.task;

import java.util.List;

/** Immutable child-run result before the scheduler attaches its patch evidence. */
public record TaskWorkerResult(TaskWorkerStatus status, List<String> changedFiles, String summary,
                               List<String> implementationSteps, String errorMessage, long durationMillis,
                               String childRunId, String traceId, List<String> debugLines) {
    public TaskWorkerResult {
        status = status != null ? status : TaskWorkerStatus.NO_CHANGES;
        changedFiles = changedFiles != null ? List.copyOf(changedFiles) : List.of();
        summary = clean(summary);
        implementationSteps = implementationSteps != null ? List.copyOf(implementationSteps) : List.of();
        errorMessage = clean(errorMessage);
        durationMillis = Math.max(0, durationMillis);
        childRunId = clean(childRunId);
        traceId = clean(traceId);
        debugLines = debugLines != null ? List.copyOf(debugLines) : List.of();
    }
    public static TaskWorkerResult failed(String message, long durationMillis) {
        return new TaskWorkerResult(TaskWorkerStatus.FAILED, List.of(), "Task worker failed.", List.of(),
                message, durationMillis, "", "", List.of());
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
