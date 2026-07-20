package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Atomic file-backed idempotency ledger. */
public final class FileSideEffectStore implements SideEffectStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final Path directory;
    private final Object[] locks = new Object[64];

    public FileSideEffectStore(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        directory = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("side-effects");
        java.util.Arrays.setAll(locks, ignored -> new Object());
    }

    @Override
    public Optional<SideEffectRecord> load(String key) {
        String clean = required(key);
        synchronized (lock(clean)) {
            Path path = path(clean);
            if (!Files.isRegularFile(path)) return Optional.empty();
            try {
                SideEffectRecord record = MAPPER.readValue(path.toFile(), SideEffectRecord.class);
                if (!clean.equals(record.idempotencyKey())) throw new IllegalStateException("idempotency identity mismatch");
                return Optional.of(record);
            } catch (Exception e) {
                throw new IllegalStateException("failed to load side-effect record", e);
            }
        }
    }

    @Override
    public SideEffectClaim claim(SideEffectRecord reservation) {
        synchronized (lock(reservation.idempotencyKey())) {
            Optional<SideEffectRecord> existing = load(reservation.idempotencyKey());
            if (existing.isPresent()) return new SideEffectClaim(existing.orElseThrow(), false);
            return new SideEffectClaim(write(reservation), true);
        }
    }

    @Override
    public SideEffectRecord save(SideEffectRecord record) {
        synchronized (lock(record.idempotencyKey())) {
            return write(record);
        }
    }

    private SideEffectRecord write(SideEffectRecord record) {
        Path target = path(record.idempotencyKey());
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.write(temporary, MAPPER.writeValueAsBytes(record));
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return record;
        } catch (IOException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("failed to save side-effect record", e);
        }
    }

    private Path path(String key) { return directory.resolve(hash(required(key)) + ".json"); }
    private Object lock(String key) { return locks[(hash(key).hashCode() & Integer.MAX_VALUE) % locks.length]; }
    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        return clean;
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
