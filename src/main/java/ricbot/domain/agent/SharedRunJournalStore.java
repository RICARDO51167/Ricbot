package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.persistence.SharedStateStore;
import ricbot.infra.persistence.SharedValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only run journal on shared CAS storage.
 *
 * <p>Events are authoritative and deliberately share a session namespace. This
 * makes {@link #latest(String)} correct even when a process dies immediately
 * after publishing an event; no secondary index can become stale.</p>
 */
public final class SharedRunJournalStore implements RunJournalStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String EVENT_PREFIX = "agent-run-events:";

    private final SharedStateStore store;

    public SharedRunJournalStore(SharedStateStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    @Override
    public void append(RunEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        Optional<RunState> current = load(event.sessionKey(), event.runId());
        if (current.isEmpty()) {
            RunState.from(event);
        } else {
            current.orElseThrow().apply(event);
        }
        store.put(namespace(event.sessionKey()), eventKey(event), write(event),
                SharedStateStore.MUST_NOT_EXIST);
    }

    @Override
    public Optional<RunState> load(String sessionKey, String runId) {
        String session = required(sessionKey, "sessionKey");
        String run = required(runId, "runId");
        return replay(readRunEvents(session, run));
    }

    @Override
    public Optional<RunState> latest(String sessionKey) {
        String session = required(sessionKey, "sessionKey");
        Map<String, List<RunEvent>> byRun = new LinkedHashMap<>();
        for (RunEvent event : readSessionEvents(session)) {
            byRun.computeIfAbsent(event.runId(), ignored -> new ArrayList<>()).add(event);
        }
        return byRun.values().stream()
                .map(events -> replay(sorted(events)).orElseThrow())
                .max(Comparator.comparing(RunState::updatedAt));
    }

    @Override
    public List<RunEvent> events(String sessionKey, String runId, long afterSequence) {
        return readRunEvents(required(sessionKey, "sessionKey"), required(runId, "runId")).stream()
                .filter(event -> event.sequence() > Math.max(0, afterSequence))
                .toList();
    }

    @Override
    public Optional<RunState> stateAt(String sessionKey, String runId, long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        List<RunEvent> prefix = readRunEvents(required(sessionKey, "sessionKey"), required(runId, "runId"))
                .stream().filter(event -> event.sequence() <= sequence).toList();
        Optional<RunState> state = replay(prefix);
        return state.filter(value -> value.lastSequence() == sequence);
    }

    @Override
    public Optional<RunState> pauseLatestInterrupted(String sessionKey, String reason) {
        Optional<RunState> latest = latest(sessionKey);
        if (latest.isEmpty() || latest.orElseThrow().status().terminal()
                || latest.orElseThrow().status() == RunStatus.PAUSED) {
            return latest;
        }
        RunState current = latest.orElseThrow();
        append(RunEvent.create(
                current.lastSequence() + 1,
                current.runId(),
                current.sessionKey(),
                current.iteration(),
                RunEventType.RUN_PAUSED,
                RunStatus.PAUSED,
                null,
                Map.of("reason", cleanReason(reason))
        ));
        return load(current.sessionKey(), current.runId());
    }

    @Override
    public RunFork fork(String parentSessionKey, String parentRunId, long parentSequence,
                        String childSessionKey, String childRunId) {
        String parentSession = required(parentSessionKey, "parentSessionKey");
        String childSession = required(childSessionKey, "childSessionKey");
        String parent = required(parentRunId, "parentRunId");
        String child = required(childRunId, "childRunId");
        if (parentSession.equals(childSession) && parent.equals(child)) {
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
        return new RunFork(parentSession, parent, parentSequence, parentState,
                childSession, child, load(childSession, child).orElseThrow(), forked.occurredAt());
    }

    private List<RunEvent> readRunEvents(String session, String run) {
        return readSessionEvents(session).stream().filter(event -> run.equals(event.runId())).toList();
    }

    private List<RunEvent> readSessionEvents(String session) {
        List<RunEvent> events = store.list(namespace(session)).stream().map(this::read).toList();
        for (RunEvent event : events) {
            if (!session.equals(event.sessionKey())) {
                throw new IllegalStateException("run event session identity mismatch");
            }
        }
        return sorted(events);
    }

    private RunEvent read(SharedValue value) {
        try {
            return MAPPER.readValue(value.content(), RunEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize shared run event", e);
        }
    }

    private static byte[] write(RunEvent event) {
        try {
            return MAPPER.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize shared run event", e);
        }
    }

    private static Optional<RunState> replay(List<RunEvent> events) {
        if (events.isEmpty()) return Optional.empty();
        validate(events);
        RunState state = RunState.from(events.get(0));
        for (int i = 1; i < events.size(); i++) state = state.apply(events.get(i));
        return Optional.of(state);
    }

    private static void validate(List<RunEvent> events) {
        RunEvent previous = null;
        for (RunEvent event : events) {
            if (previous == null && event.sequence() != 1) {
                throw new IllegalStateException("run event stream must start at sequence 1");
            }
            if (previous != null) {
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

    private static List<RunEvent> sorted(List<RunEvent> events) {
        return events.stream().sorted(Comparator.comparingLong(RunEvent::sequence)).toList();
    }

    private static String namespace(String session) {
        return EVENT_PREFIX + session;
    }

    private static String eventKey(RunEvent event) {
        return event.runId() + ":" + "%020d".formatted(event.sequence());
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static String cleanReason(String reason) {
        String clean = reason != null ? reason.trim() : "";
        return clean.isBlank() ? "interrupted" : clean;
    }
}
