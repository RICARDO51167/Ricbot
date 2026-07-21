package ricbot.domain.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.persistence.FileRuntimeFactJournal;
import ricbot.infra.persistence.RuntimeFactEvent;

import java.nio.charset.StandardCharsets;
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

    @Test
    void durableTraceFactsAreJournaledIdempotently(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);
        TraceEvent approved = new TraceEvent(
                "trace_approval", "approval-event-1", "", "session-1", "", "", "approval-1",
                TraceEventType.APPROVAL_APPROVED, "human", "approved", Map.of("scope", "tool"), null, null);

        store.append(approved);
        store.append(approved);

        var facts = new FileRuntimeFactJournal(workspace).events("session-1", 0);
        assertEquals(1, facts.size());
        assertEquals("trace.APPROVAL_APPROVED", facts.get(0).type());
        assertEquals(Map.of("scope", "tool"), facts.get(0).details().get("payload"));
        assertEquals(RuntimeFactEvent.CURRENT_SCHEMA_VERSION, facts.get(0).schemaVersion());
    }

    @Test
    void evidencePayloadBecomesArtifactReference(@TempDir Path workspace) throws Exception {
        TraceStore store = new TraceStore(workspace);
        store.append(new TraceEvent(
                "trace_verification", "verification-event-1", "", "session-2", "", "changeset-1", "",
                TraceEventType.VERIFICATION_RESULT, "verifier", "passed",
                Map.of("status", "PASS", "output", "large evidence"), null, null));

        RuntimeFactEvent fact = new FileRuntimeFactJournal(workspace).events("session-2", 0).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) fact.details().get("artifact");
        assertEquals("trace.VERIFICATION_RESULT", fact.type());
        assertFalse(fact.details().containsKey("payload"));
        assertEquals("trace.VERIFICATION_RESULT", artifact.get("artifact_type"));
        Path artifactFile = workspace.resolve(String.valueOf(artifact.get("path")));
        assertTrue(Files.isRegularFile(artifactFile));
        assertTrue(Files.readString(artifactFile, StandardCharsets.UTF_8).contains("large evidence"));
    }

    @Test
    void diagnosticTraceDoesNotBecomeCanonicalFact(@TempDir Path workspace) {
        new TraceStore(workspace).append(TraceEvent.of(
                "trace_context", "session-3", TraceEventType.CONTEXT_BUILT, "context", "built", Map.of()));

        assertTrue(new FileRuntimeFactJournal(workspace).events("session-3", 0).isEmpty());
    }

    @Test
    void loadsTraceWrittenBeforeSchemaVersionWasIntroduced(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve(".traces/trace_legacy/events.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"traceId":"trace_legacy","eventId":"old-1","sessionId":"legacy","type":"TEAM_EVENT","actor":"team","message":"old","payload":{},"createdAt":"2025-01-01T00:00:00Z"}
                """);

        TraceEvent event = new TraceStore(workspace).loadEvents("trace_legacy").get(0);
        assertEquals(TraceEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion());
        assertEquals("old-1", event.eventId());
    }
}
