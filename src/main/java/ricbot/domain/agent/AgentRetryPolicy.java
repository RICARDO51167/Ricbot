package ricbot.domain.agent;

import java.util.Optional;

/**
 * Minimal retry policy for agent-level retry/bump decisions.
 */
public final class AgentRetryPolicy {

    private final int maxRetries;
    private int retryCount;
    private String lastRetryReason;

    private AgentRetryPolicy(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public static AgentRetryPolicy none() {
        return new AgentRetryPolicy(0);
    }

    public static AgentRetryPolicy maxRetries(int maxRetries) {
        return new AgentRetryPolicy(maxRetries);
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public boolean recordFailure(String reason) {
        lastRetryReason = reason;
        if (!canRetry()) {
            return false;
        }
        retryCount++;
        return true;
    }

    public int retryCount() {
        return retryCount;
    }

    public Optional<String> lastRetryReason() {
        return Optional.ofNullable(lastRetryReason);
    }
}
