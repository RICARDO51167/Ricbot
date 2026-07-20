package ricbot.infra.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record SharedValue(String namespace, String key, long version, byte[] content, Instant updatedAt) {
    public SharedValue {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        content = content != null ? Arrays.copyOf(content, content.length) : new byte[0];
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }
    @Override public byte[] content() { return Arrays.copyOf(content, content.length); }
}
