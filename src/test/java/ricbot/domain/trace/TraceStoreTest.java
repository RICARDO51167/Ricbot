package ricbot.domain.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceStoreTest {
    @Test
    void appendAndLoadUsesRuntimeDatabase(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);
        TraceEvent event = store.append(TraceEvent.of("", "cli:/bad session", TraceEventType.CONTEXT_BUILT,
                "context", "built", Map.of("status", "ok")));
        assertEquals("trace_cli_bad_session", event.traceId());
        assertTrue(Files.isRegularFile(workspace.resolve(SqliteRuntimeStore.DATABASE_RELATIVE_PATH)));
        assertFalse(Files.exists(workspace.resolve(".traces")));
        assertEquals(TraceEventType.CONTEXT_BUILT, store.loadEvents(event.traceId()).get(0).type());
    }

    @Test
    void duplicateEventIsIdempotent(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);
        TraceEvent event = new TraceEvent("trace", "event-1", "", "session", "", "", "",
                TraceEventType.APPROVAL_APPROVED, "human", "approved", Map.of(), null, null);
        store.append(event); store.append(event);
        assertEquals(1, store.loadEvents("trace").size());
    }

    @Test
    void listAndSummaryAreDatabaseProjections(@TempDir Path workspace) throws Exception {
        TraceStore store = new TraceStore(workspace);
        store.append(new TraceEvent("trace_first", null, "", "session", "", "change", "approval",
                TraceEventType.VERIFICATION_RESULT, "verifier", "pass", Map.of("status", "PASS"), null, null));
        Thread.sleep(2);
        store.append(TraceEvent.of("trace_second", "s2", TraceEventType.TEAM_EVENT, "team", "second", Map.of()));
        assertEquals("trace_second", store.listTraces().get(0));
        TraceStore.TraceSummary summary = store.summarize("trace_first");
        assertEquals(1, summary.eventCount());
        assertEquals("PASS", summary.verifierStatuses().get(0));
        assertTrue(summary.path().startsWith("sqlite:.ricbot/application.db#traces/"));
    }
}
