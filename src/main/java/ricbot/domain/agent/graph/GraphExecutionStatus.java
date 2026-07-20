package ricbot.domain.agent.graph;

public enum GraphExecutionStatus {
    READY,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
