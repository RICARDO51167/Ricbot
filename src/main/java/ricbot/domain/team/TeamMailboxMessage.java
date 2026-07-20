package ricbot.domain.team;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TeamMailboxMessage(
        int schemaVersion,
        String messageId,
        String teamSessionId,
        long sequence,
        String fromWorkerId,
        String toWorkerId,
        TeamMessageType type,
        String correlationId,
        Map<String, Object> payload,
        Instant createdAt
) {
    public TeamMailboxMessage {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported mailbox schema");
        messageId = messageId != null && !messageId.isBlank() ? messageId : UUID.randomUUID().toString();
        if (teamSessionId == null || teamSessionId.isBlank()) throw new IllegalArgumentException("teamSessionId is required");
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        fromWorkerId = fromWorkerId != null ? fromWorkerId.trim() : "";
        if (toWorkerId == null || toWorkerId.isBlank()) throw new IllegalArgumentException("toWorkerId is required");
        type = Objects.requireNonNullElse(type, TeamMessageType.CONTROL);
        correlationId = correlationId != null ? correlationId.trim() : "";
        payload = payload != null
                ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload))
                : Map.of();
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }
}
