package ricbot.infra.persistence;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, append-only business fact independent of a particular Agent Run state machine. */
public record RuntimeFactEvent(
        int schemaVersion,
        String eventId,
        long sequence,
        String sessionKey,
        String type,
        String actor,
        String message,
        Map<String, Object> details,
        Instant occurredAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RuntimeFactEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported runtime fact schema: " + schemaVersion);
        }
        eventId = required(eventId, "eventId");
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        sessionKey = required(sessionKey, "sessionKey");
        type = required(type, "type");
        actor = clean(actor).isBlank() ? "system" : clean(actor);
        message = clean(message);
        details = details != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(details))
                : Map.of();
        occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
