package ricbot.domain.agent;

import java.time.Instant;
import java.util.Objects;

/** Durable idempotency record for one externally visible tool effect. */
public record SideEffectRecord(
        int schemaVersion,
        String idempotencyKey,
        String sessionKey,
        String toolName,
        String argumentsDigest,
        SideEffectStatus status,
        Object result,
        String confirmationId,
        Instant createdAt,
        Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public SideEffectRecord {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("unsupported schemaVersion");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        sessionKey = required(sessionKey, "sessionKey");
        toolName = required(toolName, "toolName");
        argumentsDigest = required(argumentsDigest, "argumentsDigest");
        status = Objects.requireNonNull(status, "status");
        confirmationId = confirmationId != null ? confirmationId.trim() : "";
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
    }

    public static SideEffectRecord reserved(String key, String session, String tool, String digest) {
        Instant now = Instant.now();
        return new SideEffectRecord(1, key, session, tool, digest, SideEffectStatus.RESERVED,
                null, "", now, now);
    }

    public SideEffectRecord withStatus(SideEffectStatus next, Object nextResult, String confirmation) {
        return new SideEffectRecord(schemaVersion, idempotencyKey, sessionKey, toolName, argumentsDigest,
                next, nextResult, confirmation, createdAt, Instant.now());
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
