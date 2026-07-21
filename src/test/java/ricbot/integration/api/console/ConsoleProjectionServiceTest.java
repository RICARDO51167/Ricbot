package ricbot.integration.api.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.FileRunJournalStore;
import ricbot.domain.agent.RunEvent;
import ricbot.domain.agent.RunEventType;
import ricbot.domain.agent.RunStatus;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.worker.WorkerRuntime;
import ricbot.infra.persistence.FileRuntimeFactJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleProjectionServiceTest {
    @Test
    void rebuildsEventsFromRunFactTeamAndWorkerState(@TempDir Path workspace) {
        FileRunJournalStore runs = new FileRunJournalStore(workspace);
        runs.append(RunEvent.create(1, "run-1", "session-1", 0,
                RunEventType.RUN_STARTED, RunStatus.CREATED, null, Map.of("summary", "started")));
        new FileRuntimeFactJournal(workspace).append(
                "approval-1", "session-1", "trace.APPROVAL_APPROVED", "human", "approved",
                Map.of("approval_request_id", "a-1"), Instant.now());
        new WorkerRuntime(workspace).create("session-1", "developer", "work", "", "worker-key", Map.of());
        String teamId = new TeamEngine(workspace, new TraceStore(workspace)).createSession("team work").id();

        ConsoleProjectionService projection = new ConsoleProjectionService(workspace, runs);
        List<ConsoleEvent> sessionEvents = projection.eventsForSession("session-1");
        Set<String> sources = projection.allEvents(List.of("session-1")).stream()
                .map(ConsoleEvent::source).collect(Collectors.toSet());

        assertTrue(sessionEvents.stream().anyMatch(event -> "run_journal".equals(event.source())));
        assertTrue(sessionEvents.stream().anyMatch(event -> "approval_approved".equals(event.name())));
        assertTrue(sessionEvents.stream().anyMatch(event -> "worker_state".equals(event.source())));
        assertTrue(sources.contains("team_state"), "missing team projection for " + teamId);
        assertFalse(Files.exists(workspace.resolve(".ricbot/console-events.jsonl")));
    }

    @Test
    void actionAuditWritesFactJournalWithoutLegacyFile(@TempDir Path workspace) throws Exception {
        ConsoleActionAuditService audit = new ConsoleActionAuditService(workspace);
        ConsoleActionAuditRecord record = new ConsoleActionAuditRecord(
                "action-1", "2026-07-21T00:00:00Z", "run_cancel", "RUN", "run-1", "SUCCESS",
                "operator", "127.0.0.1", "test", "cancelled", List.of(), "request-1");

        assertTrue(audit.append(record, "session-1").isEmpty());

        assertEquals("action-1", audit.recent(10).get(0).id());
        assertFalse(Files.exists(audit.auditFile()));
        assertEquals("run_cancel", new ConsoleProjectionService(workspace, null)
                .eventsForSession("session-1").get(0).name());
    }

    @Test
    void auditProjectionFailureIsReportedWithoutThrowing(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".ricbot"), "not a directory");
        ConsoleActionAuditRecord record = new ConsoleActionAuditRecord(
                "action-failed", null, "run_submit", "RUN", "run-1", "SUCCESS",
                "operator", "", "", "submitted", List.of(), "request-failed");

        List<String> warnings = new ConsoleActionAuditService(workspace).append(record, "session-1");

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("audit write failed"));
    }
}
