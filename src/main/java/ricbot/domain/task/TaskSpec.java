package ricbot.domain.task;

import ricbot.domain.task.TaskRole;

import java.util.List;
import java.util.Objects;

/** Immutable task declared by a leader plan and validated before scheduling. */
public record TaskSpec(
        String taskId,
        String parentRunId,
        String planId,
        int planRevision,
        String localTaskId,
        String activationId,
        int planOrder,
        int delegationDepth,
        TaskRole role,
        String goal,
        List<String> dependsOn,
        List<String> allowedTools,
        TaskWorkspaceMode workspaceMode,
        TaskFailurePolicy failurePolicy,
        boolean allowFailedDependencies,
        List<String> requiredCheckIds,
        List<String> acceptanceCriteria
) {
    public TaskSpec {
        taskId = required(taskId, "taskId");
        parentRunId = required(parentRunId, "parentRunId");
        planId = clean(planId);
        if (planRevision < 0 || planRevision > 2) throw new IllegalArgumentException("planRevision must be between 0 and 2");
        localTaskId = clean(localTaskId);
        activationId = required(activationId, "activationId");
        if (planOrder < 0) throw new IllegalArgumentException("planOrder must be non-negative");
        if (delegationDepth < 0) throw new IllegalArgumentException("delegationDepth must be non-negative");
        role = Objects.requireNonNull(role, "role");
        goal = required(goal, "goal");
        dependsOn = normalized(dependsOn);
        allowedTools = normalized(allowedTools);
        requiredCheckIds = normalized(requiredCheckIds);
        acceptanceCriteria = normalized(acceptanceCriteria);
        workspaceMode = Objects.requireNonNullElse(workspaceMode, TaskWorkspaceMode.SHARED_READ);
        failurePolicy = Objects.requireNonNullElse(failurePolicy, defaultPolicy(role));
    }

    public TaskSpec(String taskId, String parentRunId, String activationId, int planOrder, int delegationDepth,
                    TaskRole role, String goal, List<String> dependsOn, List<String> allowedTools,
                    TaskWorkspaceMode workspaceMode, TaskFailurePolicy failurePolicy,
                    boolean allowFailedDependencies) {
        this(taskId, parentRunId, "", 0, taskId, activationId, planOrder, delegationDepth, role, goal,
                dependsOn, allowedTools,
                workspaceMode, failurePolicy, allowFailedDependencies, List.of(), List.of());
    }

    public TaskSpec(String taskId, String parentRunId, String activationId, int planOrder, int delegationDepth,
                    TaskRole role, String goal, List<String> dependsOn, List<String> allowedTools,
                    TaskWorkspaceMode workspaceMode, TaskFailurePolicy failurePolicy,
                    boolean allowFailedDependencies, List<String> requiredCheckIds,
                    List<String> acceptanceCriteria) {
        this(taskId, parentRunId, "", 0, taskId, activationId, planOrder, delegationDepth, role, goal,
                dependsOn, allowedTools, workspaceMode, failurePolicy, allowFailedDependencies,
                requiredCheckIds, acceptanceCriteria);
    }

    private static TaskFailurePolicy defaultPolicy(TaskRole role) {
        return role == TaskRole.EXPLORER || role == TaskRole.REVIEWER
                ? TaskFailurePolicy.TOLERATE : TaskFailurePolicy.FAIL_FAST;
    }
    private static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
