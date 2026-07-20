package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileRunCheckpointStoreTest {

    @Test
    void roundTripsCheckpointWithNullAssistantContentAndUnsafeSessionKey(@TempDir Path workspace) throws Exception {
        FileRunCheckpointStore store = new FileRunCheckpointStore(workspace);
        String sessionKey = "channel:../../unsafe/user";
        RunCheckpoint expected = checkpoint(sessionKey, "run-1:2:MODEL_RESPONSE_RECEIVED", 2,
                RunCheckpointPhase.MODEL_RESPONSE_RECEIVED, List.of(), List.of(Map.of(
                        "id", "call-1",
                        "function", Map.of("name", "write_file")
                )));

        store.save(expected);

        RunCheckpoint actual = store.load(sessionKey).orElseThrow();
        assertEquals(expected, actual);
        try (var files = Files.list(store.directory())) {
            List<Path> paths = files.filter(Files::isRegularFile).toList();
            assertEquals(1, paths.size());
            assertFalse(paths.get(0).getFileName().toString().contains("unsafe"));
            assertTrue(paths.get(0).getFileName().toString().matches("[0-9a-f]{64}\\.json"));
        }
    }

    @Test
    void overwritesLatestCheckpointAtomicallyAndDeletesIt(@TempDir Path workspace) {
        FileRunCheckpointStore store = new FileRunCheckpointStore(workspace);
        store.save(checkpoint("cli:direct", "run-1:1:MODEL_RESPONSE_RECEIVED", 1,
                RunCheckpointPhase.MODEL_RESPONSE_RECEIVED, List.of(), List.of(Map.of("id", "pending"))));
        store.save(checkpoint("cli:direct", "run-1:1:TOOLS_COMPLETED", 1,
                RunCheckpointPhase.TOOLS_COMPLETED, List.of(Map.of("tool_call_id", "pending")), List.of()));

        RunCheckpoint latest = store.load("cli:direct").orElseThrow();
        assertEquals(RunCheckpointPhase.TOOLS_COMPLETED, latest.phase());
        assertEquals(1, latest.completedToolResults().size());
        assertTrue(latest.pendingToolCalls().isEmpty());

        store.delete("cli:direct");
        assertTrue(store.load("cli:direct").isEmpty());
        assertEquals(2, store.history("cli:direct").size());
        assertTrue(store.loadVersion("cli:direct", "run-1:1:MODEL_RESPONSE_RECEIVED").isPresent());
    }

    @Test
    void keepsOrderedAddressableCheckpointHistory(@TempDir Path workspace) {
        FileRunCheckpointStore store = new FileRunCheckpointStore(workspace);
        RunCheckpoint first = checkpoint("session", "cp-1", 1,
                RunCheckpointPhase.MODEL_RESPONSE_RECEIVED, List.of(), List.of());
        RunCheckpoint second = new RunCheckpoint(
                first.schemaVersion(), "cp-2", first.runId(), first.journalRunId(), first.sessionKey(),
                9, 3, 2, RunCheckpointPhase.TOOLS_COMPLETED, AgentNodeState.initial(), first.runMessages(),
                first.assistantMessage(), List.of(), List.of(), first.taskState(), "", first.updatedAt().plusSeconds(1));

        store.save(second);
        store.save(first);

        assertEquals(List.of("cp-1", "cp-2"),
                store.history("session").stream().map(RunCheckpoint::checkpointId).toList());
        assertEquals(second, store.loadVersion("session", "cp-2").orElseThrow());
        assertEquals(first, store.load("session").orElseThrow());
    }

    @Test
    void rejectsBlankSessionKeys(@TempDir Path workspace) {
        FileRunCheckpointStore store = new FileRunCheckpointStore(workspace);

        assertThrows(IllegalArgumentException.class, () -> store.load("  "));
        assertThrows(IllegalArgumentException.class, () -> store.delete(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkpointOwnsADeepImmutableSnapshotAndExportsMutableCopies() {
        List<Object> arguments = new ArrayList<>();
        arguments.add("before");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "write_file");
        function.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call-1");
        call.put("function", function);

        RunCheckpoint checkpoint = checkpoint(
                "cli:direct",
                "run-1:1:MODEL_RESPONSE_RECEIVED",
                1,
                RunCheckpointPhase.MODEL_RESPONSE_RECEIVED,
                List.of(),
                List.of(call)
        );

        arguments.add("after");
        function.put("name", "changed");
        Map<String, Object> storedFunction = (Map<String, Object>) checkpoint.pendingToolCalls().get(0).get("function");
        assertEquals("write_file", storedFunction.get("name"));
        assertEquals(List.of("before"), storedFunction.get("arguments"));
        assertThrows(UnsupportedOperationException.class, () -> storedFunction.put("name", "mutate"));

        Map<String, Object> exported = checkpoint.toSessionPayload();
        List<Map<String, Object>> exportedCalls = (List<Map<String, Object>>) exported.get("pending_tool_calls");
        ((Map<String, Object>) exportedCalls.get(0).get("function")).put("name", "export-only");
        assertEquals("write_file", storedFunction.get("name"));
    }

    private static RunCheckpoint checkpoint(
            String sessionKey,
            String checkpointId,
            int iteration,
            RunCheckpointPhase phase,
            List<Map<String, Object>> completed,
            List<Map<String, Object>> pending
    ) {
        java.util.LinkedHashMap<String, Object> assistant = new java.util.LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", pending);
        return new RunCheckpoint(
                RunCheckpoint.CURRENT_SCHEMA_VERSION,
                checkpointId,
                "run-1",
                "run-1",
                sessionKey,
                2,
                0,
                iteration,
                phase,
                AgentNodeState.initial(),
                List.of(),
                assistant,
                completed,
                pending,
                Map.of("goal", "test"),
                "",
                Instant.parse("2026-07-20T00:00:00Z")
        );
    }
}
