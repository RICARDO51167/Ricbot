package ricbot.domain.worker;

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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** File-backed worker registry and immutable mailbox. */
public final class WorkerStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Object[] LOCKS = new Object[64];

    static {
        java.util.Arrays.setAll(LOCKS, ignored -> new Object());
    }

    private final Path root;

    public WorkerStore(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        root = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("worker-runtime");
    }

    public StoredWorker create(WorkerSpec spec) {
        if (spec == null) throw new IllegalArgumentException("worker spec is required");
        Path creationLock = creationLock(spec);
        synchronized (lock(creationLock)) {
            return withFileLock(creationLock, () -> {
                Optional<StoredWorker> idempotent = findByIdempotencyKey(spec.scopeId(), spec.idempotencyKey());
                if (idempotent.isPresent()) return idempotent.orElseThrow();

                Path specFile = specFile(spec.workerId());
                if (Files.isRegularFile(specFile)) {
                    WorkerSpec existingSpec = readSpec(specFile, spec.workerId());
                    if (!sameCreation(existingSpec, spec)) {
                        throw new IllegalStateException("worker already exists: " + spec.workerId());
                    }
                    Path stateFile = stateFile(spec.workerId());
                    if (!Files.isRegularFile(stateFile)) {
                        write(stateFile, WorkerState.created(spec.workerId()));
                    }
                    writeIdempotencyIndexIfNeeded(existingSpec);
                    return load(spec.workerId()).orElseThrow();
                }

                WorkerState state = WorkerState.created(spec.workerId());
                write(specFile, spec);
                write(stateFile(spec.workerId()), state);
                writeIdempotencyIndexIfNeeded(spec);
                return new StoredWorker(spec, state);
            });
        }
    }

    public Optional<StoredWorker> load(String workerId) {
        String id = required(workerId, "workerId");
        Path specFile = specFile(id);
        Path stateFile = stateFile(id);
        if (!Files.isRegularFile(specFile) || !Files.isRegularFile(stateFile)) return Optional.empty();
        try {
            WorkerSpec spec = MAPPER.readValue(specFile.toFile(), WorkerSpec.class);
            WorkerState state = MAPPER.readValue(stateFile.toFile(), WorkerState.class);
            if (!id.equals(spec.workerId()) || !id.equals(state.workerId())) {
                throw new IllegalStateException("worker identity mismatch: " + id);
            }
            return Optional.of(new StoredWorker(spec, state));
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("failed to load worker " + id, e);
        }
    }

    public Optional<StoredWorker> findByIdempotencyKey(String scopeId, String idempotencyKey) {
        String scope = required(scopeId, "scopeId");
        String key = clean(idempotencyKey);
        if (key.isBlank()) return Optional.empty();
        Path index = idempotencyFile(scope, key);
        if (!Files.isRegularFile(index)) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = MAPPER.readValue(index.toFile(), Map.class);
            String workerId = String.valueOf(value.getOrDefault("worker_id", ""));
            return load(workerId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load worker idempotency index", e);
        }
    }

    public List<StoredWorker> list() {
        Path workers = root.resolve("workers");
        if (!Files.isDirectory(workers)) return List.of();
        try (Stream<Path> paths = Files.walk(workers, 2)) {
            List<StoredWorker> result = new ArrayList<>();
            for (Path path : paths.filter(value -> value.getFileName().toString().equals("spec.json")).toList()) {
                String workerId = MAPPER.readValue(path.toFile(), WorkerSpec.class).workerId();
                load(workerId).ifPresent(result::add);
            }
            return result.stream().sorted(Comparator.comparing(value -> value.spec().workerId())).toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to list workers", e);
        }
    }

    public WorkerState saveState(WorkerState next, long expectedVersion) {
        if (next == null) throw new IllegalArgumentException("worker state is required");
        Path target = stateFile(next.workerId());
        synchronized (lock(target)) {
            return withFileLock(target.resolveSibling("state.lock"), () -> {
                StoredWorker current = load(next.workerId()).orElseThrow(() ->
                        new IllegalArgumentException("worker does not exist: " + next.workerId()));
                if (current.state().version() != expectedVersion) {
                    throw new IllegalStateException("worker state version conflict: expected " + expectedVersion
                            + " but was " + current.state().version());
                }
                if (next.version() != expectedVersion + 1) {
                    throw new IllegalArgumentException("next worker state version must increment by one");
                }
                write(target, next);
                return next;
            });
        }
    }

    public MailboxMessage send(
            String fromWorkerId,
            String toWorkerId,
            MessageKind kind,
            String correlationId,
            Map<String, Object> payload
    ) {
        String recipient = required(toWorkerId, "toWorkerId");
        if (load(recipient).isEmpty()) throw new IllegalArgumentException("worker does not exist: " + recipient);
        long sequence = nextSequence();
        MailboxMessage message = new MailboxMessage(
                1,
                UUID.randomUUID().toString(),
                sequence,
                clean(fromWorkerId),
                recipient,
                kind != null ? kind : MessageKind.CONTROL,
                clean(correlationId),
                payload != null ? Map.copyOf(payload) : Map.of(),
                Instant.now()
        );
        write(messageFile(recipient, message), message);
        return message;
    }

    public List<MailboxMessage> inbox(String workerId, long afterSequence, boolean includeAcknowledged) {
        String id = required(workerId, "workerId");
        Path inbox = workerDirectory(id).resolve("inbox");
        if (!Files.isDirectory(inbox)) return List.of();
        try (Stream<Path> paths = Files.list(inbox)) {
            List<MailboxMessage> result = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(value -> value.getFileName().toString().endsWith(".json")).toList()) {
                MailboxMessage message = MAPPER.readValue(path.toFile(), MailboxMessage.class);
                if (message.sequence() > afterSequence
                        && (includeAcknowledged || !Files.isRegularFile(ackFile(id, message.messageId())))) {
                    result.add(message);
                }
            }
            return result.stream().sorted(Comparator.comparingLong(MailboxMessage::sequence)).toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to read worker mailbox", e);
        }
    }

    public void acknowledge(String workerId, String messageId) {
        String id = required(workerId, "workerId");
        String message = required(messageId, "messageId");
        write(ackFile(id, message), Map.of(
                "schema_version", 1,
                "message_id", message,
                "acknowledged_at", Instant.now().toString()
        ));
    }

    private long nextSequence() {
        Path counter = root.resolve("mailbox.sequence");
        synchronized (lock(counter)) {
            return withFileLock(root.resolve("mailbox.sequence.lock"), () -> {
                Files.createDirectories(counter.getParent());
                long current = 0;
                if (Files.isRegularFile(counter)) {
                    String raw = Files.readString(counter, StandardCharsets.UTF_8).trim();
                    current = raw.isBlank() ? 0 : Long.parseLong(raw);
                }
                long next = current + 1;
                writeBytes(counter, Long.toString(next).getBytes(StandardCharsets.UTF_8));
                return next;
            });
        }
    }

    private Path creationLock(WorkerSpec spec) {
        String identity = spec.idempotencyKey().isBlank()
                ? "worker\u0000" + spec.workerId()
                : "idempotency\u0000" + spec.scopeId() + "\u0000" + spec.idempotencyKey();
        return root.resolve("locks").resolve(hash(identity) + ".lock");
    }

    private Path workerDirectory(String workerId) {
        return root.resolve("workers").resolve(hash(required(workerId, "workerId")));
    }

    private Path specFile(String workerId) {
        return workerDirectory(workerId).resolve("spec.json");
    }

    private Path stateFile(String workerId) {
        return workerDirectory(workerId).resolve("state.json");
    }

    private Path idempotencyFile(String scopeId, String idempotencyKey) {
        return root.resolve("idempotency").resolve(hash(required(scopeId, "scopeId") + "\u0000"
                + required(idempotencyKey, "idempotencyKey")) + ".json");
    }

    private Path messageFile(String workerId, MailboxMessage message) {
        return workerDirectory(workerId).resolve("inbox")
                .resolve("%020d-%s.json".formatted(message.sequence(), hash(message.messageId())));
    }

    private Path ackFile(String workerId, String messageId) {
        return workerDirectory(workerId).resolve("acks").resolve(hash(messageId) + ".json");
    }

    private static boolean sameCreation(WorkerSpec left, WorkerSpec right) {
        return left.workerId().equals(right.workerId())
                && left.scopeId().equals(right.scopeId())
                && left.idempotencyKey().equals(right.idempotencyKey());
    }

    private WorkerSpec readSpec(Path path, String workerId) {
        try {
            return MAPPER.readValue(path.toFile(), WorkerSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load worker spec " + workerId, e);
        }
    }

    private void writeIdempotencyIndexIfNeeded(WorkerSpec spec) {
        if (spec.idempotencyKey().isBlank()) return;
        write(idempotencyFile(spec.scopeId(), spec.idempotencyKey()), Map.of(
                "schema_version", 1,
                "scope_id", spec.scopeId(),
                "idempotency_key", spec.idempotencyKey(),
                "worker_id", spec.workerId(),
                "created_at", spec.createdAt().toString()
        ));
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
            throw new IllegalStateException("failed to lock worker store", e);
        }
    }

    private static void write(Path target, Object value) {
        try {
            writeBytes(target, MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist worker runtime state", e);
        }
    }

    private static void writeBytes(Path target, byte[] bytes) {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("failed to persist worker runtime state", e);
        }
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record StoredWorker(WorkerSpec spec, WorkerState state) {
    }

    public record MailboxMessage(
            int schemaVersion,
            String messageId,
            long sequence,
            String fromWorkerId,
            String toWorkerId,
            MessageKind kind,
            String correlationId,
            Map<String, Object> payload,
            Instant createdAt
    ) {
        public MailboxMessage {
            if (schemaVersion != 1) throw new IllegalArgumentException("unsupported worker mailbox schema");
            messageId = required(messageId, "messageId");
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
            fromWorkerId = clean(fromWorkerId);
            toWorkerId = required(toWorkerId, "toWorkerId");
            kind = kind != null ? kind : MessageKind.CONTROL;
            correlationId = clean(correlationId);
            payload = payload != null ? Map.copyOf(payload) : Map.of();
            createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public enum MessageKind {
        TASK, RESULT, QUESTION, RESPONSE, PROGRESS, HANDOFF, CONTROL
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }
}
