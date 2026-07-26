package ricbot.domain.task;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured worker result. Large outputs belong in artifact paths, not the parent prompt. */
public record TaskResult(
        int schemaVersion,
        String taskId,
        String parentRunId,
        String childRunId,
        int attempt,
        TaskStatus status,
        int planOrder,
        String summary,
        Map<String, String> artifacts,
        String patch,
        List<String> changedFiles,
        List<String> testEvidence,
        String error,
        Instant completedAt
) {
    public TaskResult {
        if (schemaVersion != 2) throw new IllegalArgumentException("unsupported task result schema");
        taskId = required(taskId, "taskId");
        parentRunId = required(parentRunId, "parentRunId");
        childRunId = childRunId != null ? childRunId.trim() : "";
        if (attempt < 1) attempt = 1;
        status = Objects.requireNonNull(status, "status");
        if (!status.terminal() && status != TaskStatus.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("task result status must be settled");
        }
        summary = summary != null ? summary : "";
        artifacts = Collections.unmodifiableMap(new LinkedHashMap<>(artifacts != null ? artifacts : Map.of()));
        patch = patch != null ? patch : "";
        changedFiles = List.copyOf(changedFiles != null ? changedFiles : List.of());
        testEvidence = List.copyOf(testEvidence != null ? testEvidence : List.of());
        error = error != null ? error : "";
        completedAt = Objects.requireNonNullElseGet(completedAt, Instant::now);
    }

    public static TaskResult failed(TaskRecord record, Throwable failure) {
        String message = failure != null && failure.getMessage() != null ? failure.getMessage()
                : failure != null ? failure.getClass().getSimpleName() : "task failed";
        return new TaskResult(2, record.spec().taskId(), record.spec().parentRunId(), record.childRunId(), record.attempt(),
                TaskStatus.FAILED, record.spec().planOrder(), "", Map.of(), "", List.of(), List.of(), message, Instant.now());
    }

    public static TaskResult cancelled(TaskRecord record, String reason) {
        return new TaskResult(2, record.spec().taskId(), record.spec().parentRunId(), record.childRunId(), record.attempt(),
                TaskStatus.CANCELLED, record.spec().planOrder(), "", Map.of(), "", List.of(), List.of(), reason, Instant.now());
    }

    public static TaskResult skipped(TaskRecord record, String reason) {
        return new TaskResult(2, record.spec().taskId(), record.spec().parentRunId(), record.childRunId(), record.attempt(),
                TaskStatus.SKIPPED, record.spec().planOrder(), "", Map.of(), "", List.of(), List.of(), reason, Instant.now());
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    public TaskResult(int schemaVersion, String taskId, String parentRunId, String childRunId, TaskStatus status,
                      int planOrder, String summary, Map<String, String> artifacts, String patch,
                      List<String> changedFiles, List<String> testEvidence, String error, Instant completedAt) {
        this(schemaVersion, taskId, parentRunId, childRunId, 1, status, planOrder, summary, artifacts, patch,
                changedFiles, testEvidence, error, completedAt);
    }
}
