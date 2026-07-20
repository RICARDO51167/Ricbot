package ricbot.domain.agent;

/** Signals that a required durable transition could not be committed. */
public final class RunJournalException extends IllegalStateException {
    public RunJournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
