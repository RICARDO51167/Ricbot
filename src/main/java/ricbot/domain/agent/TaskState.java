package ricbot.domain.agent;

import ricbot.domain.session.Session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TaskState {

    static final String STATUS_ACTIVE = "active";
    static final String STATUS_BLOCKED = "blocked";
    static final String STATUS_COMPLETED = "completed";
    static final String STATUS_ABANDONED = "abandoned";

    private String goal = "";
    private List<String> plan = new ArrayList<>();
    private String currentStep = "";
    private String status = STATUS_ACTIVE;
    private String blockedReason = "";
    private String nextAction = "";
    private String lastToolName = "";
    private String lastToolOutcome = "";
    private String updatedAt = Instant.now().toString();

    static TaskState fromSession(Session session) {
        if (session == null) {
            return new TaskState();
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.TASK_STATE_KEY);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> taskMap = (Map<String, Object>) map;
            return fromMap(taskMap);
        }
        return new TaskState();
    }

    static TaskState fromMap(Map<String, Object> raw) {
        TaskState state = new TaskState();
        if (raw == null) {
            return state;
        }
        state.goal = stringValue(raw.get("goal"));
        state.plan = toPlan(raw.get("plan"));
        state.currentStep = stringValue(raw.get("current_step"));
        state.status = normalizeStatus(stringValue(raw.get("status")));
        state.blockedReason = stringValue(raw.get("blocked_reason"));
        state.nextAction = stringValue(raw.get("next_action"));
        state.lastToolName = stringValue(raw.get("last_tool_name"));
        state.lastToolOutcome = stringValue(raw.get("last_tool_outcome"));
        String updated = stringValue(raw.get("updated_at"));
        state.updatedAt = updated.isBlank() ? Instant.now().toString() : updated;
        return state;
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goal", goal);
        out.put("plan", plan != null ? new ArrayList<>(plan) : List.of());
        out.put("current_step", currentStep);
        out.put("status", status);
        out.put("blocked_reason", blockedReason);
        out.put("next_action", nextAction);
        out.put("last_tool_name", lastToolName);
        out.put("last_tool_outcome", lastToolOutcome);
        out.put("updated_at", updatedAt);
        return out;
    }

    void persist(Session session) {
        if (session == null) {
            return;
        }
        touch();
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, toMap());
    }

    void beginTurn(String userMessage) {
        String normalized = userMessage != null ? userMessage.trim() : "";
        if (!normalized.isBlank()) {
            if (goal.isBlank() || STATUS_COMPLETED.equals(status) || STATUS_ABANDONED.equals(status)) {
                goal = normalized;
                plan = defaultPlan(normalized);
            }
            if (currentStep.isBlank()) {
                currentStep = "分析用户请求";
            }
            if (nextAction.isBlank()) {
                nextAction = "继续完成当前任务";
            }
        }
        status = STATUS_ACTIVE;
        blockedReason = "";
        touch();
    }

    void markToolStart(String toolName, Map<String, Object> arguments) {
        String argsSummary = arguments == null || arguments.isEmpty() ? "" : summarizeArgs(arguments);
        status = STATUS_ACTIVE;
        blockedReason = "";
        currentStep = "执行工具 " + toolName;
        nextAction = "等待 " + toolName + " 执行结果";
        lastToolName = toolName != null ? toolName : "";
        lastToolOutcome = argsSummary.isBlank() ? "started" : "started: " + argsSummary;
        touch();
    }

    void markToolFinish(Map<String, Object> event) {
        if (event == null) {
            return;
        }
        String name = stringValue(event.get("name"));
        String statusValue = stringValue(event.get("status"));
        String detail = stringValue(event.get("detail"));
        lastToolName = name;
        lastToolOutcome = detail.isBlank() ? statusValue : statusValue + ": " + detail;
        if ("error".equals(statusValue)) {
            status = STATUS_BLOCKED;
            blockedReason = detail.isBlank() ? ("工具 " + name + " 执行失败") : detail;
            currentStep = "工具执行失败";
            nextAction = "修复阻塞后继续任务";
        } else {
            status = STATUS_ACTIVE;
            blockedReason = "";
            currentStep = "处理工具结果";
            nextAction = "继续推进任务";
        }
        touch();
    }

    void markCompleted(String finalContent) {
        status = STATUS_COMPLETED;
        blockedReason = "";
        currentStep = "任务已完成";
        nextAction = "";
        if (finalContent != null && !finalContent.isBlank()) {
            lastToolOutcome = abbreviate(finalContent, 180);
        }
        touch();
    }

    void markBlocked(String reason) {
        status = STATUS_BLOCKED;
        blockedReason = reason != null ? reason : "";
        currentStep = "任务受阻";
        nextAction = "处理阻塞原因后继续";
        touch();
    }

    String renderStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("goal: ").append(goal.isBlank() ? "(empty)" : goal).append("\n");
        sb.append("status: ").append(status).append("\n");
        if (!currentStep.isBlank()) {
            sb.append("current step: ").append(currentStep).append("\n");
        }
        if (!blockedReason.isBlank()) {
            sb.append("blocked reason: ").append(blockedReason).append("\n");
        }
        if (!nextAction.isBlank()) {
            sb.append("next action: ").append(nextAction).append("\n");
        }
        if (!lastToolName.isBlank()) {
            sb.append("last tool: ").append(lastToolName).append("\n");
        }
        if (!lastToolOutcome.isBlank()) {
            sb.append("last tool outcome: ").append(lastToolOutcome).append("\n");
        }
        return sb.toString().trim();
    }

    private static List<String> defaultPlan(String userMessage) {
        List<String> out = new ArrayList<>();
        out.add("理解目标：" + abbreviate(userMessage, 80));
        out.add("执行必要的工具或推理步骤");
        out.add("给出可交付结果");
        return out;
    }

    private static String summarizeArgs(Map<String, Object> args) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (parts.size() >= 3) {
                break;
            }
            parts.add(entry.getKey() + "=" + abbreviate(String.valueOf(entry.getValue()), 40));
        }
        return String.join(", ", parts);
    }

    private static List<String> toPlan(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static String normalizeStatus(String status) {
        if (STATUS_ACTIVE.equals(status) || STATUS_BLOCKED.equals(status)
                || STATUS_COMPLETED.equals(status) || STATUS_ABANDONED.equals(status)) {
            return status;
        }
        return STATUS_ACTIVE;
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    private void touch() {
        updatedAt = Instant.now().toString();
    }

    String goal() {
        return goal;
    }

    List<String> plan() {
        return plan != null ? plan : List.of();
    }

    String currentStep() {
        return currentStep;
    }

    String status() {
        return status;
    }

    String blockedReason() {
        return blockedReason;
    }

    String nextAction() {
        return nextAction;
    }

    String lastToolName() {
        return lastToolName;
    }

    String lastToolOutcome() {
        return lastToolOutcome;
    }
}
