package ricbot.domain.task;

/** Immutable input passed to a child-run worker. */
public record TaskWorkerRequest(String taskId, String parentRunId, TaskRole role, String goal) {
    public TaskWorkerRequest {
        taskId = clean(taskId);
        parentRunId = clean(parentRunId);
        role = role != null ? role : TaskRole.DEVELOPER;
        goal = clean(goal);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
