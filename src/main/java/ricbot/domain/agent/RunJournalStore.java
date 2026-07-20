package ricbot.domain.agent;

import java.util.List;
import java.util.Optional;

/** Event journal and materialized-state boundary for durable agent runs. */
public interface RunJournalStore extends RunEventSink {
    Optional<RunState> load(String sessionKey, String runId);

    Optional<RunState> latest(String sessionKey);

    List<RunEvent> events(String sessionKey, String runId, long afterSequence);

    Optional<RunState> stateAt(String sessionKey, String runId, long sequence);

    Optional<RunState> pauseLatestInterrupted(String sessionKey, String reason);

    default RunFork fork(String sessionKey, String parentRunId, long parentSequence, String childRunId) {
        return fork(sessionKey, parentRunId, parentSequence, sessionKey, childRunId);
    }

    RunFork fork(
            String parentSessionKey,
            String parentRunId,
            long parentSequence,
            String childSessionKey,
            String childRunId
    );

    static RunJournalStore disabled() {
        return DisabledRunJournalStore.INSTANCE;
    }

    enum DisabledRunJournalStore implements RunJournalStore {
        INSTANCE;

        @Override
        public void append(RunEvent event) {
        }

        @Override
        public Optional<RunState> load(String sessionKey, String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<RunState> latest(String sessionKey) {
            return Optional.empty();
        }

        @Override
        public List<RunEvent> events(String sessionKey, String runId, long afterSequence) {
            return List.of();
        }

        @Override
        public Optional<RunState> stateAt(String sessionKey, String runId, long sequence) {
            return Optional.empty();
        }

        @Override
        public Optional<RunState> pauseLatestInterrupted(String sessionKey, String reason) {
            return Optional.empty();
        }

        @Override
        public RunFork fork(String parentSessionKey, String parentRunId, long parentSequence,
                            String childSessionKey, String childRunId) {
            throw new UnsupportedOperationException("run journal is disabled");
        }
    }
}
