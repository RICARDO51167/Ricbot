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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * File-backed run journal using one atomically published file per event.
 *
 * <p>An event is the source of truth. The adjacent state file is only a
 * materialized snapshot, so a crash between event publication and snapshot
 * replacement is recovered by replaying the event directory.</p>
 */
public final class FileRunJournalStore implements RunJournalStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int LOCK_STRIPES = 64;

    private final Path root;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public FileRunJournalStore(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is required");
        }
        this.root = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("run-journal");
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    @Override
    public void append(RunEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        synchronized (lockFor(event.sessionKey(), event.runId())) {
            Optional<RunState> current = loadUnlocked(event.sessionKey(), event.runId());
            RunState next;
            if (current.isEmpty()) {
                next = RunState.from(event);
            } else {
                next = current.orElseThrow().apply(event);
            }

            Path runDirectory = runDirectory(event.sessionKey(), event.runId());
            Path eventsDirectory = runDirectory.resolve("events");
            Path eventPath = eventsDirectory.resolve(eventFileName(event));
            try {
                Files.createDirectories(eventsDirectory);
                writeAtomically(eventPath, MAPPER.writeValueAsBytes(event));
                writeAtomically(runDirectory.resolve("state.json"), MAPPER.writeValueAsBytes(next));
            } catch (IOException e) {
                throw failure("append", event.sessionKey(), event.runId(), e);
            }
        }
    }

    @Override
    public Optional<RunState> load(String sessionKey, String runId) {
        String session = requireText(sessionKey, "sessionKey");
        String run = requireText(runId, "runId");
        synchronized (lockFor(session, run)) {
            return loadUnlocked(session, run);
        }
    }

    @Override
    public Optional<RunState> latest(String sessionKey) {
        String session = requireText(sessionKey, "sessionKey");
        Path sessionDirectory = sessionDirectory(session);
        if (!Files.isDirectory(sessionDirectory)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.list(sessionDirectory)) {
            return paths
                    .filter(Files::isDirectory)
                    .map(this::loadRunDirectory)
                    .flatMap(Optional::stream)
                    .filter(state -> session.equals(state.sessionKey()))
                    .max(Comparator.comparing(RunState::updatedAt));
        } catch (IOException e) {
            throw failure("load latest", session, "latest", e);
        }
    }

    @Override
    public List<RunEvent> events(String sessionKey, String runId, long afterSequence) {
        String session = requireText(sessionKey, "sessionKey");
        String run = requireText(runId, "runId");
        synchronized (lockFor(session, run)) {
            return readEvents(runDirectory(session, run)).stream()
                    .filter(event -> event.sequence() > Math.max(0, afterSequence))
                    .toList();
        }
    }

    @Override
    public Optional<RunState> stateAt(String sessionKey, String runId, long sequence) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        String session = requireText(sessionKey, "sessionKey");
        String run = requireText(runId, "runId");
        synchronized (lockFor(session, run)) {
            List<RunEvent> prefix = readEvents(runDirectory(session, run)).stream()
                    .filter(event -> event.sequence() <= sequence)
                    .toList();
            Optional<RunState> state = replay(prefix);
            if (state.isEmpty() || state.orElseThrow().lastSequence() != sequence) {
                return Optional.empty();
            }
            return state;
        }
    }

    @Override
    public Optional<RunState> pauseLatestInterrupted(String sessionKey, String reason) {
        Optional<RunState> latest = latest(sessionKey);
        if (latest.isEmpty() || latest.orElseThrow().status().terminal()
                || latest.orElseThrow().status() == RunStatus.PAUSED) {
            return latest;
        }
        RunState current = latest.orElseThrow();
        RunEvent paused = RunEvent.create(
                current.lastSequence() + 1,
                current.runId(),
                current.sessionKey(),
                current.iteration(),
                RunEventType.RUN_PAUSED,
                RunStatus.PAUSED,
                null,
                Map.of("reason", cleanReason(reason))
        );
        append(paused);
        return load(current.sessionKey(), current.runId());
    }

    @Override
    public RunFork fork(
            String parentSessionKey,
            String parentRunId,
            long parentSequence,
            String childSessionKey,
            String childRunId
    ) {
        String parentSession = requireText(parentSessionKey, "parentSessionKey");
        String childSession = requireText(childSessionKey, "childSessionKey");
        String parent = requireText(parentRunId, "parentRunId");
        String child = requireText(childRunId, "childRunId");
        if (parent.equals(child) && parentSession.equals(childSession)) {
            throw new IllegalArgumentException("child run identity must differ from parent run identity");
        }
        RunState parentState = stateAt(parentSession, parent, parentSequence).orElseThrow(() ->
                new IllegalArgumentException("parentSequence does not identify an existing run event"));
        if (load(childSession, child).isPresent()) {
            throw new IllegalStateException("child run already exists");
        }

        RunEvent forked = RunEvent.create(
                1,
                child,
                childSession,
                parentState.iteration(),
                RunEventType.RUN_FORKED,
                RunStatus.CREATED,
                null,
                Map.of(
                        "parent_run_id", parent,
                        "parent_session_key", parentSession,
                        "parent_sequence", parentSequence,
                        "parent_status", parentState.status().name()
                )
        );
        append(forked);
        RunState childState = load(childSession, child).orElseThrow();
        return new RunFork(
                parentSession,
                parent,
                parentSequence,
                parentState,
                childSession,
                child,
                childState,
                forked.occurredAt()
        );
    }

    Path root() {
        return root;
    }

    private Optional<RunState> loadUnlocked(String sessionKey, String runId) {
        Optional<RunState> state = materialize(runDirectory(sessionKey, runId));
        if (state.isPresent()) {
            RunState loaded = state.orElseThrow();
            if (!sessionKey.equals(loaded.sessionKey()) || !runId.equals(loaded.runId())) {
                throw new IllegalStateException("run journal identity does not match requested run");
            }
        }
        return state;
    }

    private Optional<RunState> loadRunDirectory(Path runDirectory) {
        return materialize(runDirectory);
    }

    private Optional<RunState> materialize(Path runDirectory) {
        List<RunEvent> events = readEvents(runDirectory);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        validateEventStream(events);
        Optional<RunState> snapshot = readSnapshot(runDirectory.resolve("state.json"));
        if (snapshot.isEmpty()) {
            return replay(events);
        }
        RunState state = snapshot.orElseThrow();
        RunEvent lastPersistedEvent = events.get(events.size() - 1);
        if (state.lastSequence() > lastPersistedEvent.sequence()) {
            throw new IllegalStateException("run state snapshot is ahead of its event journal");
        }
        for (RunEvent event : events) {
            if (event.sequence() > state.lastSequence()) {
                state = state.apply(event);
            }
        }
        return Optional.of(state);
    }

    private static void validateEventStream(List<RunEvent> events) {
        RunEvent previous = null;
        for (RunEvent event : events) {
            if (previous == null) {
                if (event.sequence() != 1) {
                    throw new IllegalStateException("run event stream must start at sequence 1");
                }
            } else {
                if (event.sequence() != previous.sequence() + 1) {
                    throw new IllegalStateException("run event stream contains a sequence gap");
                }
                if (!event.runId().equals(previous.runId())
                        || !event.sessionKey().equals(previous.sessionKey())) {
                    throw new IllegalStateException("run event stream contains mixed identities");
                }
            }
            previous = event;
        }
    }

    private Optional<RunState> readSnapshot(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(stateFile.toFile(), RunState.class));
        } catch (Exception ignored) {
            // Events are authoritative; a torn or old snapshot is disposable.
            return Optional.empty();
        }
    }

    private List<RunEvent> readEvents(Path runDirectory) {
        Path eventsDirectory = runDirectory.resolve("events");
        if (!Files.isDirectory(eventsDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(eventsDirectory)) {
            List<Path> eventFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            java.util.ArrayList<RunEvent> events = new java.util.ArrayList<>(eventFiles.size());
            for (Path eventFile : eventFiles) {
                events.add(MAPPER.readValue(eventFile.toFile(), RunEvent.class));
            }
            return List.copyOf(events);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read run journal directory " + runDirectory.getFileName(), e);
        }
    }

    private static Optional<RunState> replay(List<RunEvent> events) {
        if (events == null || events.isEmpty()) {
            return Optional.empty();
        }
        RunState state = RunState.from(events.get(0));
        for (int i = 1; i < events.size(); i++) {
            state = state.apply(events.get(i));
        }
        return Optional.of(state);
    }

    private Path sessionDirectory(String sessionKey) {
        return root.resolve(hash(requireText(sessionKey, "sessionKey")));
    }

    private Path runDirectory(String sessionKey, String runId) {
        return sessionDirectory(sessionKey).resolve(hash(requireText(runId, "runId")));
    }

    private Object lockFor(String sessionKey, String runId) {
        String identity = sessionKey + "\n" + runId;
        return locks[(hash(identity).hashCode() & Integer.MAX_VALUE) % locks.length];
    }

    private static String eventFileName(RunEvent event) {
        return "%020d-%s.json".formatted(event.sequence(), event.eventId());
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
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireText(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }

    private static String cleanReason(String reason) {
        String clean = reason != null ? reason.trim() : "";
        return clean.isBlank() ? "interrupted" : clean;
    }

    private static IllegalStateException failure(
            String operation,
            String sessionKey,
            String runId,
            Exception cause
    ) {
        return new IllegalStateException(
                "failed to " + operation + " run journal for "
                        + hash(sessionKey).substring(0, 12) + "/" + hash(runId).substring(0, 12),
                cause
        );
    }
}
