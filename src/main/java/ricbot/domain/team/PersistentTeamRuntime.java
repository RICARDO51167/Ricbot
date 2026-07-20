package ricbot.domain.team;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Multi-process durable worker registry and mailbox.
 *
 * <p>Messages are immutable files, delivery acknowledgement is a separate
 * marker, and the team sequence is allocated under an OS file lock.</p>
 */
public final class PersistentTeamRuntime {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Object[] LOCKS = new Object[64];
    static { java.util.Arrays.setAll(LOCKS, ignored -> new Object()); }
    private final Path root;

    public PersistentTeamRuntime(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        root = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("team-runtime");
    }

    public PersistentWorkerSession createWorker(
            String teamSessionId,
            String workerId,
            TeamRole role,
            String parentWorkerId,
            Map<String, Object> metadata
    ) {
        PersistentWorkerSession created = PersistentWorkerSession.create(
                teamSessionId, workerId, role, parentWorkerId, metadata);
        Path target = workerFile(teamSessionId, workerId);
        synchronized (lock(target)) {
            withWorkerFileLock(target, () -> {
                if (Files.isRegularFile(target)) throw new IllegalStateException("worker already exists: " + workerId);
                write(target, created);
                return created;
            });
        }
        return created;
    }

    public Optional<PersistentWorkerSession> worker(String teamSessionId, String workerId) {
        Path path = workerFile(teamSessionId, workerId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            PersistentWorkerSession value = MAPPER.readValue(path.toFile(), PersistentWorkerSession.class);
            if (!teamSessionId.equals(value.teamSessionId()) || !workerId.equals(value.workerId())) {
                throw new IllegalStateException("worker identity mismatch");
            }
            return Optional.of(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load worker " + workerId, e);
        }
    }

    public PersistentWorkerSession transition(
            String teamSessionId, String workerId, WorkerSessionStatus status, String taskId) {
        Path target = workerFile(teamSessionId, workerId);
        synchronized (lock(target)) {
            return withWorkerFileLock(target, () -> {
                PersistentWorkerSession next = worker(teamSessionId, workerId).orElseThrow(() ->
                        new IllegalArgumentException("worker does not exist: " + workerId)).transition(status, taskId);
                write(target, next);
                return next;
            });
        }
    }

    public List<PersistentWorkerSession> workers(String teamSessionId) {
        Path directory = teamDirectory(teamSessionId).resolve("workers");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.walk(directory, 2)) {
            List<PersistentWorkerSession> result = new ArrayList<>();
            for (Path path : paths.filter(value -> value.getFileName().toString().equals("worker.json")).toList()) {
                result.add(MAPPER.readValue(path.toFile(), PersistentWorkerSession.class));
            }
            return result.stream().sorted(Comparator.comparing(PersistentWorkerSession::workerId)).toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to list workers", e);
        }
    }

    public TeamMailboxMessage send(
            String teamSessionId,
            String fromWorkerId,
            String toWorkerId,
            TeamMessageType type,
            String correlationId,
            Map<String, Object> payload
    ) {
        if (worker(teamSessionId, toWorkerId).isEmpty()) {
            throw new IllegalArgumentException("recipient worker does not exist: " + toWorkerId);
        }
        long sequence = nextSequence(teamSessionId);
        TeamMailboxMessage message = new TeamMailboxMessage(
                1, UUID.randomUUID().toString(), teamSessionId, sequence, fromWorkerId, toWorkerId,
                type, correlationId, payload, Instant.now());
        write(messageFile(teamSessionId, toWorkerId, message), message);
        return message;
    }

    public List<TeamMailboxMessage> broadcast(
            String teamSessionId,
            String fromWorkerId,
            TeamMessageType type,
            Map<String, Object> payload
    ) {
        return workers(teamSessionId).stream()
                .filter(worker -> !worker.workerId().equals(fromWorkerId))
                .map(worker -> send(teamSessionId, fromWorkerId, worker.workerId(), type, "", payload))
                .toList();
    }

