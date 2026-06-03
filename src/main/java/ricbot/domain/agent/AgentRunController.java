package ricbot.domain.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class AgentRunController {
    private static final String MAX_TURNS_STOP_REASON = "max_turns";
    private static final String TIMEOUT_STOP_REASON = "timeout";

    private final int maxTurns;
    private final Clock clock;
    private final Instant deadline;
    private int currentTurn;
    private boolean cancelled;
    private String stopReason;

    private AgentRunController(int maxTurns, Clock clock, Instant deadline) {
        this.maxTurns = Math.max(0, maxTurns);
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.deadline = deadline;
    }

    public static AgentRunController withMaxTurns(int maxTurns) {
        return new AgentRunController(maxTurns, Clock.systemUTC(), null);
    }

    public static AgentRunController withMaxTurnsAndTimeout(int maxTurns, Duration timeout, Clock clock) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        Instant deadline = timeout != null ? Instant.now(effectiveClock).plus(timeout) : null;
        return new AgentRunController(maxTurns, effectiveClock, deadline);
    }

    public boolean canContinue() {
        if (cancelled) {
            return false;
        }
        if (timedOut()) {
            stop(TIMEOUT_STOP_REASON);
            return false;
        }
        if (currentTurn >= maxTurns) {
            stop(MAX_TURNS_STOP_REASON);
            return false;
        }
        return true;
    }

    public void recordTurn() {
        currentTurn++;
        if (currentTurn >= maxTurns) {
            stop(MAX_TURNS_STOP_REASON);
        }
    }

    public void cancel(String reason) {
        cancelled = true;
        String detail = clean(reason);
        stop(detail.isBlank() ? "cancelled" : "cancelled: " + detail);
    }

    public boolean cancelled() {
        return cancelled;
    }

    public Optional<String> stopReason() {
        return Optional.ofNullable(stopReason);
    }

    public boolean timedOut() {
        return deadline != null && !Instant.now(clock).isBefore(deadline);
    }

    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    public void stop(String reason) {
        String cleanReason = clean(reason);
        if (!cleanReason.isBlank()) {
            stopReason = cleanReason;
        }
    }

    public int currentTurn() {
        return currentTurn;
    }

    public int maxTurns() {
        return maxTurns;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
