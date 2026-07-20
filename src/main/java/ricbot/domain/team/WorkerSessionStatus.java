package ricbot.domain.team;

public enum WorkerSessionStatus {
    CREATED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
}
