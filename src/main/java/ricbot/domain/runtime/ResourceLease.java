package ricbot.domain.runtime;

import java.time.Instant;

public record ResourceLease(String resource, String effectId, String owner, Instant expiresAt, long version) {
    public ResourceLease {
        if (resource == null || resource.isBlank() || effectId == null || effectId.isBlank()
                || owner == null || owner.isBlank() || expiresAt == null || version < 0) {
            throw new IllegalArgumentException("invalid resource lease");
        }
    }
}
