package ricbot.infra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Multi-process append-only Journal for runtime facts outside the Run state machine. */
public final class FileRuntimeFactJournal {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Object[] LOCKS = new Object[64];

    static {
        java.util.Arrays.setAll(LOCKS, ignored -> new Object());
    }

    private final Path root;

    public FileRuntimeFactJournal(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        root = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("fact-journal");
    }

    public RuntimeFactEvent append(
            String eventId,
            String sessionKey,
            String type,
            String actor,
            String message,
            Map<String, Object> details,
            Instant occurredAt
    ) {
        String session = required(sessionKey, "sessionKey");
        String id = required(eventId, "eventId");
        Path sessionDirectory = sessionDirectory(session);
        Path target = sessionDirectory.resolve("events").resolve(hash(id) + ".json");
        Path lockFile = sessionDirectory.resolve("journal.lock");
        synchronized (lock(sessionDirectory)) {
            return withFileLock(lockFile, () -> {
                if (Files.isRegularFile(target)) return read(target);
                long sequence = nextSequence(sessionDirectory.resolve("sequence"));
                RuntimeFactEvent event = new RuntimeFactEvent(
                        RuntimeFactEvent.CURRENT_SCHEMA_VERSION,
                        id,
                        sequence,
                        session,
                        type,
                        actor,
                        message,
                        details,
                        occurredAt
                );
                write(target, MAPPER.writeValueAsBytes(event));
                return event;
            });
        }
    }

    public List<RuntimeFactEvent> events(String sessionKey, long afterSequence) {
        Path directory = sessionDirectory(required(sessionKey, "sessionKey")).resolve("events");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            List<RuntimeFactEvent> result = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                RuntimeFactEvent event = read(path);
                if (event.sequence() > afterSequence) result.add(event);
            }
            return result.stream().sorted(Comparator.comparingLong(RuntimeFactEvent::sequence)).toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to read runtime fact journal", e);
        }
    }

    private long nextSequence(Path counter) throws Exception {
        String stored = Files.isRegularFile(counter)
                ? Files.readString(counter, StandardCharsets.UTF_8).trim()
                : "";
        long current = stored.isBlank() ? 0 : Long.parseLong(stored);
        long next = current + 1;
        write(counter, Long.toString(next).getBytes(StandardCharsets.UTF_8));
        return next;
    }

    private RuntimeFactEvent read(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), RuntimeFactEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read runtime fact", e);
        }
    }

    private Path sessionDirectory(String sessionKey) {
        return root.resolve(hash(sessionKey));
    }

    private Object lock(Path path) {
        return LOCKS[(path.toString().hashCode() & Integer.MAX_VALUE) % LOCKS.length];
    }

    private <T> T withFileLock(Path lockFile, IoSupplier<T> operation) {
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.get();
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            if (e instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalStateException("failed to append runtime fact", e);
        }
    }

    private static void write(Path target, byte[] content) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.createDirectories(target.getParent());
        Files.write(temporary, content);
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }
}
