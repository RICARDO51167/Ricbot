package ricbot.domain.agent.graph.exceptionp;

/** A node failure that must be persisted as failed without scheduling another attempt. */
public final class GraphNonRetryableException extends RuntimeException {
    public GraphNonRetryableException(String message, Throwable cause) { super(message, cause); }
}
