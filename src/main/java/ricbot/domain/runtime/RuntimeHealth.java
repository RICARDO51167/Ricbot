package ricbot.domain.runtime;

import java.time.Instant;

/** Read-only health snapshot for the local durable scheduler. */
public record RuntimeHealth(boolean schedulerRunning,
                            Instant lastSuccessfulTick,
                            Instant lastFailedTick,
                            long consecutiveFailures,
                            String lastFailure,
                            long currentPollDelayMillis,
                            int lastBatchSize) {
    public RuntimeHealth {
        lastFailure = lastFailure != null ? lastFailure : "";
        currentPollDelayMillis = Math.max(0L, currentPollDelayMillis);
        lastBatchSize = Math.max(0, lastBatchSize);
    }

    public static RuntimeHealth unavailable() {
        return new RuntimeHealth(false, null, null, 0, "runtime health is unavailable", 0, 0);
    }
}