    public List<TeamMailboxMessage> inbox(
            String teamSessionId, String workerId, long afterSequence, boolean includeAcknowledged) {
        Path directory = workerDirectory(teamSessionId, workerId).resolve("inbox");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            List<TeamMailboxMessage> result = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile).filter(value -> value.toString().endsWith(".json")).toList()) {
                TeamMailboxMessage message = MAPPER.readValue(path.toFile(), TeamMailboxMessage.class);
                if (message.sequence() > afterSequence
                        && (includeAcknowledged || !acknowledged(teamSessionId, workerId, message.messageId()))) {
                    result.add(message);
                }
            }
            return result.stream().sorted(Comparator.comparingLong(TeamMailboxMessage::sequence)).toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to read mailbox", e);
        }
    }

    public void acknowledge(String teamSessionId, String workerId, String messageId) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId is required");
        write(ackFile(teamSessionId, workerId, messageId), Map.of(
                "message_id", messageId,
                "acknowledged_at", Instant.now().toString()
        ));
    }

    public JoinResult join(String teamSessionId, List<String> workerIds) {
        Map<String, WorkerSessionStatus> statuses = new LinkedHashMap<>();
        for (String workerId : workerIds != null ? workerIds : List.<String>of()) {
            statuses.put(workerId, worker(teamSessionId, workerId)
                    .map(PersistentWorkerSession::status).orElse(WorkerSessionStatus.FAILED));
        }
        boolean complete = !statuses.isEmpty() && statuses.values().stream().allMatch(WorkerSessionStatus::terminal);
        boolean successful = complete && statuses.values().stream().allMatch(value -> value == WorkerSessionStatus.COMPLETED);
        return new JoinResult(Map.copyOf(statuses), complete, successful);
    }

    public HandoffResult handoff(
            String teamSessionId,
            String sourceWorkerId,
            String targetWorkerId,
            String taskId,
            Map<String, Object> context
    ) {
        PersistentWorkerSession source = transition(
                teamSessionId, sourceWorkerId, WorkerSessionStatus.PAUSED, taskId);
        PersistentWorkerSession target = worker(teamSessionId, targetWorkerId).orElseThrow(() ->
                new IllegalArgumentException("target worker does not exist: " + targetWorkerId));
        if (target.status() == WorkerSessionStatus.CREATED || target.status() == WorkerSessionStatus.PAUSED) {
            target = transition(teamSessionId, targetWorkerId, WorkerSessionStatus.RUNNING, taskId);
        }
        Map<String, Object> payload = new LinkedHashMap<>(context != null ? context : Map.of());
        payload.put("task_id", taskId != null ? taskId : "");
        payload.put("source_worker_id", sourceWorkerId);
        TeamMailboxMessage message = send(
                teamSessionId, sourceWorkerId, targetWorkerId, TeamMessageType.HANDOFF, taskId, payload);
        return new HandoffResult(source, target, message);
    }

    private long nextSequence(String teamSessionId) {
        Path counter = teamDirectory(teamSessionId).resolve("mailbox.sequence");
        synchronized (lock(counter)) {
            return nextSequenceLocked(counter);
        }
    }

    private long nextSequenceLocked(Path counter) {
        try {
            Files.createDirectories(counter.getParent());
            try (FileChannel channel = FileChannel.open(counter,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ByteBuffer buffer = ByteBuffer.allocate(64);
                channel.position(0);
                channel.read(buffer);
                buffer.flip();
                String raw = java.nio.charset.StandardCharsets.UTF_8.decode(buffer).toString().trim();
                long next = raw.isBlank() ? 1 : Long.parseLong(raw) + 1;
                byte[] encoded = Long.toString(next).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                channel.truncate(0);
                channel.position(0);
                channel.write(ByteBuffer.wrap(encoded));
                channel.force(true);
                return next;
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to allocate mailbox sequence", e);
        }
    }

    private boolean acknowledged(String team, String worker, String message) {
        return Files.isRegularFile(ackFile(team, worker, message));
    }
    private Path teamDirectory(String team) { return root.resolve(hash(required(team, "teamSessionId"))); }
    private Path workerDirectory(String team, String worker) {
        return teamDirectory(team).resolve("workers").resolve(hash(required(worker, "workerId")));
    }
    private Path workerFile(String team, String worker) { return workerDirectory(team, worker).resolve("worker.json"); }
    private Path messageFile(String team, String worker, TeamMailboxMessage message) {
        return workerDirectory(team, worker).resolve("inbox")
                .resolve("%020d-%s.json".formatted(message.sequence(), hash(message.messageId())));
    }
    private Path ackFile(String team, String worker, String message) {
        return workerDirectory(team, worker).resolve("acks").resolve(hash(message) + ".json");
    }
    private Object lock(Path path) {
        return LOCKS[(path.toString().hashCode() & Integer.MAX_VALUE) % LOCKS.length];
    }

    private <T> T withWorkerFileLock(Path target, IoSupplier<T> operation) {
        Path lockFile = target.resolveSibling(target.getFileName() + ".lock");
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
            throw new IllegalStateException("failed to lock worker state", e);
        }
    }

    private static void write(Path target, Object value) {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, MAPPER.writeValueAsBytes(value));
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            throw new IllegalStateException("failed to persist team runtime state", e);
        }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record JoinResult(Map<String, WorkerSessionStatus> statuses, boolean complete, boolean successful) { }
    public record HandoffResult(PersistentWorkerSession source, PersistentWorkerSession target,
                                TeamMailboxMessage message) { }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws Exception; }
}
