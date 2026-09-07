package ricbot.domain.runtime;

import java.time.Instant;
import java.util.Map;

/** Persisted runtime fact. Ephemeral streaming observations use a different application channel. */
public record RuntimeEvent(long sequence, String eventId, String runId, long commitSequence,
                           String type, Instant occurredAt, Map<String, Object> payload) {
    public RuntimeEvent { payload = Map.copyOf(payload != null ? payload : Map.of()); }
}
