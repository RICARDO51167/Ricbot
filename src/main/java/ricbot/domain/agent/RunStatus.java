package ricbot.domain.agent;

/** Durable lifecycle states for one agent runner invocation. */
public enum RunStatus {
    CREATED,
    MODEL_RUNNING,
    WAITING_TOOL,
    TOOL_RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
