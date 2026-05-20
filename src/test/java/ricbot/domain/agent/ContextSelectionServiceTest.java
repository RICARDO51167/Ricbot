package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceType;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.note.NoteService;
import ricbot.domain.rag.WorkspaceRagService;
import ricbot.domain.session.Session;
import ricbot.domain.subagent.SubAgentResult;
import ricbot.domain.subagent.SubAgentRole;
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
    void select_addsProjectNotesAndWorkspaceKnowledge(@TempDir Path workspace) throws Exception {
        MemoryStore memoryStore = new MemoryStore(workspace);
        NoteService noteService = new NoteService(workspace);
        noteService.create(
                "MCP timeout blocker",
                "blockers",
                "blocker",
                "MCP streamable HTTP timeout is blocked by transport retry behavior.",
                List.of("mcp", "timeout")
        );
        Files.createDirectories(workspace.resolve("src/main/java/ricbot/integration/mcp"));
        Files.writeString(workspace.resolve("src/main/java/ricbot/integration/mcp/MCPAdapters.java"), """
                package ricbot.integration.mcp;

                public class MCPAdapters {
                    public void handleStreamableHttpTimeout() {
                    }
                }
                """);
        WorkspaceRagService ragService = new WorkspaceRagService(workspace);
        ragService.indexWorkspace();

        ContextSelectionService service = new ContextSelectionService(
                memoryStore,
                new ToolTraceSummarizer(),
                32_000,
                noteService,
                ragService
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, TaskState.fromSession(new Session("test")), List.of()),
                List.of(),
                "继续处理 MCP timeout",
                6
        );

        String rendered = result.bundle().render();
        assertTrue(rendered.contains("## project_notes"), rendered);
        assertTrue(rendered.contains("MCP timeout blocker"), rendered);
        assertTrue(rendered.contains("## workspace_knowledge"), rendered);
        assertTrue(rendered.contains("MCPAdapters.java"), rendered);
        Map<String, Object> budgetTrace = result.bundle().budgetTrace();
        assertTrue(String.valueOf(budgetTrace).contains("project_notes"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains("workspace_knowledge"), String.valueOf(budgetTrace));
    }

    @Test
    void select_addsVerifiedExperienceOnly(@TempDir Path workspace) {
        MemoryStore memoryStore = new MemoryStore(workspace);
        ExperienceStore experienceStore = new ExperienceStore(workspace);
        ExperienceEntry verified = experienceStore.addCandidate(experience(
                "Run filesystem tests",
                "Run filesystem tests after changing write_file or edit_file.",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                0.8d
        ));
        experienceStore.verify(verified.id());
        experienceStore.addCandidate(experience(
                "Candidate should not enter context",
                "Candidate filesystem advice.",
                List.of("src/main/java/ricbot/tool/filesystem/EditFileTool.java"),
                1.0d
        ));

        ContextSelectionService service = new ContextSelectionService(
                memoryStore,
                new ToolTraceSummarizer(),
                32_000,
                null,
                null,
                experienceStore
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, TaskState.fromSession(new Session("test")), List.of()),
                List.of(),
                "修改 src/main/java/ricbot/tool/filesystem/EditFileTool.java 后跑什么测试",
                6
        );

        String rendered = result.bundle().render();
        assertTrue(rendered.contains("## verified_experience"), rendered);
        assertTrue(rendered.contains("Run filesystem tests"), rendered);
        assertFalse(rendered.contains("Candidate should not enter context"), rendered);

        Map<String, Object> budgetTrace = result.bundle().budgetTrace();
        assertTrue(String.valueOf(budgetTrace).contains("verified_experience"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains("experience/verified.jsonl:" + verified.id()), String.valueOf(budgetTrace));
        assertEquals(1, experienceStore.listUsage(verified.id()).size());
        assertEquals("session-test", experienceStore.listUsage(verified.id()).get(0).sessionId());
    }

    @Test
    void select_recordsContextBuiltAndExperienceHitTrace(@TempDir Path workspace) {
        ExperienceStore experienceStore = new ExperienceStore(workspace);
        ExperienceEntry verified = experienceStore.addCandidate(experience(
                "Run filesystem tests",
                "Run filesystem tests after changing write_file or edit_file.",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                0.8d
        ));
        experienceStore.verify(verified.id());
        TraceStore traceStore = new TraceStore(workspace);
        ContextSelectionService service = new ContextSelectionService(
                new MemoryStore(workspace),
                new ToolTraceSummarizer(),
                32_000,
                null,
                null,
                experienceStore,
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
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.EXPERIENCE_HIT), traceStore.loadEvents(traceId).toString());
        assertTrue(result.bundle().render().contains("## trace_context"), result.bundle().render());
        assertTrue(String.valueOf(result.bundle().budgetTrace()).contains(".traces/" + traceId + "/events.jsonl"), String.valueOf(result.bundle().budgetTrace()));
    }

    @Test
    void select_skipsRejectedExperience(@TempDir Path workspace) {
        ExperienceStore experienceStore = new ExperienceStore(workspace);
        ExperienceEntry rejected = experienceStore.addCandidate(experience(
                "Rejected filesystem rule",
                "Rejected content must not enter context.",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                1.0d
        ));
        experienceStore.reject(rejected.id());
        ContextSelectionService service = new ContextSelectionService(
                new MemoryStore(workspace),
                new ToolTraceSummarizer(),
                32_000,
                null,
                null,
                experienceStore
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs(null, TaskState.fromSession(new Session("test")), List.of()),
                List.of(),
                "filesystem WriteFileTool",
                6
        );

        assertFalse(result.bundle().render().contains("Rejected filesystem rule"), result.bundle().render());
        assertTrue(result.bundle().section("verified_experience").isEmpty());
        assertTrue(experienceStore.listUsage(rejected.id()).isEmpty());
    }

    @Test
    void select_doesNotRecordUsageForSearchResultsThatDoNotEnterContext(@TempDir Path workspace) {
        ExperienceStore experienceStore = new ExperienceStore(workspace);
        ExperienceEntry verified = experienceStore.addCandidate(experience(
                "Run filesystem tests",
                "Run filesystem tests after changing write_file or edit_file.",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                0.8d
        ));
        experienceStore.verify(verified.id());
        ContextSelectionService service = new ContextSelectionService(
                new MemoryStore(workspace),
                new ToolTraceSummarizer(),
                32_000,
                null,
                null,
                experienceStore
        );

        service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, TaskState.fromSession(new Session("test")), List.of()),
                List.of(),
                "completely unrelated query",
                6
        );

        assertTrue(experienceStore.listUsage(verified.id()).isEmpty());
    }

    @Test
    void select_addsSubAgentSummaries(@TempDir Path workspace) {
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());
        SubAgentResult result = new SubAgentResult(
                "subtask_plan",
                SubAgentRole.PLANNER,
                "Plan the context upgrade.",
                List.of("Keep the change small."),
                List.of("Do not change provider path."),
                List.of("./mvnw -q -Dtest='ricbot.domain.agent.*Test' test"),
                List.of("src/main/java/ricbot/domain/agent/ContextSelectionService.java"),
                0.72d,
                null
        );

        ContextSelectionService.SelectionResult selection = service.select(
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), List.of(result)),
                List.of(),
                "context upgrade",
                6
        );

        String rendered = selection.bundle().render();
        assertTrue(rendered.contains("## subagent_summaries"), rendered);
        assertTrue(rendered.contains("Plan the context upgrade"), rendered);
        Map<String, Object> budgetTrace = selection.bundle().budgetTrace();
        assertTrue(String.valueOf(budgetTrace).contains("subagent_summaries"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains("subagent:subtask_plan"), String.valueOf(budgetTrace));
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
                Map.entry("workerReports", List.of("task=teamtask_1 | role=EXPLORER | status=COMPLETED | workspacePath=/tmp/workspace | summary=Explorer summarized workspace context")),
                Map.entry("workerPath", ".team/team_demo/workers.jsonl"),
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
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), List.of(), teamContext),
                List.of(),
                "team verifier context",
                6
        );

        String rendered = selection.bundle().render();
        assertTrue(rendered.contains("## team_context"), rendered);
        assertTrue(rendered.contains("team_demo"), rendered);
        assertTrue(rendered.contains("status=REJECT"), rendered);
        assertTrue(rendered.contains("Explorer summarized workspace context"), rendered);
        Map<String, Object> budgetTrace = selection.bundle().budgetTrace();
        assertTrue(String.valueOf(budgetTrace).contains("team_context"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/whiteboard.md"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/verification.jsonl"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains(".team/team_demo/workers.jsonl"), String.valueOf(budgetTrace));
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
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), List.of(), restored.contextSnapshot(resumed.id())),
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
                new ContextSelectionService.SessionPreparedInputs("session-test", null, null, List.of(), List.of(), Map.of(), workspaceContext),
                List.of(),
                "workspace context",
                6
        );

        String rendered = selection.bundle().render();
        assertTrue(rendered.contains("## workspace_session"), rendered);
        assertTrue(rendered.contains("workspace_demo"), rendered);
        assertTrue(String.valueOf(selection.bundle().budgetTrace()).contains(".workspaces/workspace_demo/session.json"));
    }

    private ExperienceEntry experience(String title, String content, List<String> files, double confidence) {
        return ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                title,
                content,
                "When changing filesystem tools.",
                "Verified from previous task.",
                "task_summary",
                "V3.6",
                files,
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                confidence
        );
    }
}
