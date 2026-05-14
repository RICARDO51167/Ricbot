package ricbot.domain.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
