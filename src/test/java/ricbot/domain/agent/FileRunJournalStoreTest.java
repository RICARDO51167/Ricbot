package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileRunJournalStoreTest {

    @Test
    void replaysEventsWhenMaterializedSnapshotIsMissing(@TempDir Path workspace) throws Exception {
        FileRunJournalStore store = new FileRunJournalStore(workspace);
        String sessionKey = "channel:../../unsafe";
        String runId = "run/../../one";
        store.append(event(1, runId, sessionKey, RunEventType.RUN_STARTED, RunStatus.CREATED, null));
        store.append(event(2, runId, sessionKey, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null));

        Path stateFile;
        try (var paths = Files.walk(store.root())) {
            stateFile = paths.filter(path -> "state.json".equals(path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow();
        }
        Files.delete(stateFile);

        RunState state = store.load(sessionKey, runId).orElseThrow();
        assertEquals(RunStatus.MODEL_RUNNING, state.status());
        assertEquals(2, state.lastSequence());
        assertEquals(2, store.events(sessionKey, runId, 0).size());
        try (var paths = Files.walk(store.root())) {
            assertTrue(paths.filter(Files::isRegularFile).allMatch(path ->
                    !path.toString().contains("unsafe") && !path.toString().contains("../")));
        }
    }

    @Test
    void interruptedRunningToolBecomesUnknownInsteadOfBeingReplayed(@TempDir Path workspace) {
        FileRunJournalStore store = new FileRunJournalStore(workspace);
        String sessionKey = "cli:direct";
        String runId = "run-1";
        ToolInvocationRecord running = ToolInvocationRecord.running(
                runId,
                1,
                "call-1",
                "write_file",
                Map.of("path", "result.txt", "content", "done"),
                false,
                "side_effect"
        );
        store.append(event(1, runId, sessionKey, RunEventType.RUN_STARTED, RunStatus.CREATED, null));
        store.append(event(2, runId, sessionKey, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null));
        store.append(event(3, runId, sessionKey, RunEventType.MODEL_RESPONSE_RECEIVED, RunStatus.WAITING_TOOL, null));
        store.append(event(4, runId, sessionKey, RunEventType.TOOL_CALL_STARTED, RunStatus.TOOL_RUNNING, running));

        RunState paused = store.pauseLatestInterrupted(sessionKey, "process_recovery").orElseThrow();

        assertEquals(RunStatus.PAUSED, paused.status());
        assertEquals("process_recovery", paused.pauseReason());
        ToolInvocationRecord recovered = paused.toolInvocations().get(running.invocationId());
        assertEquals(ToolInvocationStatus.UNKNOWN, recovered.status());
        assertFalse(recovered.readOnly());
        assertEquals(5, store.events(sessionKey, runId, 0).size());
    }

    @Test
    void exposesDeterministicReplayCursor(@TempDir Path workspace) {
        FileRunJournalStore store = new FileRunJournalStore(workspace);
        store.append(event(1, "run-1", "cli:direct", RunEventType.RUN_STARTED, RunStatus.CREATED, null));
        store.append(event(2, "run-1", "cli:direct", RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null));
        store.append(event(3, "run-1", "cli:direct", RunEventType.RUN_FINISHED, RunStatus.COMPLETED, null));

        List<RunEvent> replay = store.events("cli:direct", "run-1", 1);

        assertEquals(List.of(2L, 3L), replay.stream().map(RunEvent::sequence).toList());
        assertEquals(RunStatus.COMPLETED, store.latest("cli:direct").orElseThrow().status());
        assertEquals(List.of("run-1"), store.runs("cli:direct").stream().map(RunState::runId).toList());
        assertEquals(RunStatus.MODEL_RUNNING,
                store.stateAt("cli:direct", "run-1", 2).orElseThrow().status());
        assertTrue(store.stateAt("cli:direct", "run-1", 4).isEmpty());
    }

    @Test
    void forksIntoAnIndependentSession(@TempDir Path workspace) {
        FileRunJournalStore store = new FileRunJournalStore(workspace);
        store.append(event(1, "parent", "source", RunEventType.RUN_STARTED, RunStatus.CREATED, null));
        store.append(event(2, "parent", "source", RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null));

        RunFork fork = store.fork("source", "parent", 2, "target", "child");

        assertEquals("source", fork.parentSessionKey());
        assertEquals("target", fork.childSessionKey());
        assertTrue(store.load("source", "child").isEmpty());
        assertEquals(RunStatus.CREATED, store.load("target", "child").orElseThrow().status());
    }

    @Test
    void forksLineageAtExactEventSequence(@TempDir Path workspace) {
        FileRunJournalStore store = new FileRunJournalStore(workspace);
        store.append(event(1, "parent", "cli:direct", RunEventType.RUN_STARTED, RunStatus.CREATED, null));
        store.append(event(2, "parent", "cli:direct", RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null));
        store.append(event(3, "parent", "cli:direct", RunEventType.RUN_FINISHED, RunStatus.COMPLETED, null));

        RunFork fork = store.fork("cli:direct", "parent", 2, "child");

        assertEquals(RunStatus.MODEL_RUNNING, fork.parentState().status());
        assertEquals(2, fork.parentState().lastSequence());
        assertEquals(RunStatus.CREATED, fork.childState().status());
        RunEvent childEvent = store.events("cli:direct", "child", 0).get(0);
        assertEquals(RunEventType.RUN_FORKED, childEvent.type());
        assertEquals("parent", childEvent.details().get("parent_run_id"));
        assertEquals(2L, ((Number) childEvent.details().get("parent_sequence")).longValue());
        assertEquals(RunStatus.COMPLETED, store.load("cli:direct", "parent").orElseThrow().status());

        assertThrows(IllegalArgumentException.class, () ->
                store.fork("cli:direct", "parent", 99, "missing-sequence"));
        assertThrows(IllegalStateException.class, () ->
                store.fork("cli:direct", "parent", 2, "child"));
    }

    private static RunEvent event(
            long sequence,
            String runId,
            String sessionKey,
            RunEventType type,
            RunStatus status,
            ToolInvocationRecord invocation
    ) {
        return RunEvent.create(sequence, runId, sessionKey, 1, type, status, invocation, Map.of());
    }
}
