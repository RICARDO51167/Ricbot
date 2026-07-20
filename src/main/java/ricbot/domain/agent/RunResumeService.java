package ricbot.domain.agent;

import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.tool.api.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds deterministic historical contexts and persists executable forks. */
public final class RunResumeService {
    private final SessionManager sessions;
    private final RunCheckpointStore checkpoints;
    private final RunJournalStore journal;
    private final ToolRegistry tools;

    public RunResumeService(SessionManager sessions, RunCheckpointStore checkpoints, RunJournalStore journal) {
        this(sessions, checkpoints, journal, null);
    }

    public RunResumeService(
            SessionManager sessions,
            RunCheckpointStore checkpoints,
            RunJournalStore journal,
            ToolRegistry tools
    ) {
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.checkpoints = java.util.Objects.requireNonNull(checkpoints, "checkpoints");
        this.journal = java.util.Objects.requireNonNull(journal, "journal");
        this.tools = tools;
    }

    public Optional<ResumePoint> at(String sessionKey, String runId, long sequence) {
        Optional<RunState> state = journal.stateAt(sessionKey, runId, sequence);
        if (state.isEmpty()) return Optional.empty();
        Optional<RunCheckpoint> checkpoint = checkpoints.history(sessionKey).stream()
                .filter(value -> runId.equals(value.journalRunId()))
                .filter(value -> value.journalSequence() <= sequence)
                .max(Comparator.comparingLong(RunCheckpoint::journalSequence)
                        .thenComparing(RunCheckpoint::updatedAt));
        if (checkpoint.isEmpty()) return Optional.empty();

        RunCheckpoint selected = checkpoint.orElseThrow();
        Session session = sessions.find(sessionKey).orElseThrow(() ->
                new IllegalStateException("session does not exist: " + sessionKey));
        int baselineSize = Math.min(selected.sessionMessageCount(), session.getMessages().size());
        List<Map<String, Object>> messages = new ArrayList<>(
                session.getMessages().subList(0, baselineSize));
        messages.addAll(selected.runMessages());
        return Optional.of(new ResumePoint(sessionKey, selected, state.orElseThrow(), messages, Instant.now()));
    }

    public ExecutableRunFork forkAt(
            String sourceSessionKey,
            String parentRunId,
            long sequence,
            String childSessionKey,
            String childRunId
    ) {
        ResumePoint source = at(sourceSessionKey, parentRunId, sequence).orElseThrow(() ->
                new IllegalArgumentException("no recoverable checkpoint exists at the requested sequence"));
        validateExecutableBoundary(source, sequence);
        if (sessions.find(childSessionKey).isPresent()) {
            throw new IllegalStateException("child session already exists");
        }
        RunFork lineage = journal.fork(
                sourceSessionKey, parentRunId, sequence, childSessionKey, childRunId);
        Session parent = sessions.find(sourceSessionKey).orElseThrow();
        Map<String, Object> metadata = new LinkedHashMap<>(parent.getMetadata());
        metadata.put("fork_parent_session", sourceSessionKey);
        metadata.put("fork_parent_run", parentRunId);
        metadata.put("fork_parent_sequence", sequence);
        metadata.put("fork_checkpoint_id", source.checkpoint().checkpointId());
        Session child = sessions.getOrCreate(childSessionKey)
                .setMessages(source.mutableMessages())
                .setMetadata(metadata)
                .setLastConsolidated(Math.min(parent.getLastConsolidated(), source.messages().size()));
        sessions.save(child);
        return new ExecutableRunFork(lineage, source, child);
    }

    private void validateExecutableBoundary(ResumePoint source, long requestedSequence) {
        RunCheckpoint checkpoint = source.checkpoint();
        if (checkpoint.pendingToolCalls().isEmpty()) return;
        if (checkpoint.phase() != RunCheckpointPhase.MODEL_RESPONSE_RECEIVED
                || checkpoint.journalSequence() != requestedSequence) {
            throw new IllegalStateException(
                    "cannot fork from the middle of a pending tool batch; choose its model-response or tools-completed checkpoint");
        }
        if (tools == null) {
            throw new IllegalStateException("tool registry is required to validate a pending-tool fork");
        }
        for (Map<String, Object> pending : checkpoint.pendingToolCalls()) {
            Map<?, ?> function = pending.get("function") instanceof Map<?, ?> value ? value : Map.of();
            Object rawName = function.get("name");
            String name = rawName != null ? String.valueOf(rawName).trim() : "";
            if (name.isBlank() || !tools.policyFor(name).readOnly()) {
                throw new IllegalStateException(
                        "historical fork cannot replay pending side-effect tool without reconciliation: " + name);
            }
        }
    }
}
