package ricbot.domain.task;

import java.util.List;

/** Immutable input passed to a child-run worker. */
public record TaskWorkerRequest(String taskId, String parentRunId, String childRunId, int attempt,
                                TaskRole role, String goal, TaskWorkspaceMode workspaceMode,
                                List<String> allowedTools, int siblingCount) {
    public TaskWorkerRequest {
        taskId = clean(taskId);
        parentRunId = clean(parentRunId);
        childRunId = clean(childRunId);
        if (attempt < 1) attempt = 1;
        role = role != null ? role : TaskRole.DEVELOPER;
        goal = clean(goal);
        workspaceMode = workspaceMode != null ? workspaceMode
                : role == TaskRole.DEVELOPER || role == TaskRole.TESTER
                ? TaskWorkspaceMode.ISOLATED_WORKTREE : TaskWorkspaceMode.SHARED_READ;
        allowedTools = allowedTools != null ? allowedTools.stream().map(TaskWorkerRequest::clean)
                .filter(value -> !value.isBlank()).distinct().toList() : List.of();
        siblingCount = Math.max(1, siblingCount);
    }
    public TaskWorkerRequest(String taskId, String parentRunId, String childRunId, int attempt,
                             TaskRole role, String goal, TaskWorkspaceMode workspaceMode,
                             List<String> allowedTools) {
        this(taskId, parentRunId, childRunId, attempt, role, goal, workspaceMode, allowedTools, 1);
    }
    public TaskWorkerRequest(String taskId, String parentRunId, String childRunId, int attempt,
                             TaskRole role, String goal) {
        this(taskId, parentRunId, childRunId, attempt, role, goal, null, List.of(), 1);
    }
    public TaskWorkerRequest(String taskId, String parentRunId, TaskRole role, String goal) {
        this(taskId, parentRunId, "", 1, role, goal, null, List.of(), 1);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
