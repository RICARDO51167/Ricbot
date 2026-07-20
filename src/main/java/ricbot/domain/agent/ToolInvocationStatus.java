package ricbot.domain.agent;

/** Persisted status of a single tool invocation. */
public enum ToolInvocationStatus {
    PREPARED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    public boolean finished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
