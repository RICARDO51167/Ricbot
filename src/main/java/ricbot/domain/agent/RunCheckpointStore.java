package ricbot.domain.agent;

import java.util.Optional;
import java.util.List;

/**
 * Persistence boundary for the latest recoverable checkpoint of a session.
 */
public interface RunCheckpointStore {
    void save(RunCheckpoint checkpoint);

    Optional<RunCheckpoint> load(String sessionKey);

    Optional<RunCheckpoint> loadVersion(String sessionKey, String checkpointId);

    List<RunCheckpoint> history(String sessionKey);

    void delete(String sessionKey);

    static RunCheckpointStore disabled() {
        return DisabledRunCheckpointStore.INSTANCE;
    }

    enum DisabledRunCheckpointStore implements RunCheckpointStore {
        INSTANCE;

        @Override
        public void save(RunCheckpoint checkpoint) {
        }

        @Override
        public Optional<RunCheckpoint> load(String sessionKey) {
            return Optional.empty();
        }

        @Override
        public Optional<RunCheckpoint> loadVersion(String sessionKey, String checkpointId) {
            return Optional.empty();
        }

        @Override
        public List<RunCheckpoint> history(String sessionKey) {
            return List.of();
        }

        @Override
        public void delete(String sessionKey) {
        }
    }
}
