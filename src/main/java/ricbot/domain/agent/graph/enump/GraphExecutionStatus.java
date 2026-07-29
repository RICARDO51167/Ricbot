package ricbot.domain.agent.graph.enump;

public enum GraphExecutionStatus {
    READY,
    RETRY_WAIT,
    RUNNING,
    PAUSED,
    WAITING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
