package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomic file-backed checkpoint store.
 *
 * <p>File names are SHA-256 hashes of session keys so untrusted channel or
 * conversation identifiers cannot influence the filesystem layout.</p>
 */
public final class FileRunCheckpointStore implements RunCheckpointStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int LOCK_STRIPES = 64;

    private final Path directory;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public FileRunCheckpointStore(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is required");
        }
        this.directory = workspace.toAbsolutePath().normalize()
                .resolve(".ricbot")
                .resolve("run-checkpoints");
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    @Override
    public void save(RunCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint is required");
        }
        synchronized (lockFor(checkpoint.sessionKey())) {
            Path target = pathFor(checkpoint.sessionKey());
            try {
                Files.createDirectories(directory);
                byte[] content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(checkpoint);
                Path historyTarget = historyPath(checkpoint.sessionKey(), checkpoint.checkpointId());
                Files.createDirectories(historyTarget.getParent());
                writeAtomically(historyTarget, content);
                writeAtomically(target, content);
            } catch (IOException e) {
                throw failure("save", checkpoint.sessionKey(), e);
            }
        }
    }

    @Override
    public Optional<RunCheckpoint> load(String sessionKey) {
        String key = requireSessionKey(sessionKey);
        synchronized (lockFor(key)) {
            Path path = pathFor(key);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            try {
                RunCheckpoint checkpoint = MAPPER.readValue(path.toFile(), RunCheckpoint.class);
                if (!key.equals(checkpoint.sessionKey())) {
                    throw new IllegalStateException("checkpoint session key does not match requested session");
                }
                return Optional.of(checkpoint);
            } catch (Exception e) {
                throw failure("load", key, e);
            }
        }
    }

    @Override
    public Optional<RunCheckpoint> loadVersion(String sessionKey, String checkpointId) {
        String key = requireSessionKey(sessionKey);
        String version = requireCheckpointId(checkpointId);
        synchronized (lockFor(key)) {
            return readCheckpoint(historyPath(key, version), key, version);
        }
    }

    @Override
    public List<RunCheckpoint> history(String sessionKey) {
        String key = requireSessionKey(sessionKey);
        synchronized (lockFor(key)) {
            Path historyDirectory = historyDirectory(key);
            if (!Files.isDirectory(historyDirectory)) {
                return List.of();
            }
            try (var paths = Files.list(historyDirectory)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .map(path -> readCheckpoint(path, key, null).orElseThrow())
                        .sorted(java.util.Comparator
                                .comparingLong(RunCheckpoint::journalSequence)
                                .thenComparing(RunCheckpoint::updatedAt))
                        .toList();
            } catch (IOException e) {
                throw failure("list history", key, e);
            }
        }
    }

    @Override
    public void delete(String sessionKey) {
        String key = requireSessionKey(sessionKey);
        synchronized (lockFor(key)) {
            try {
                Files.deleteIfExists(pathFor(key));
            } catch (IOException e) {
                throw failure("delete", key, e);
            }
        }
    }

    Path directory() {
        return directory;
    }

    private Path pathFor(String sessionKey) {
        return directory.resolve(hash(requireSessionKey(sessionKey)) + ".json");
    }

    private Path historyDirectory(String sessionKey) {
        return directory.resolve("history").resolve(hash(requireSessionKey(sessionKey)));
    }

    private Path historyPath(String sessionKey, String checkpointId) {
        return historyDirectory(sessionKey).resolve(hash(requireCheckpointId(checkpointId)) + ".json");
    }

    private Object lockFor(String sessionKey) {
        return locks[(hash(sessionKey).hashCode() & Integer.MAX_VALUE) % locks.length];
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Optional<RunCheckpoint> readCheckpoint(Path path, String sessionKey, String checkpointId) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            RunCheckpoint checkpoint = MAPPER.readValue(path.toFile(), RunCheckpoint.class);
            if (!sessionKey.equals(checkpoint.sessionKey())) {
                throw new IllegalStateException("checkpoint session key does not match requested session");
            }
            if (checkpointId != null && !checkpointId.equals(checkpoint.checkpointId())) {
                throw new IllegalStateException("checkpoint id does not match requested version");
            }
            return Optional.of(checkpoint);
        } catch (Exception e) {
            throw failure("load", sessionKey, e);
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveAtomically(temporary, target);
        } catch (IOException e) {
            tryDelete(temporary);
            throw e;
        }
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireSessionKey(String sessionKey) {
        String key = sessionKey != null ? sessionKey.trim() : "";
        if (key.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        return key;
    }

    private static String requireCheckpointId(String checkpointId) {
        String id = checkpointId != null ? checkpointId.trim() : "";
        if (id.isBlank()) {
            throw new IllegalArgumentException("checkpointId is required");
        }
        return id;
    }

    private static IllegalStateException failure(String operation, String sessionKey, Exception cause) {
        return new IllegalStateException(
                "failed to " + operation + " run checkpoint for session " + hash(sessionKey).substring(0, 12),
                cause
        );
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
