package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.session.Session;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSelectionServiceTest {

    @Test
    void selectHistory_usesChineseOverlapAndDoesNotKeepUnrelatedOlderMessages(@TempDir Path workspace) {
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "我喜欢蓝色主题"),
                Map.of("role", "assistant", "content", "记住了"),
                Map.of("role", "user", "content", "飞书审批流程需要继续完善"),
                Map.of("role", "assistant", "content", "我会处理飞书审批"),
                Map.of("role", "user", "content", "最近的无关消息 1"),
                Map.of("role", "assistant", "content", "最近的无关回复 1"),
                Map.of("role", "user", "content", "最近的无关消息 2"),
                Map.of("role", "assistant", "content", "最近的无关回复 2")
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs(null, null, List.of()),
                messages,
                "继续飞书审批",
                6
        );

        String rendered = result.history().toString();
        assertEquals(6, result.history().size());
        assertTrue(rendered.contains("飞书审批流程需要继续完善"), rendered);
        assertFalse(rendered.contains("我喜欢蓝色主题"), rendered);
    }

    @Test
    void select_recordsContextBuiltTrace(@TempDir Path workspace) {
        TraceStore traceStore = new TraceStore(workspace);
        ContextSelectionService service = new ContextSelectionService(
                new MemoryStore(workspace),
                new ToolTraceSummarizer(),
                32_000,
                traceStore
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, TaskState.fromSession(new Session("test")), List.of()),
                List.of(),
                "修改 src/main/java/ricbot/tool/filesystem/WriteFileTool.java 后跑什么测试",
                6
        );

        String traceId = traceStore.traceIdForSession("session-test");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.CONTEXT_BUILT), traceStore.loadEvents(traceId).toString());
        assertTrue(result.bundle().render().contains("## trace_context"), result.bundle().render());
        assertTrue(String.valueOf(result.bundle().budgetTrace()).contains(".traces/" + traceId + "/events.jsonl"), String.valueOf(result.bundle().budgetTrace()));
    }

    @Test
    void select_addsTeamContext(@TempDir Path workspace) {
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());
        Map<String, Object> teamContext = Map.ofEntries(
                Map.entry("session", Map.of(
                        "id", "team_demo",
                        "goal", "Coordinate verifier gate",
                        "state", "VERIFYING"
                )),
                Map.entry("whiteboardPath", ".team/team_demo/whiteboard.md"),
                Map.entry("verificationPath", ".team/team_demo/verification.jsonl"),
                Map.entry("whiteboardSummary", "Leader note: worker produced a small patch."),
                Map.entry("verifierResults", List.of("teamtask_1: REJECT - missing targeted tests")),
                Map.entry("verificationReports", List.of("task=teamtask_1 | status=REJECT | riskLevel=MEDIUM | missingTests=./mvnw -q test | requiredActions=Run missing suggested tests")),
                Map.entry("workerResults", List.of("teamtask_1: EXPLORER COMPLETED - Explorer summarized workspace context")),
                Map.entry("workerReports", List.of(
                        "task=teamtask_1 | role=EXPLORER | status=COMPLETED | workspacePath=/tmp/workspace | summary=Explorer summarized workspace context",
                        "task=teamtask_2 | role=EXPLORER | status=DENIED | workspacePath=/tmp/workspace | summary=Policy-gated role tool-call write_file -> DENY | policy=decision=DENY"
                )),
                Map.entry("workerPath", ".team/team_demo/workers.jsonl"),
                Map.entry("implementationSteps", List.of("step=implstep_1 | task=teamtask_2 | type=READ | status=READY | targetPath=README.md")),
                Map.entry("blockedImplementationSteps", List.of("step=implstep_2 | task=teamtask_2 | type=EDIT | status=BLOCKED | blockedReason=dependency implstep_1 must be applied first")),
                Map.entry("implementationStepProgress", Map.of("total", 4, "draft", 1, "ready", 2, "applied", 0, "blocked", 1, "nextStep", "step=implstep_1 | type=READ")),
                Map.entry("implementationStepsPath", ".team/team_demo/implementation_steps.jsonl"),
                Map.entry("stepAuditSummary", List.of("task=teamtask_2 totalAuditRecords=3 latest=STEP_BLOCKED step=implstep_2 status=BLOCKED")),
                Map.entry("stepAuditPath", ".team/team_demo/step_audit.jsonl"),
                Map.entry("revisionRequests", List.of("teamtask_1: Revision requested: missing targeted tests")),
                Map.entry("recentEvents", List.of(Map.of(
                        "id", "event_1",
                        "type", "VERIFICATION_REJECTED",
                        "role", "VERIFIER",
                        "taskId", "teamtask_1",
                        "message", "missing targeted tests"
                )))
        );

        ContextSelectionService.SelectionResult selection = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), teamContext),
                List.of(),
                "team verifier context",
                6
        );

        String rendered = selection.bundle().render();
        assertTrue(rendered.contains("## team_context"), rendered);
        assertTrue(rendered.contains("team_demo"), rendered);
        assertTrue(rendered.contains("REJECT"), rendered);
        assertTrue(rendered.contains("Explorer summarized workspace context"), rendered);
        assertTrue(rendered.contains("Policy-gated role tool-call"), rendered);
        assertTrue(rendered.contains("implstep_1"), rendered);
        assertTrue(rendered.contains("stepAudit"), rendered);
        Map<String, Object> budgetTrace = selection.bundle().budgetTrace();
        assertTrue(String.valueOf(budgetTrace).contains("team_context"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/whiteboard.md"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/verification.jsonl"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/workers.jsonl"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/implementation_steps.jsonl"), String.valueOf(budgetTrace));
    }

    @Test
    void select_addsTeamContextFromResumedSessionSnapshot(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        var session = engine.createSession("Resume team context");
        engine.createTask(session.id(), TeamRole.REVIEWER, "Review restored state");

        TeamEngine restored = new TeamEngine(workspace);
        var resumed = restored.resumeSession(session.id());
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());

        ContextSelectionService.SelectionResult selection = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), restored.contextSnapshot(resumed.id())),
                List.of(),
                "resume team context",
                6
        );

        String rendered = selection.bundle().render();
        assertTrue(rendered.contains("## team_context"), rendered);
        assertTrue(rendered.contains(session.id()), rendered);
        assertTrue(String.valueOf(selection.bundle().budgetTrace()).contains(".team/" + session.id() + "/whiteboard.md"));
    }

    @Test
    void select_addsWorkspaceSessionSource(@TempDir Path workspace) {
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());
        Map<String, Object> workspaceContext = Map.of(
                "id", "workspace_demo",
                "type", "GIT_WORKTREE",
                "status", "ACTIVE",
                "goal", "isolated implementation",
                "path", workspace.resolve(".workspaces/workspace_demo").toString(),
                "source", ".workspaces/workspace_demo/session.json"
        );

        ContextSelectionService.SelectionResult selection = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), Map.of(), workspaceContext),
                List.of(),
                "workspace context",
                6
        );

        String rendered = selection.bundle().render();
        assertTrue(rendered.contains("## workspace_session"), rendered);
        assertTrue(rendered.contains("workspace_demo"), rendered);
        assertTrue(String.valueOf(selection.bundle().budgetTrace()).contains(".workspaces/workspace_demo/session.json"));
    }

}
