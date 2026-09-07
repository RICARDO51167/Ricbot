package ricbot.domain.runtime;

public enum RunStatus {
    READY, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
