package ricbot.domain.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceStoreTest {

    @Test
    void appendAndLoadEvents(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);

        TraceEvent event = store.append(TraceEvent.of("", "cli:/bad session", TraceEventType.CONTEXT_BUILT, "context", "built", Map.of("status", "ok")));

        assertEquals("trace_cli_bad_session", event.traceId());
        assertTrue(Files.exists(workspace.resolve(".traces").resolve(event.traceId()).resolve("events.jsonl")));
        assertEquals(1, store.loadEvents(event.traceId()).size());
        assertEquals(TraceEventType.CONTEXT_BUILT, store.loadEvents(event.traceId()).get(0).type());
    }

    @Test
    void listTracesAndLoadLatestTrace(@TempDir Path workspace) throws Exception {
        TraceStore store = new TraceStore(workspace);
        store.append(TraceEvent.of("trace_first", "s1", TraceEventType.TEAM_EVENT, "team", "first", Map.of()));
        Thread.sleep(5);
        store.append(TraceEvent.of("trace_second", "s2", TraceEventType.APPROVAL_REQUESTED, "approval", "second", Map.of()));

        assertEquals("trace_second", store.listTraces().get(0));
        assertEquals("trace_second", store.loadLatestTrace().traceId());
    }

    @Test
    void summarizeIncludesInvolvedIdsStatusAndPath(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);
        store.append(new TraceEvent(
                "trace_summary",
                null,
                "",
                "session",
                "team_1",
                "changeset_1",
                "approval_1",
                TraceEventType.VERIFICATION_RESULT,
                "verifier",
                "pass",
                Map.of("status", "PASS"),
                null,
                null
        ));
        store.append(new TraceEvent(
                "trace_summary",
                null,
                "",
                "session",
                "",
                "changeset_1",
                "",
                TraceEventType.CHANGESET_COMMITTED,
                "change",
                "committed",
                Map.of("commitHash", "abc123", "rollbackStatus", "not executed"),
                null,
                null
        ));

        TraceStore.TraceSummary summary = store.summarize("trace_summary");

        assertEquals(2, summary.eventCount());
        assertTrue(summary.eventTypes().contains("VERIFICATION_RESULT"));
        assertTrue(summary.approvalRequestIds().contains("approval_1"));
        assertTrue(summary.changeSetIds().contains("changeset_1"));
        assertEquals("PASS", summary.verifierStatuses().get(0));
        assertEquals("abc123", summary.commitHash());
        assertEquals("not executed", summary.rollbackStatus());
        assertEquals(".traces/trace_summary/events.jsonl", summary.path());
    }

    @Test
    void writeFailureDoesNotThrowToMainFlow(@TempDir Path workspace) throws Exception {
        Path traces = workspace.resolve(".traces");
        Files.writeString(traces, "not a directory");
        TraceStore store = new TraceStore(workspace);

        TraceEvent event = store.append(TraceEvent.of("trace_fail", "session", TraceEventType.TEAM_EVENT, "team", "ignored", Map.of()));

        assertEquals("trace_fail", event.traceId());
        assertFalse(Files.isDirectory(traces));
        assertTrue(store.loadEvents("trace_fail").isEmpty());
    }
}
