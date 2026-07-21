package ricbot.domain.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.team.StepAuditEventType;
import ricbot.domain.team.StepAuditRecord;
import ricbot.domain.team.StepAuditService;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceViewerServiceTest {

    @Test
    void taskIdAggregatesTraceAuditReportWorkspaceAndChangeSet(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("trace viewer");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "update readme");
        String traceId = new TraceStore(workspace).traceIdForSession(session.id());
        new TraceStore(workspace).append(new TraceEvent(
                traceId,
                null,
                "",
                session.id(),
                session.id(),
                "",
                "",
                TraceEventType.WORKER_FINISHED,
                "team",
                "worker finished",
                Map.of("taskId", task.id()),
                "2026-01-01T00:00:01Z",
                null
        ));
        new StepAuditService(workspace).append(new StepAuditRecord(
                null,
                "",
                task.id(),
                session.id(),
                StepAuditEventType.STEP_TOOL_APPLIED,
                "",
                "APPLIED",
                "tool applied",
                "",
                "read_file",
                "read README",
                "",
                "",
                "",
                "2026-01-01T00:00:02Z",
                Map.of("workspaceSessionId", "workspace_trace")
        ));
        new WorkspaceSessionStore(workspace).save(new WorkspaceSession(
                "workspace_trace",
                WorkspaceBackendType.GIT_WORKTREE,
                workspace.toString(),
                workspace.resolve(".workspaces/workspace_trace").toString(),
                "ricbot/workspace_trace",
                "trace workspace",
                WorkspaceSessionStatus.ACTIVE,
                "2026-01-01T00:00:03Z",
                "2026-01-01T00:00:03Z",
                Map.of("managedBy", "ricbot", "taskId", task.id(), "teamSessionId", session.id())
        ));
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        new ChangeSetService(workspace).createFromWorkingTree("cli:direct", session.id(), task.id());

        TraceTimeline timeline = new TraceViewerService(workspace).show(task.id());

        assertEquals(traceId, timeline.traceId());
        assertEquals(task.id(), timeline.taskId());
        assertFalse(timeline.events().isEmpty());
        assertTrue(timeline.events().stream().anyMatch(event -> event.source() == TraceTimelineSource.TRACE), timeline.events().toString());
        assertTrue(timeline.events().stream().anyMatch(event -> event.source() == TraceTimelineSource.STEP_AUDIT), timeline.events().toString());
        assertEquals("workspace_trace", timeline.relatedWorkspace().get("id"));
        assertEquals(task.id(), timeline.relatedReport().get("taskId"));
        assertFalse(timeline.relatedChangeSet().isEmpty());
    }

    @Test
    void missingSourcesProduceWarningsButNoFailure(@TempDir Path workspace) throws Exception {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("missing sources");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "no artifacts");

        TraceTimeline timeline = new TraceViewerService(workspace).show(task.id());

        assertEquals(task.id(), timeline.taskId());
        assertTrue(timeline.warnings().stream().anyMatch(value -> value.contains("no trace events")), timeline.warnings().toString());
        assertTrue(timeline.warnings().stream().anyMatch(value -> value.contains("no workspace session")), timeline.warnings().toString());
        assertTrue(timeline.warnings().stream().anyMatch(value -> value.contains("no changeset")), timeline.warnings().toString());
    }

    @Test
    void eventsAreSortedByTimestamp(@TempDir Path workspace) throws Exception {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("sorted timeline");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "sort events");
        StepAuditService audit = new StepAuditService(workspace);
        audit.append(new StepAuditRecord(null, "", task.id(), session.id(), StepAuditEventType.STEP_VERIFIED,
                "", "", "late", "", "", "", "", "", "", "2026-01-01T00:00:03Z", Map.of()));
        audit.append(new StepAuditRecord(null, "", task.id(), session.id(), StepAuditEventType.STEP_FAILED,
                "", "", "early", "", "", "", "", "", "", "2026-01-01T00:00:01Z", Map.of()));

        TraceTimeline timeline = new TraceViewerService(workspace).show(task.id());

        assertEquals("2026-01-01T00:00:01Z", timeline.events().get(0).timestamp());
    }

    @Test
    void noTraceFoundReturnsClearWarning(@TempDir Path workspace) {
        TraceTimeline timeline = new TraceViewerService(workspace).show("missing");

        assertTrue(timeline.events().isEmpty());
        assertTrue(timeline.warnings().get(0).contains("No trace found"), timeline.warnings().toString());
    }

    private static void initGitRepo(Path workspace) throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.name", "Test");
        git(workspace, "config", "user.email", "test@example.com");
        Files.writeString(workspace.resolve("README.md"), "initial\n");
        git(workspace, "add", "README.md");
        git(workspace, "commit", "-m", "init");
    }

    private static String git(Path workspace, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("git failed: " + String.join(" ", command) + "\n" + stderr + stdout);
        }
        return stdout;
    }
}
