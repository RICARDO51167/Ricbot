package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.persistence.FileSharedStateStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SharedPersistenceStoresTest {

    @Test
    void checkpointHistoryIsVisibleAcrossStoreInstances(@TempDir Path workspace) {
        FileSharedStateStore shared = new FileSharedStateStore(workspace);
        SharedRunCheckpointStore writer = new SharedRunCheckpointStore(shared);
        SharedRunCheckpointStore reader = new SharedRunCheckpointStore(new FileSharedStateStore(workspace));
        RunCheckpoint first = checkpoint("session", "cp-1", 1, RunCheckpointPhase.MODEL_RESPONSE_RECEIVED);
        RunCheckpoint second = checkpoint("session", "cp-2", 2, RunCheckpointPhase.TOOLS_COMPLETED);

        writer.save(first);
        writer.save(second);

        assertEquals(second, reader.load("session").orElseThrow());
        assertEquals(List.of("cp-1", "cp-2"),
                reader.history("session").stream().map(RunCheckpoint::checkpointId).toList());
        reader.delete("session");
        assertTrue(writer.load("session").isEmpty());
        assertEquals(first, writer.loadVersion("session", "cp-1").orElseThrow());
    }

    @Test
    void journalReplaysAndForksAcrossStoreInstances(@TempDir Path workspace) {
        SharedRunJournalStore first = new SharedRunJournalStore(new FileSharedStateStore(workspace));
        SharedRunJournalStore second = new SharedRunJournalStore(new FileSharedStateStore(workspace));
        first.append(event(1, "run-1", "session", RunEventType.RUN_STARTED, RunStatus.CREATED));
        second.append(event(2, "run-1", "session", RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING));

        assertEquals(RunStatus.MODEL_RUNNING, first.load("session", "run-1").orElseThrow().status());
        assertEquals(2, first.latest("session").orElseThrow().lastSequence());
        assertEquals(List.of("run-1"), first.runs("session").stream().map(RunState::runId).toList());
        RunFork fork = second.fork("session", "run-1", 2, "other", "child");
        assertEquals("other", fork.childSessionKey());
        assertEquals(RunStatus.CREATED, first.load("other", "child").orElseThrow().status());
        assertThrows(IllegalArgumentException.class, () ->
                first.append(event(2, "run-1", "session", RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING)));
    }

    @Test
    void sideEffectClaimIsExclusiveAcrossStoreInstances(@TempDir Path workspace) {
        SharedSideEffectStore first = new SharedSideEffectStore(new FileSharedStateStore(workspace));
        SharedSideEffectStore second = new SharedSideEffectStore(new FileSharedStateStore(workspace));
        SideEffectRecord reservation = SideEffectRecord.reserved("key-1", "session", "write", "digest");

        assertTrue(first.claim(reservation).created());
        SideEffectClaim duplicate = second.claim(reservation);

        assertFalse(duplicate.created());
        SideEffectRecord completed = reservation.withStatus(SideEffectStatus.SUCCEEDED, Map.of("ok", true), "c-1");
        second.save(completed);
        assertEquals(SideEffectStatus.SUCCEEDED, first.load("key-1").orElseThrow().status());
    }

    private static RunEvent event(long sequence, String runId, String session, RunEventType type, RunStatus status) {
        return RunEvent.create(sequence, runId, session, 1, type, status, null, Map.of());
    }

    private static RunCheckpoint checkpoint(String session, String id, int iteration, RunCheckpointPhase phase) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of());
        return new RunCheckpoint(
                RunCheckpoint.CURRENT_SCHEMA_VERSION, id, "run-1", "run-1", session,
                iteration, 0, iteration, phase, AgentNodeState.initial(), List.of(), assistant,
                List.of(), List.of(), Map.of(), "", Instant.parse("2026-07-20T00:00:00Z").plusSeconds(iteration)
        );
    }
}
