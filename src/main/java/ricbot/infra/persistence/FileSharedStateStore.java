package ricbot.infra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Multi-process CAS implementation for local/single-node deployments. */
public final class FileSharedStateStore implements SharedStateStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Object[] LOCKS = new Object[64];
    static { java.util.Arrays.setAll(LOCKS, ignored -> new Object()); }
    private final Path root;

    public FileSharedStateStore(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        root = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("shared-state");
    }

    public Optional<SharedValue> get(String namespace, String key) {
        Path target = valuePath(namespace, key);
        synchronized (lock(target)) { return read(target, namespace, key); }
    }

    public SharedValue put(String namespace, String key, byte[] content, long expectedVersion) {
        Path target = valuePath(namespace, key);
        synchronized (lock(target)) {
            return withFileLock(target, () -> {
                Optional<SharedValue> existing = read(target, namespace, key);
                verifyVersion(existing, expectedVersion);
                long version = existing.map(value -> value.version() + 1).orElse(1L);
                SharedValue next = new SharedValue(namespace, key, version, content, Instant.now());
                write(target, next);
                return next;
            });
        }
    }

    public List<SharedValue> list(String namespace) {
        String clean = required(namespace, "namespace");
        Path directory = root.resolve(hash(clean));
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            List<SharedValue> values = new ArrayList<>();
            for (Path path : paths.filter(value -> value.toString().endsWith(".json")).toList()) {
                SharedValue value = MAPPER.readValue(path.toFile(), SharedValue.class);
                if (clean.equals(value.namespace())) values.add(value);
            }
            return values.stream().sorted(Comparator.comparing(SharedValue::key)).toList();
        } catch (Exception e) { throw new IllegalStateException("failed to list shared state", e); }
    }

    public boolean delete(String namespace, String key, long expectedVersion) {
        Path target = valuePath(namespace, key);
        synchronized (lock(target)) {
            return withFileLock(target, () -> {
                Optional<SharedValue> existing = read(target, namespace, key);
                if (existing.isEmpty()) return false;
                verifyVersion(existing, expectedVersion);
                Files.deleteIfExists(target);
                return true;
            });
        }
    }

    private <T> T withFileLock(Path target, IoSupplier<T> operation) {
        Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.get();
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("shared state operation failed", e);
        }
    }

    private static void verifyVersion(Optional<SharedValue> existing, long expected) {
        if (expected == ANY_VERSION) return;
        long actual = existing.map(SharedValue::version).orElse(0L);
        if (actual != expected) throw new IllegalStateException(
                "shared state version conflict: expected " + expected + " but was " + actual);
    }

    private Optional<SharedValue> read(Path target, String namespace, String key) {
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            SharedValue value = MAPPER.readValue(target.toFile(), SharedValue.class);
            if (!namespace.equals(value.namespace()) || !key.equals(value.key())) {
                throw new IllegalStateException("shared state identity mismatch");
            }
            return Optional.of(value);
        } catch (Exception e) { throw new IllegalStateException("failed to read shared state", e); }
    }

    private static void write(Path target, SharedValue value) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, MAPPER.writeValueAsBytes(value));
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path valuePath(String namespace, String key) {
        return root.resolve(hash(required(namespace, "namespace")))
                .resolve(hash(required(key, "key")) + ".json");
    }
    private Object lock(Path path) { return LOCKS[(path.toString().hashCode() & Integer.MAX_VALUE) % LOCKS.length]; }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws Exception; }
}
