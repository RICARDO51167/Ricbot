package ricbot.domain.agent;

import java.util.List;
import java.util.Optional;

/** Cursor-based replay contract suitable for SSE/WebSocket reconnects. */
public final class RunEventReplayService {
    private final RunJournalStore journal;

    public RunEventReplayService(RunJournalStore journal) {
        this.journal = java.util.Objects.requireNonNull(journal, "journal");
    }

    public ReplayBatch replay(String sessionKey, String runId, long afterSequence, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 1000));
        List<RunEvent> available = journal.events(sessionKey, runId, Math.max(0, afterSequence));
        List<RunEvent> events = available.stream().limit(effectiveLimit).toList();
        long cursor = events.isEmpty() ? Math.max(0, afterSequence) : events.get(events.size() - 1).sequence();
        Optional<RunState> state = journal.load(sessionKey, runId);
        boolean caughtUp = state.isEmpty() || cursor >= state.orElseThrow().lastSequence();
        return new ReplayBatch(events, cursor, caughtUp, state.orElse(null));
    }

    public record ReplayBatch(List<RunEvent> events, long nextCursor, boolean caughtUp, RunState currentState) {
        public ReplayBatch { events = events != null ? List.copyOf(events) : List.of(); }
    }
}
