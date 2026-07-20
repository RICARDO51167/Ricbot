package ricbot.domain.agent;

/**
 * Stable phases at which an agent run can be recovered after an interruption.
 */
public enum RunCheckpointPhase {
    MODEL_RESPONSE_RECEIVED,
    TOOLS_COMPLETED;

    static RunCheckpointPhase from(Object raw) {
        if (raw == null) {
            return MODEL_RESPONSE_RECEIVED;
        }
        try {
            return valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MODEL_RESPONSE_RECEIVED;
        }
    }
}
