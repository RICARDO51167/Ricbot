package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.session.Session;
import ricbot.domain.subagent.SubAgentResult;
import ricbot.domain.subagent.SubAgentRole;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TaskSummaryServiceTest {

    @Test
    void summarizeCurrentTask_buildsStructuredSummary() {
        TaskState taskState = TaskState.fromMap(Map.of(
                "goal", "实现 V2.5 可观测闭环",
                "next_action", "把摘要写入 NoteService"
        ));
        List<Map<String, Object>> toolTrace = List.of(
                Map.of(
                        "tool_name", "edit_file",
                        "status", "ok",
                        "arguments_summary", "path=src/main/java/ricbot/domain/agent/AgentCommands.java"
                ),
                Map.of(
                        "tool_name", "exec",
                        "status", "ok",
                        "arguments_summary", "./mvnw -q -Dtest=AgentCommandsTest test",
                        "result_summary", "tests passed"
                ),
                Map.of(
                        "tool_name", "rag",
                        "status", "error",
                        "detail", "index missing"
                )
        );

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(
                taskState,
                toolTrace,
                List.of("src/main/java/ricbot/domain/rag/WorkspaceRagService.java"),
                List.of("./mvnw -q -Dtest=RagToolTest test"),
                List.of("Use sha256 for incremental RAG refresh")
        );

        Map<String, Object> map = summary.toMap();
        assertEquals("实现 V2.5 可观测闭环", map.get("goal"));
        assertTrue(String.valueOf(map.get("changed_files")).contains("AgentCommands.java"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("changed_files")).contains("WorkspaceRagService.java"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("key_decisions")).contains("sha256"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("test_commands")).contains("RagToolTest"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("blockers")).contains("index missing"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("next_actions")).contains("NoteService"), String.valueOf(map));
    }

    @Test
    void summarizeCurrentTask_extractsDiffReviewSuggestedTestsRollbackHintsAndApprovals() {
        TaskState taskState = TaskState.fromMap(Map.of(
                "goal", "深化 DiffReview",
                "next_action", "运行建议测试"
        ));
        List<Map<String, Object>> toolTrace = List.of(
                Map.of(
                        "tool_name", "write_file",
                        "status", "ok",
                        "arguments_summary", "path=src/main/java/ricbot/tool/filesystem/DiffReviewService.java",
                        "result_summary", """
                                文件已写入
                                DiffReview
                                summary: Edited src/main/java/ricbot/tool/filesystem/DiffReviewService.java (+2/-1)
                                changedFiles: src/main/java/ricbot/tool/filesystem/DiffReviewService.java
                                riskLevel: MEDIUM
                                suggestedTests: ./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test
                                rollbackHint: git checkout -- src/main/java/ricbot/tool/filesystem/DiffReviewService.java
                                """
                ),
                Map.of(
                        "tool_name", "exec",
                        "status", "ok",
                        "arguments_summary", "./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test",
                        "result_summary", "tests passed"
                ),
                Map.of(
                        "tool_name", "edit_file",
                        "status", "ok",
                        "result_summary", """
                                需要审批后才能执行。
                                requestId: approval_abc123
                                riskLevel: MEDIUM
                                """
                )
        );

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(
                taskState,
                toolTrace,
                List.of(),
                List.of(),
                List.of("DiffReview 作为任务笔记输入")
        );

        Map<String, Object> map = summary.toMap();
        assertTrue(String.valueOf(map.get("diff_reviews")).contains("DiffReviewService.java"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("suggested_tests")).contains("ricbot.tool.filesystem.*Test"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("rollback_hints")).contains("git checkout --"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("approval_records")).contains("approval_abc123"), String.valueOf(map));
        assertTrue(String.valueOf(map.get("next_actions")).contains("运行建议测试"), String.valueOf(map));
    }

    @Test
    void summarizeCurrentTask_returnsReadableNoticeWhenEmpty() {
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(
                TaskState.fromMap(Map.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(summary.notice().contains("没有可汇总"), summary.notice());
    }

    @Test
    void summarizeCurrentTask_includesSubAgentFindingsFromSession() {
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "V4.1 subagent summaries"
        ));
        session.getMetadata().put(SessionRuntimeKeys.SUBAGENT_RESULTS_KEY, List.of(
                new SubAgentResult(
                        "subtask_review",
                        SubAgentRole.REVIEWER,
                        "Review current diff.",
                        List.of("DiffReview is present."),
                        List.of("Run targeted tests."),
                        List.of("./mvnw -q -Dtest='ricbot.domain.agent.*Test' test"),
                        List.of("src/main/java/ricbot/domain/agent/TaskSummaryService.java"),
                        0.7d,
                        null
                ).toMap()
        ));

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);

        assertTrue(summary.subAgentFindings().toString().contains("REVIEWER"), summary.subAgentFindings().toString());
        assertTrue(summary.toMap().containsKey("subagent_findings"));
    }

    @Test
    void summarizeCurrentTask_includesTeamFindingsFromSession() {
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "V4.1 team engine"
        ));
        session.getMetadata().put(SessionRuntimeKeys.TEAM_CONTEXT_KEY, Map.of(
                "session", Map.of(
                        "id", "team_demo",
                        "state", "REVISING",
                        "goal", "Implement verifier gate"
                ),
                "whiteboardSummary", "Leader note: verifier rejected missing tests.",
                "workerResults", List.of("teamtask_1: EXPLORER COMPLETED - Explorer summarized workspace context"),
                "workerReports", List.of("task=teamtask_1 | role=EXPLORER | status=COMPLETED | summary=Explorer summarized workspace context"),
                "verifierResults", List.of("teamtask_1: REJECT - missing tests"),
                "verificationReports", List.of("task=teamtask_1 | status=REJECT | riskLevel=MEDIUM | missingTests=./mvnw -q test | requiredActions=Run missing suggested tests"),
                "revisionRequests", List.of("teamtask_1: Revision requested: missing tests")
        ));

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);

        assertTrue(summary.teamFindings().toString().contains("team_demo"), summary.teamFindings().toString());
        assertTrue(summary.teamFindings().toString().contains("verifier:"), summary.teamFindings().toString());
        assertTrue(summary.teamFindings().toString().contains("revision:"), summary.teamFindings().toString());
        assertTrue(summary.verifierReports().toString().contains("status=REJECT"), summary.verifierReports().toString());
        assertTrue(summary.verifierReports().toString().contains("missingTests="), summary.verifierReports().toString());
        assertTrue(summary.verifierReports().toString().contains("requiredActions="), summary.verifierReports().toString());
        assertTrue(summary.workerFindings().toString().contains("EXPLORER"), summary.workerFindings().toString());
        assertTrue(summary.toMap().containsKey("team_findings"));
        assertTrue(summary.toMap().containsKey("verifier_reports"));
        assertTrue(summary.toMap().containsKey("worker_findings"));
    }

    @Test
    void summarizeCurrentTask_includesChangeSetSummaryFromSession() {
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "V4.4 changeset summary"
        ));
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_SUMMARY_KEY,
                "changeset_demo status=APPROVED files=2 summary=Changed files: 2");
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_STATUS_KEY, "COMMITTED");
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_COMMIT_HASH_KEY, "abc123");
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_ROLLBACK_STATUS_KEY, "not executed");

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);

        assertTrue(summary.changeSetSummaries().toString().contains("changeset_demo"), summary.changeSetSummaries().toString());
        assertEquals("COMMITTED", summary.changeSetStatus());
        assertEquals("abc123", summary.commitHash());
        assertEquals("not executed", summary.rollbackStatus());
        assertTrue(summary.toMap().containsKey("changeset_summary"));
        assertTrue(String.valueOf(summary.toMap().get("changeset_summary")).contains("APPROVED"));
        assertEquals("abc123", summary.toMap().get("commit_hash"));
        assertEquals("not executed", summary.toMap().get("rollback_status"));
    }

    @Test
    void summarizeCurrentTask_includesTraceSummaryFromSession() {
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "V4.6 unified trace"
        ));
        session.getMetadata().put(SessionRuntimeKeys.TRACE_SUMMARY_KEY, """
                trace trace_cli_direct
                path: .traces/trace_cli_direct/events.jsonl
                eventCount: 2
                eventTypes: CONTEXT_BUILT, CHANGESET_CREATED
                """);

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);

        assertTrue(summary.traceSummary().contains("trace_cli_direct"), summary.traceSummary());
        assertTrue(String.valueOf(summary.toMap().get("trace_summary")).contains(".traces/trace_cli_direct/events.jsonl"), summary.toMap().toString());
    }

    @Test
    void summarizeCurrentTask_includesWorkspaceSummaryFromSession() {
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "V4.7 workspace backend"
        ));
        session.getMetadata().put(SessionRuntimeKeys.WORKSPACE_SUMMARY_KEY,
                "workspace workspace_demo | type: GIT_WORKTREE | status: ACTIVE | goal: isolated implementation");
        session.getMetadata().put(SessionRuntimeKeys.WORKSPACE_SOURCE_KEY, ".workspaces/workspace_demo/session.json");

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);

        assertTrue(summary.workspaceSummary().toString().contains("workspace_demo"), summary.workspaceSummary().toString());
        assertTrue(String.valueOf(summary.toMap().get("workspace_summary")).contains(".workspaces/workspace_demo/session.json"), summary.toMap().toString());
    }

    @Test
    void summarizeCurrentTask_includesResumedTeamFindings(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        var team = engine.createSession("Resumed team summary");
        engine.createTask(team.id(), TeamRole.DEVELOPER, "Implement resumed summary");
        TeamEngine restored = new TeamEngine(workspace);
        var resumed = restored.resumeSession(team.id());

        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TEAM_CONTEXT_KEY, restored.contextSnapshot(resumed.id()));

        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);

        assertTrue(summary.teamFindings().toString().contains("Resumed team summary"), summary.teamFindings().toString());
        assertTrue(summary.teamFindings().toString().contains("whiteboard:"), summary.teamFindings().toString());
    }
}
