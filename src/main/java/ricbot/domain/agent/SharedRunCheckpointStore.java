package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.persistence.SharedStateStore;
import ricbot.infra.persistence.SharedValue;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Checkpoint repository backed by the deployment-wide CAS store. */
public final class SharedRunCheckpointStore implements RunCheckpointStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String LATEST_NAMESPACE = "agent-checkpoints-latest";
    private static final String HISTORY_PREFIX = "agent-checkpoints-history:";

    private final SharedStateStore store;

    public SharedRunCheckpointStore(SharedStateStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    @Override
    public void save(RunCheckpoint checkpoint) {
        java.util.Objects.requireNonNull(checkpoint, "checkpoint");
        byte[] content = write(checkpoint);
        store.put(historyNamespace(checkpoint.sessionKey()), checkpoint.checkpointId(), content,
                SharedStateStore.ANY_VERSION);
        store.put(LATEST_NAMESPACE, checkpoint.sessionKey(), content, SharedStateStore.ANY_VERSION);
    }

    @Override
    public Optional<RunCheckpoint> load(String sessionKey) {
        String session = required(sessionKey, "sessionKey");
        return store.get(LATEST_NAMESPACE, session).map(value -> read(value, session, null));
    }

    @Override
    public Optional<RunCheckpoint> loadVersion(String sessionKey, String checkpointId) {
        String session = required(sessionKey, "sessionKey");
        String id = required(checkpointId, "checkpointId");
        return store.get(historyNamespace(session), id).map(value -> read(value, session, id));
    }

    @Override
    public List<RunCheckpoint> history(String sessionKey) {
        String session = required(sessionKey, "sessionKey");
        return store.list(historyNamespace(session)).stream()
                .map(value -> read(value, session, value.key()))
                .sorted(Comparator.comparingLong(RunCheckpoint::journalSequence)
                        .thenComparing(RunCheckpoint::updatedAt))
                .toList();
    }

    @Override
    public void delete(String sessionKey) {
        String session = required(sessionKey, "sessionKey");
        store.delete(LATEST_NAMESPACE, session, SharedStateStore.ANY_VERSION);
    }

    private static RunCheckpoint read(SharedValue value, String session, String checkpointId) {
        try {
            RunCheckpoint checkpoint = MAPPER.readValue(value.content(), RunCheckpoint.class);
            if (!session.equals(checkpoint.sessionKey())) {
                throw new IllegalStateException("checkpoint session identity mismatch");
            }
            if (checkpointId != null && !checkpointId.equals(checkpoint.checkpointId())) {
                throw new IllegalStateException("checkpoint version identity mismatch");
            }
            return checkpoint;
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("failed to deserialize shared checkpoint", e);
        }
    }

    private static byte[] write(RunCheckpoint checkpoint) {
        try {
            return MAPPER.writeValueAsBytes(checkpoint);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize shared checkpoint", e);
        }
    }

    private static String historyNamespace(String session) {
        return HISTORY_PREFIX + session;
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
