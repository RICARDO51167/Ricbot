package ricbot.domain.task;

public enum TaskStatus {
    PLANNED,
    READY,
    RUNNING,
    RECOVERING,
    WAITING_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
