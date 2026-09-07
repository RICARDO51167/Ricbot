package ricbot.domain.runtime;

/** Best-effort cancellation boundary invoked only after CancelRequested is durable. */
@FunctionalInterface
public interface CancellationPort {
    void cancel(String runId);
}
