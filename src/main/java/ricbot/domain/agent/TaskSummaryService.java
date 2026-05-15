package ricbot.domain.agent;

import ricbot.domain.session.Session;
import ricbot.domain.subagent.SubAgentOrchestrator;
import ricbot.domain.subagent.SubAgentResult;
import ricbot.domain.team.TeamEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TaskSummaryService {

    public TaskSummary summarizeCurrentTask(Session session) {
        TaskState taskState = TaskState.fromSession(session);
        List<Map<String, Object>> toolTrace = readTrace(session, SessionRuntimeKeys.TOOL_TRACE_KEY);
        return summarizeCurrentTask(
                taskState,
                toolTrace,
                List.of(),
                List.of(),
                List.of(),
                renderTeamFindings(TeamEngine.contextFromSession(session)),
                renderSubAgentFindings(SubAgentOrchestrator.resultsFromSession(session))
        );
    }

    TaskSummary summarizeCurrentTask(
            TaskState taskState,
            List<Map<String, Object>> toolTrace,
            List<String> modifiedFiles,
            List<String> testResults,
            List<String> keyDecisions
    ) {
        return summarizeCurrentTask(taskState, toolTrace, modifiedFiles, testResults, keyDecisions, List.of(), List.of());
    }

    TaskSummary summarizeCurrentTask(
            TaskState taskState,
            List<Map<String, Object>> toolTrace,
            List<String> modifiedFiles,
            List<String> testResults,
            List<String> keyDecisions,
            List<String> teamFindings,
            List<String> subAgentFindings
    ) {
        List<String> changedFiles = new ArrayList<>(dedupe(modifiedFiles));
        changedFiles.addAll(inferChangedFiles(toolTrace));
        changedFiles = new ArrayList<>(dedupe(changedFiles));

        List<String> testCommands = new ArrayList<>(dedupe(testResults));
        testCommands.addAll(inferTestCommands(toolTrace));
        testCommands = new ArrayList<>(dedupe(testCommands));

        List<String> blockers = new ArrayList<>();
        if (taskState != null && taskState.blockedReason() != null && !taskState.blockedReason().isBlank()) {
            blockers.add(taskState.blockedReason());
        }
        blockers.addAll(inferBlockers(toolTrace));
        blockers = new ArrayList<>(dedupe(blockers));

        List<String> approvalRecords = inferApprovalRecords(toolTrace);
        List<String> diffReviews = inferDiffReviews(toolTrace);
        List<String> suggestedTests = inferDiffField(toolTrace, "suggestedTests:");
        List<String> rollbackHints = inferDiffField(toolTrace, "rollbackHint:");

        List<String> nextActions = new ArrayList<>();
        if (taskState != null && taskState.nextAction() != null && !taskState.nextAction().isBlank()) {
            nextActions.add(taskState.nextAction());
        }
        if (!suggestedTests.isEmpty()) {
            nextActions.add("运行建议测试：" + String.join("; ", suggestedTests));
        }

        return new TaskSummary(
                taskState != null ? taskState.goal() : "",
                changedFiles,
                new ArrayList<>(dedupe(keyDecisions)),
                testCommands,
                blockers,
                new ArrayList<>(dedupe(nextActions)),
                approvalRecords,
                diffReviews,
                suggestedTests,
                rollbackHints,
                new ArrayList<>(dedupe(teamFindings)),
                new ArrayList<>(dedupe(subAgentFindings)),
                notice(taskState, changedFiles, toolTrace)
        );
    }

    private List<String> renderTeamFindings(Map<String, Object> teamContext) {
        if (teamContext == null || teamContext.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Object rawSession = teamContext.get("session");
        if (rawSession instanceof Map<?, ?> session) {
            String id = string(session.get("id"));
            String state = string(session.get("state"));
            String goal = string(session.get("goal"));
            out.add("team " + id + " state=" + state + " goal=" + goal);
        }
        for (String value : stringList(teamContext.get("verifierResults"))) {
            out.add("verifier: " + value);
        }
        for (String value : stringList(teamContext.get("revisionRequests"))) {
            out.add("revision: " + value);
        }
        String whiteboard = string(teamContext.get("whiteboardSummary")).replace("\n", " ").trim();
        if (!whiteboard.isBlank()) {
            out.add("whiteboard: " + abbreviate(whiteboard, 220));
        }
        return new ArrayList<>(dedupe(out));
    }

    private List<String> renderSubAgentFindings(List<SubAgentResult> results) {
        List<String> out = new ArrayList<>();
        for (SubAgentResult result : results != null ? results : List.<SubAgentResult>of()) {
            out.add(SubAgentOrchestrator.renderCompact(result));
        }
        return out;
    }

    private List<String> inferChangedFiles(List<Map<String, Object>> toolTrace) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            String tool = string(trace.get("tool_name"));
            if (!tool.equals("write_file") && !tool.equals("edit_file") && !tool.equals("notebook_edit") && !tool.equals("note")) {
                continue;
            }
            addPathLike(out, string(trace.get("arguments_summary")));
            addPathLike(out, string(trace.get("result_summary")));
        }
        return out;
    }

    private List<String> inferTestCommands(List<Map<String, Object>> toolTrace) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            String tool = string(trace.get("tool_name"));
            String combined = combined(trace);
            if (tool.equals("exec") && (combined.contains("mvn") || combined.contains("test") || combined.contains("pytest")
                    || combined.contains("gradle") || combined.contains("npm test"))) {
                out.add(combined.trim());
            }
        }
        return out;
    }

    private List<String> inferBlockers(List<Map<String, Object>> toolTrace) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            String status = string(trace.get("status"));
            if ("error".equalsIgnoreCase(status)) {
                String detail = string(trace.get("detail"));
                String result = string(trace.get("result_summary"));
                out.add(!detail.isBlank() ? detail : result);
            }
        }
        return out;
    }

    private List<String> inferApprovalRecords(List<Map<String, Object>> toolTrace) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            String combined = combined(trace);
            if (!combined.contains("requestId:") && !combined.contains("approval_") && !combined.contains("审批")) {
                continue;
            }
            String tool = string(trace.get("tool_name"));
            String requestId = extractLineValue(combined, "requestId:");
            String riskLevel = extractLineValue(combined, "riskLevel:");
            StringBuilder sb = new StringBuilder();
            if (!tool.isBlank()) {
                sb.append(tool).append(": ");
            }
            if (!requestId.isBlank()) {
                sb.append("requestId=").append(requestId);
            } else {
                sb.append(abbreviate(combined, 120));
            }
            if (!riskLevel.isBlank()) {
                sb.append(", riskLevel=").append(riskLevel);
            }
            out.add(sb.toString());
        }
        return new ArrayList<>(dedupe(out));
    }

    private List<String> inferDiffReviews(List<Map<String, Object>> toolTrace) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            String combined = combined(trace);
            if (!combined.contains("DiffReview")) {
                continue;
            }
            String changedFiles = extractLineValue(combined, "changedFiles:");
            String summary = extractLineValue(combined, "summary:");
            String riskLevel = extractLineValue(combined, "riskLevel:");
            StringBuilder sb = new StringBuilder();
            sb.append(!changedFiles.isBlank() ? changedFiles : "file change");
            if (!summary.isBlank()) {
                sb.append(" — ").append(summary);
            }
            if (!riskLevel.isBlank()) {
                sb.append(" [risk=").append(riskLevel).append("]");
            }
            out.add(sb.toString());
        }
        return new ArrayList<>(dedupe(out));
    }

    private List<String> inferDiffField(List<Map<String, Object>> toolTrace, String field) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            String value = extractLineValue(combined(trace), field);
            if (value.isBlank() || "none".equalsIgnoreCase(value)) {
                continue;
            }
            for (String item : value.split(",")) {
                if (!item.isBlank()) {
                    out.add(item.trim());
                }
            }
        }
        return new ArrayList<>(dedupe(out));
    }

    private String notice(TaskState taskState, List<String> changedFiles, List<Map<String, Object>> toolTrace) {
        boolean hasGoal = taskState != null && taskState.goal() != null && !taskState.goal().isBlank();
        boolean hasChanges = changedFiles != null && !changedFiles.isEmpty();
        boolean hasTrace = toolTrace != null && !toolTrace.isEmpty();
        if (!hasGoal && !hasChanges && !hasTrace) {
            return "当前没有可汇总的任务状态或文件变更。";
        }
        if (!hasChanges) {
            return "当前任务没有检测到文件变更。";
        }
        if (!hasGoal) {
            return "当前没有明确任务目标，以下为工具轨迹摘要。";
        }
        return "";
    }

    private void addPathLike(List<String> out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String cleaned = text.replace("{", " ").replace("}", " ").replace(",", " ");
        for (String token : cleaned.split("\\s+")) {
            String value = token.replace("\"", "").replace("'", "").trim();
            if (value.contains("/") || value.endsWith(".java") || value.endsWith(".md") || value.endsWith(".json")
                    || value.endsWith(".yml") || value.endsWith(".yaml") || value.endsWith(".txt")) {
                out.add(value);
            }
        }
    }

    private List<Map<String, Object>> readTrace(Session session, String key) {
        if (session == null || session.getMetadata() == null) {
            return List.of();
        }
        Object raw = session.getMetadata().get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        row.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                out.add(row);
            }
        }
        return out;
    }

    private String combined(Map<String, Object> trace) {
        if (trace == null) {
            return "";
        }
        return string(trace.get("arguments_summary")) + "\n"
                + string(trace.get("result_summary")) + "\n"
                + string(trace.get("detail"));
    }

    private String extractLineValue(String text, String prefix) {
        if (text == null || prefix == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            int index = trimmed.indexOf(prefix);
            if (index >= 0) {
                return trimmed.substring(index + prefix.length()).trim();
            }
        }
        return "";
    }

    private Set<String> dedupe(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values != null ? values : List.<String>of()) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return out;
    }

    private List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "...";
    }

    private String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    public record TaskSummary(
            String goal,
            List<String> changedFiles,
            List<String> keyDecisions,
            List<String> testCommands,
            List<String> blockers,
            List<String> nextActions,
            List<String> approvalRecords,
            List<String> diffReviews,
            List<String> suggestedTests,
            List<String> rollbackHints,
            List<String> teamFindings,
            List<String> subAgentFindings,
            String notice
    ) {
        public TaskSummary {
            goal = goal != null ? goal : "";
            changedFiles = changedFiles != null ? List.copyOf(changedFiles) : List.of();
            keyDecisions = keyDecisions != null ? List.copyOf(keyDecisions) : List.of();
            testCommands = testCommands != null ? List.copyOf(testCommands) : List.of();
            blockers = blockers != null ? List.copyOf(blockers) : List.of();
            nextActions = nextActions != null ? List.copyOf(nextActions) : List.of();
            approvalRecords = approvalRecords != null ? List.copyOf(approvalRecords) : List.of();
            diffReviews = diffReviews != null ? List.copyOf(diffReviews) : List.of();
            suggestedTests = suggestedTests != null ? List.copyOf(suggestedTests) : List.of();
            rollbackHints = rollbackHints != null ? List.copyOf(rollbackHints) : List.of();
            teamFindings = teamFindings != null ? List.copyOf(teamFindings) : List.of();
            subAgentFindings = subAgentFindings != null ? List.copyOf(subAgentFindings) : List.of();
            notice = notice != null ? notice : "";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("goal", goal);
            out.put("changed_files", changedFiles);
            out.put("key_decisions", keyDecisions);
            out.put("test_commands", testCommands);
            out.put("blockers", blockers);
            out.put("next_actions", nextActions);
            out.put("approval_records", approvalRecords);
            out.put("diff_reviews", diffReviews);
            out.put("suggested_tests", suggestedTests);
            out.put("rollback_hints", rollbackHints);
            out.put("team_findings", teamFindings);
            out.put("subagent_findings", subAgentFindings);
            out.put("notice", notice);
            return out;
        }
    }
}
