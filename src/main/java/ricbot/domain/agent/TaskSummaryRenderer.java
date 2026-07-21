package ricbot.domain.agent;

import java.util.ArrayList;
import java.util.List;

/** Renders an in-memory task summary without persisting a parallel note store. */
final class TaskSummaryRenderer {
    String render(TaskSummaryService.TaskSummary summary) {
        StringBuilder out = new StringBuilder("# Task Summary");
        if (summary != null && !summary.goal().isBlank()) out.append(" - ").append(summary.goal().trim());
        out.append("\n\n");
        if (summary == null) return out.append("当前没有可汇总的任务状态或文件变更。\n").toString();
        if (!summary.notice().isBlank()) out.append("> ").append(summary.notice()).append("\n\n");
        textSection(out, "Goal", value(summary.goal(), "未记录明确目标"));
        section(out, "Changed Files", summary.changedFiles(), "未检测到文件变更");
        section(out, "Key Decisions", summary.keyDecisions(), "未记录关键决策");
        section(out, "Approval Records", summary.approvalRecords(), "未记录审批请求");
        section(out, "Diff Reviews", summary.diffReviews(), "未记录 DiffReview");
        section(out, "Suggested Tests", summary.suggestedTests(), "未生成建议测试");
        section(out, "Rollback Hints", summary.rollbackHints(), "未生成回滚提示");
        section(out, "Team Findings", summary.teamFindings(), "未记录 Team 状态");
        section(out, "Worker Findings", summary.workerFindings(), "未记录 Worker Report");
        section(out, "Developer Plan", summary.developerPlan(), "未记录 Developer Plan");
        section(out, "Implementation Steps", summary.implementationSteps(), "未记录 Implementation Steps");
        section(out, "Step Audit", summary.stepAudit(), "未记录 Step Audit");
        section(out, "Approved Tool Calls", summary.approvedToolCalls(), "未记录已审批工具调用");
        section(out, "Policy", summary.policySummary(), "未记录 Policy 评估");
        section(out, "Verifier Report", summary.verifierReports(), "未记录 Verifier Report");
        section(out, "ChangeSet Recommendation", summary.changeSetRecommendation(), "未记录 ChangeSet 建议");
        section(out, "ChangeSet", changeSetLines(summary), "未记录 ChangeSet");
        section(out, "Workspace", summary.workspaceSummary(), "未记录 Workspace session");
        textSection(out, "Trace Summary", value(summary.traceSummary(), "未记录 Trace Summary"));
        section(out, "Test Commands", summary.testCommands(), "未记录已运行测试");
        section(out, "Blockers", summary.blockers(), "未记录阻塞项");
        section(out, "Next Actions", summary.nextActions(), "未记录下一步");
        return out.toString().stripTrailing() + "\n";
    }

    private void textSection(StringBuilder out, String title, String text) {
        out.append("## ").append(title).append("\n\n").append(text).append("\n\n");
    }

    private void section(StringBuilder out, String title, List<String> values, String empty) {
        out.append("## ").append(title).append("\n\n");
        if (values == null || values.isEmpty()) out.append(empty).append("\n\n");
        else {
            values.forEach(value -> out.append("- ").append(value).append("\n"));
            out.append("\n");
        }
    }

    private List<String> changeSetLines(TaskSummaryService.TaskSummary summary) {
        List<String> values = new ArrayList<>(summary.changeSetSummaries());
        if (!summary.changeSetStatus().isBlank()) values.add("status=" + summary.changeSetStatus());
        if (!summary.commitHash().isBlank()) values.add("commitHash=" + summary.commitHash());
        if (!summary.rollbackStatus().isBlank()) values.add("rollbackStatus=" + summary.rollbackStatus());
        return values;
    }

    private String value(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
