package ricbot.domain.agent.dto;

/** Durable graph ownership attached to one side-effect protocol record. */
public record SideEffectExecutionIdentity(String runId, String sessionId, String taskId, String activationId) {
    public SideEffectExecutionIdentity {
        runId = clean(runId);
        sessionId = clean(sessionId);
        taskId = clean(taskId);
        activationId = clean(activationId);
    }

    public static SideEffectExecutionIdentity session(String sessionId) {
        return new SideEffectExecutionIdentity("", sessionId, "", "");
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
