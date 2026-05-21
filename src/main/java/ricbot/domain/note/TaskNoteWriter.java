package ricbot.domain.note;

import ricbot.domain.agent.TaskSummaryService;

import java.util.ArrayList;
import java.util.List;

public class TaskNoteWriter {
    private final NoteService noteService;

    public TaskNoteWriter(NoteService noteService) {
        this.noteService = noteService;
    }

    public WriteResult write(TaskSummaryService.TaskSummary summary, String category) {
        if (noteService == null) {
            throw new IllegalStateException("NoteService is not configured");
        }
        String normalizedCategory = normalizeCategory(summary, category);
        String title = title(summary);
        NoteEntry entry = noteService.create(
                title,
                normalizedCategory,
                normalizedCategory.equals("blockers") ? "blocker" : "task_state",
                renderMarkdown(summary),
                tags(summary, normalizedCategory)
        );
        return new WriteResult(entry.id(), entry.path(), entry.category(), entry.title());
    }

    public String renderMarkdown(TaskSummaryService.TaskSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title(summary)).append("\n\n");
        if (summary == null) {
            sb.append("当前没有可汇总的任务状态或文件变更。\n");
            return sb.toString();
        }
        if (!summary.notice().isBlank()) {
            sb.append("> ").append(summary.notice()).append("\n\n");
        }
        section(sb, "Goal", valueOrPlaceholder(summary.goal(), "未记录明确目标"));
        listSection(sb, "Changed Files", summary.changedFiles(), "未检测到文件变更");
        listSection(sb, "Key Decisions", summary.keyDecisions(), "未记录关键决策");
        listSection(sb, "Approval Records", summary.approvalRecords(), "未记录审批请求");
        listSection(sb, "Diff Reviews", summary.diffReviews(), "未记录 DiffReview");
        listSection(sb, "Suggested Tests", summary.suggestedTests(), "未生成建议测试");
        listSection(sb, "Rollback Hints", summary.rollbackHints(), "未生成回滚提示");
        listSection(sb, "Team Findings", summary.teamFindings(), "未记录 Team 状态");
        listSection(sb, "Worker Findings", summary.workerFindings(), "未记录 Worker Report");
        listSection(sb, "Developer Plan", summary.developerPlan(), "未记录 Developer Plan");
        listSection(sb, "Implementation Steps", summary.implementationSteps(), "未记录 Implementation Steps");
        listSection(sb, "Step Audit", summary.stepAudit(), "未记录 Step Audit");
        listSection(sb, "Approved Tool Calls", summary.approvedToolCalls(), "未记录已审批工具调用");
        listSection(sb, "Policy", summary.policySummary(), "未记录 Policy 评估");
        listSection(sb, "Verifier Report", summary.verifierReports(), "未记录 Verifier Report");
        listSection(sb, "ChangeSet Recommendation", summary.changeSetRecommendation(), "未记录 ChangeSet 建议");
        listSection(sb, "ChangeSet", changeSetLines(summary), "未记录 ChangeSet");
        listSection(sb, "Workspace", summary.workspaceSummary(), "未记录 Workspace session");
        listSection(sb, "SubAgent Findings", summary.subAgentFindings(), "未记录子代理摘要");
        section(sb, "Trace Summary", valueOrPlaceholder(summary.traceSummary(), "未记录 Trace Summary"));
        listSection(sb, "Test Commands", summary.testCommands(), "未记录已运行测试");
        listSection(sb, "Blockers", summary.blockers(), "未记录阻塞项");
        listSection(sb, "Next Actions", summary.nextActions(), "未记录下一步");
        return sb.toString().stripTrailing() + "\n";
    }

    private String normalizeCategory(TaskSummaryService.TaskSummary summary, String category) {
        String normalized = NoteEntry.normalizeCategory(category);
        if ("blockers".equals(normalized) && summary != null && !summary.blockers().isEmpty()) {
            return "blockers";
        }
        return "tasks";
    }

    private List<String> tags(TaskSummaryService.TaskSummary summary, String category) {
        List<String> tags = new ArrayList<>();
        tags.add("task-summary");
        tags.add(category);
        if (summary != null && !summary.blockers().isEmpty()) {
            tags.add("blocker");
        }
        if (summary != null && !summary.approvalRecords().isEmpty()) {
            tags.add("approval");
        }
        if (summary != null && !summary.diffReviews().isEmpty()) {
            tags.add("diff-review");
        }
        if (summary != null && !summary.subAgentFindings().isEmpty()) {
            tags.add("subagent");
        }
        if (summary != null && !summary.teamFindings().isEmpty()) {
            tags.add("team");
        }
        if (summary != null && !summary.verifierReports().isEmpty()) {
            tags.add("verifier");
        }
        if (summary != null && !summary.workerFindings().isEmpty()) {
            tags.add("worker");
        }
        if (summary != null && !summary.policySummary().isEmpty()) {
            tags.add("policy");
        }
        if (summary != null && !summary.developerPlan().isEmpty()) {
            tags.add("developer-plan");
        }
        if (summary != null && !summary.implementationSteps().isEmpty()) {
            tags.add("implementation-step");
        }
        if (summary != null && !summary.changeSetSummaries().isEmpty()) {
            tags.add("changeset");
        }
        if (summary != null && !summary.workspaceSummary().isEmpty()) {
            tags.add("workspace");
        }
        if (summary != null && !summary.traceSummary().isBlank()) {
            tags.add("trace");
        }
        return tags;
    }

    private String title(TaskSummaryService.TaskSummary summary) {
        if (summary == null || summary.goal() == null || summary.goal().isBlank()) {
            return "Task Summary";
        }
        return "Task Summary - " + summary.goal().trim();
    }

    private void section(StringBuilder sb, String title, String body) {
        sb.append("## ").append(title).append("\n\n");
        sb.append(body).append("\n\n");
    }

    private void listSection(StringBuilder sb, String title, List<String> values, String emptyText) {
        sb.append("## ").append(title).append("\n\n");
        if (values == null || values.isEmpty()) {
            sb.append(emptyText).append("\n\n");
            return;
        }
        for (String value : values) {
            sb.append("- ").append(value).append("\n");
        }
        sb.append("\n");
    }

    private List<String> changeSetLines(TaskSummaryService.TaskSummary summary) {
        List<String> values = new ArrayList<>();
        if (summary == null) {
            return values;
        }
        values.addAll(summary.changeSetSummaries());
        if (!summary.changeSetStatus().isBlank()) {
            values.add("status=" + summary.changeSetStatus());
        }
        if (!summary.commitHash().isBlank()) {
            values.add("commitHash=" + summary.commitHash());
        }
        if (!summary.rollbackStatus().isBlank()) {
            values.add("rollbackStatus=" + summary.rollbackStatus());
        }
        return values;
    }

    private String valueOrPlaceholder(String value, String placeholder) {
        return value != null && !value.isBlank() ? value.trim() : placeholder;
    }

    public record WriteResult(String noteId, String path, String category, String title) {
    }
}
