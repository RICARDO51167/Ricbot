package ricbot.domain.runtime.dto;

import ricbot.domain.runtime.enump.RuntimeInstanceStatus;

import java.time.Instant;
import java.util.Objects;

/** Durable identity and lease for one Ricbot runtime process. */
public record RuntimeInstanceRecord(
        String instanceId,
        String hostId,
        long pid,
        Instant processStartedAt,
        Instant heartbeatAt,
        Instant expiresAt,
        RuntimeInstanceStatus status,
        long version
) {
    public RuntimeInstanceRecord {
        instanceId = required(instanceId, "instanceId");
        hostId = required(hostId, "hostId");
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        processStartedAt = Objects.requireNonNull(processStartedAt, "processStartedAt");
        heartbeatAt = Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        status = Objects.requireNonNull(status, "status");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public RuntimeInstanceRecord heartbeat(Instant now, Instant nextExpiry) {
        if (status != RuntimeInstanceStatus.ACTIVE) {
            throw new IllegalStateException("only an active runtime instance can heartbeat");
        }
        return new RuntimeInstanceRecord(instanceId, hostId, pid, processStartedAt, now, nextExpiry,
                RuntimeInstanceStatus.ACTIVE, version + 1);
    }

    public RuntimeInstanceRecord close(Instant now) {
        return new RuntimeInstanceRecord(instanceId, hostId, pid, processStartedAt, now, now,
                RuntimeInstanceStatus.CLOSED, version + 1);
    }

    public RuntimeInstanceRecord expire(Instant now) {
        return new RuntimeInstanceRecord(instanceId, hostId, pid, processStartedAt, heartbeatAt, now,
                RuntimeInstanceStatus.EXPIRED, version + 1);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
