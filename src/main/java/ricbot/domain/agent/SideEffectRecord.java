package ricbot.domain.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable idempotency record for one externally visible tool effect. */
public record SideEffectRecord(
        int schemaVersion,
        String idempotencyKey,
        String runId,
        String sessionKey,
        String taskId,
        String activationId,
        String toolName,
        String argumentsDigest,
        Map<String, Object> arguments,
        SideEffectStatus status,
        Object result,
        String confirmationId,
        long version,
        String ownerInstanceId,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public SideEffectRecord {
        if (schemaVersion != 1 && schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        runId = clean(runId);
        sessionKey = required(sessionKey, "sessionKey");
        taskId = clean(taskId);
        activationId = clean(activationId);
        toolName = required(toolName, "toolName");
        argumentsDigest = required(argumentsDigest, "argumentsDigest");
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments != null ? arguments : Map.of()));
        status = Objects.requireNonNull(status, "status");
        confirmationId = confirmationId != null ? confirmationId.trim() : "";
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        ownerInstanceId = clean(ownerInstanceId);
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
    }

    public static SideEffectRecord reserved(String key, String session, String tool, String digest) {
        return reserved(key, "", session, "", "", tool, digest, Map.of());
    }

    public static SideEffectRecord reserved(String key, String runId, String session, String taskId,
                                            String activationId, String tool, String digest,
                                            Map<String, Object> arguments) {
        Instant now = Instant.now();
        return new SideEffectRecord(CURRENT_SCHEMA_VERSION, key, runId, session, taskId, activationId,
                tool, digest, arguments, SideEffectStatus.RESERVED, null, "", 0, "", null, now, now);
    }

    public SideEffectRecord withStatus(SideEffectStatus next, Object nextResult, String confirmation) {
        return new SideEffectRecord(CURRENT_SCHEMA_VERSION, idempotencyKey, runId, sessionKey, taskId,
                activationId, toolName, argumentsDigest, arguments, next, nextResult, confirmation,
                version + 1, ownerInstanceId, leaseExpiresAt, createdAt, Instant.now());
    }

    public SideEffectRecord claimExecution(String instanceId, Instant expiresAt) {
        return claimExecution(instanceId, expiresAt, null);
    }

    /**
     * Claims the external-call boundary and durably records the evidence needed to
     * probe an uncertain result after a crash.
     */
    public SideEffectRecord claimExecution(String instanceId, Instant expiresAt, Object executionEvidence) {
        return new SideEffectRecord(CURRENT_SCHEMA_VERSION, idempotencyKey, runId, sessionKey, taskId,
                activationId, toolName, argumentsDigest, arguments, SideEffectStatus.EXECUTING, executionEvidence,
                confirmationId, version + 1, required(instanceId, "instanceId"),
                Objects.requireNonNull(expiresAt, "expiresAt"), createdAt, Instant.now());
    }

    public SideEffectRecord renewLease(String instanceId, Instant expiresAt) {
        if (!ownerInstanceId.equals(required(instanceId, "instanceId"))) {
            throw new IllegalStateException("side effect lease is owned by another runtime instance");
        }
        if (status != SideEffectStatus.EXECUTING) {
            throw new IllegalStateException("only executing side effects have a renewable lease");
        }
        return new SideEffectRecord(CURRENT_SCHEMA_VERSION, idempotencyKey, runId, sessionKey, taskId,
                activationId, toolName, argumentsDigest, arguments, status, result, confirmationId,
                version + 1, ownerInstanceId, Objects.requireNonNull(expiresAt, "expiresAt"),
                createdAt, Instant.now());
    }

    public SideEffectRecord clearLease(SideEffectStatus next, Object nextResult, String confirmation) {
        return new SideEffectRecord(CURRENT_SCHEMA_VERSION, idempotencyKey, runId, sessionKey, taskId,
                activationId, toolName, argumentsDigest, arguments, next, nextResult, confirmation,
                version + 1, "", null, createdAt, Instant.now());
    }

    private static String required(String value, String field) {
        String result = clean(value);
        if (result.isBlank()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
