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
    private static final int MAX_TRANSITIONS = 20;

    private String goal = "";
    private List<String> plan = new ArrayList<>();
    private List<TaskStep> steps = new ArrayList<>();
    private String currentStep = "";
    private String status = STATUS_ACTIVE;
    private String blockedReason = "";
    private String nextAction = "";
    private String lastToolName = "";
    private String lastToolOutcome = "";
    private String updatedAt = Instant.now().toString();
    private List<TaskTransition> transitions = new ArrayList<>();
    private List<ParallelTask> parallelTasks = new ArrayList<>();

    static TaskState fromSession(Session session) {
        if (session == null) {
            return new TaskState();
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.TASK_STATE_KEY);
        if (raw instanceof Map<?, ?> map) {
            return fromMap(copyObjectMap(map));
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
        state.steps = toSteps(raw.get("steps"), state.plan);
        state.currentStep = stringValue(raw.get("current_step"));
        state.status = normalizeStatus(stringValue(raw.get("status")));
        state.blockedReason = stringValue(raw.get("blocked_reason"));
        state.nextAction = stringValue(raw.get("next_action"));
        state.lastToolName = stringValue(raw.get("last_tool_name"));
        state.lastToolOutcome = stringValue(raw.get("last_tool_outcome"));
        state.transitions = toTransitions(raw.get("transitions"));
        state.parallelTasks = toParallelTasks(raw.get("parallel_tasks"));
        String updated = stringValue(raw.get("updated_at"));
        state.updatedAt = updated.isBlank() ? Instant.now().toString() : updated;
        return state;
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goal", goal);
        out.put("plan", plan != null ? new ArrayList<>(plan) : List.of());
        out.put("steps", renderSteps());
        out.put("current_step", currentStep);
        out.put("status", status);
        out.put("blocked_reason", blockedReason);
        out.put("next_action", nextAction);
        out.put("last_tool_name", lastToolName);
        out.put("last_tool_outcome", lastToolOutcome);
        out.put("transitions", renderTransitions());
        out.put("parallel_tasks", renderParallelTasks());
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
                steps = defaultSteps(plan);
                recordTransition("begin", "", STATUS_ACTIVE, "user", normalized);
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
        if (!normalized.isBlank()) {
            recordTransition("turn", status, STATUS_ACTIVE, "user", normalized);
        }
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
        if ("spawn".equals(lastToolName)) {
            markParallelTaskStarted(arguments);
        }
        markStepInProgress(currentStep, argsSummary);
        recordTransition("tool_start", status, STATUS_ACTIVE, lastToolName, argsSummary);
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
            markCurrentStep(STATUS_BLOCKED, lastToolOutcome);
            recordTransition("tool_finish", STATUS_ACTIVE, STATUS_BLOCKED, name, lastToolOutcome);
        } else {
            status = STATUS_ACTIVE;
            blockedReason = "";
            currentStep = "处理工具结果";
            nextAction = "继续推进任务";
            markStepInProgress(currentStep, lastToolOutcome);
            recordTransition("tool_finish", STATUS_ACTIVE, STATUS_ACTIVE, name, lastToolOutcome);
        }
        touch();
    }

    void markCompleted(String finalContent) {
        status = STATUS_COMPLETED;
        blockedReason = "";
        currentStep = "任务已完成";
        nextAction = "";
        completeOpenSteps(finalContent);
        if (finalContent != null && !finalContent.isBlank()) {
            lastToolOutcome = abbreviate(finalContent, 180);
        }
        recordTransition("complete", STATUS_ACTIVE, STATUS_COMPLETED, "assistant", finalContent);
        touch();
    }

    void markBlocked(String reason) {
        String previous = status;
        status = STATUS_BLOCKED;
        blockedReason = reason != null ? reason : "";
        currentStep = "任务受阻";
        nextAction = "处理阻塞原因后继续";
        markCurrentStep(STATUS_BLOCKED, blockedReason);
        recordTransition("blocked", previous, STATUS_BLOCKED, "system", blockedReason);
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
        if (steps != null && !steps.isEmpty()) {
            sb.append("steps: ");
            for (int i = 0; i < steps.size(); i++) {
                TaskStep step = steps.get(i);
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(step.id()).append(". ").append(step.title()).append(" [").append(step.status()).append("]");
            }
            sb.append("\n");
        }
        if (parallelTasks != null && !parallelTasks.isEmpty()) {
            sb.append("parallel tasks: ");
            for (int i = 0; i < parallelTasks.size(); i++) {
                ParallelTask task = parallelTasks.get(i);
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(task.id()).append(". ").append(task.label()).append(" [").append(task.status()).append("]");
            }
            sb.append("\n");
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

    private static List<TaskStep> defaultSteps(List<String> plan) {
        List<TaskStep> out = new ArrayList<>();
        List<String> source = plan != null ? plan : List.of();
        String now = Instant.now().toString();
        for (int i = 0; i < source.size(); i++) {
            out.add(new TaskStep(String.valueOf(i + 1), source.get(i), i == 0 ? STATUS_ACTIVE : "pending", "", now));
        }
        return out;
    }

    private void markStepInProgress(String title, String evidence) {
        if (title == null || title.isBlank()) {
            return;
        }
        ensureSteps();
        String now = Instant.now().toString();
        for (int i = 0; i < steps.size(); i++) {
            TaskStep step = steps.get(i);
            if (STATUS_ACTIVE.equals(step.status())) {
                steps.set(i, step.withStatus(STATUS_COMPLETED, evidence, now));
                break;
            }
        }
        steps.add(new TaskStep(String.valueOf(steps.size() + 1), title, STATUS_ACTIVE, abbreviate(evidence, 180), now));
        syncPlanFromSteps();
    }

    private void markCurrentStep(String statusValue, String evidence) {
        ensureSteps();
        String now = Instant.now().toString();
        if (steps.isEmpty()) {
            steps.add(new TaskStep("1", currentStep.isBlank() ? statusValue : currentStep, statusValue, abbreviate(evidence, 180), now));
        } else {
            int index = steps.size() - 1;
            steps.set(index, steps.get(index).withStatus(statusValue, evidence, now));
        }
        syncPlanFromSteps();
    }

    private void completeOpenSteps(String evidence) {
        ensureSteps();
        String now = Instant.now().toString();
        for (int i = 0; i < steps.size(); i++) {
            TaskStep step = steps.get(i);
            if (!STATUS_COMPLETED.equals(step.status())) {
                steps.set(i, step.withStatus(STATUS_COMPLETED, evidence, now));
            }
        }
        syncPlanFromSteps();
    }

    private void ensureSteps() {
        if (steps == null) {
            steps = new ArrayList<>();
        }
        if (steps.isEmpty() && plan != null && !plan.isEmpty()) {
            steps = defaultSteps(plan);
        }
    }

    private void syncPlanFromSteps() {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        List<String> out = new ArrayList<>();
        for (TaskStep step : steps) {
            out.add(step.title());
        }
        plan = out;
    }

    private void recordTransition(String event, String fromStatus, String toStatus, String actor, String evidence) {
        if (transitions == null) {
            transitions = new ArrayList<>();
        }
        transitions.add(new TaskTransition(
                Instant.now().toString(),
                event != null ? event : "",
                fromStatus != null ? fromStatus : "",
                toStatus != null ? toStatus : "",
                actor != null ? actor : "",
                abbreviate(evidence, 220)
        ));
        while (transitions.size() > MAX_TRANSITIONS) {
            transitions.remove(0);
        }
    }

    private void markParallelTaskStarted(Map<String, Object> arguments) {
        if (parallelTasks == null) {
            parallelTasks = new ArrayList<>();
        }
        String task = arguments != null ? stringValue(arguments.get("task")) : "";
        String label = arguments != null ? stringValue(arguments.get("label")) : "";
        if (label.isBlank()) {
            label = abbreviate(task, 40);
        }
        String id = "p" + (parallelTasks.size() + 1);
        parallelTasks.add(new ParallelTask(id, label, task, STATUS_ACTIVE, "spawn started", Instant.now().toString()));
        recordTransition("parallel_start", "", STATUS_ACTIVE, "spawn", label);
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

    private static List<TaskStep> toSteps(Object raw, List<String> fallbackPlan) {
        List<TaskStep> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            int next = 1;
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String id = stringValue(map.get("id"));
                    String title = stringValue(map.get("title"));
                    String status = normalizeStepStatus(stringValue(map.get("status")));
                    String evidence = stringValue(map.get("evidence"));
                    String updatedAt = stringValue(map.get("updated_at"));
                    if (!title.isBlank()) {
                        out.add(new TaskStep(id.isBlank() ? String.valueOf(next) : id, title, status, evidence, updatedAt.isBlank() ? Instant.now().toString() : updatedAt));
                        next++;
                    }
                } else if (item != null) {
                    String title = String.valueOf(item).trim();
                    if (!title.isBlank()) {
                        out.add(new TaskStep(String.valueOf(next++), title, "pending", "", Instant.now().toString()));
                    }
                }
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        return defaultSteps(fallbackPlan);
    }

    private static String normalizeStatus(String status) {
        if (STATUS_ACTIVE.equals(status) || STATUS_BLOCKED.equals(status)
                || STATUS_COMPLETED.equals(status) || STATUS_ABANDONED.equals(status)) {
            return status;
        }
        return STATUS_ACTIVE;
    }

    private static String normalizeStepStatus(String status) {
        if ("pending".equals(status) || STATUS_ACTIVE.equals(status) || STATUS_BLOCKED.equals(status)
                || STATUS_COMPLETED.equals(status) || STATUS_ABANDONED.equals(status)) {
            return status;
        }
        return "pending";
    }

    private static List<TaskTransition> toTransitions(Object raw) {
        List<TaskTransition> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                out.add(new TaskTransition(
                        stringValue(map.get("at")),
                        stringValue(map.get("event")),
                        stringValue(map.get("from")),
                        stringValue(map.get("to")),
                        stringValue(map.get("actor")),
                        stringValue(map.get("evidence"))
                ));
            }
        }
        if (out.size() > MAX_TRANSITIONS) {
            return new ArrayList<>(out.subList(out.size() - MAX_TRANSITIONS, out.size()));
        }
        return out;
    }

    private static List<ParallelTask> toParallelTasks(Object raw) {
        List<ParallelTask> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String id = stringValue(map.get("id"));
                String label = stringValue(map.get("label"));
                String goal = stringValue(map.get("goal"));
                String status = normalizeStatus(stringValue(map.get("status")));
                String evidence = stringValue(map.get("evidence"));
                String updatedAt = stringValue(map.get("updated_at"));
                if (!label.isBlank() || !goal.isBlank()) {
                    out.add(new ParallelTask(
                            id.isBlank() ? "p" + (out.size() + 1) : id,
                            label.isBlank() ? abbreviate(goal, 40) : label,
                            goal,
                            status,
                            evidence,
                            updatedAt.isBlank() ? Instant.now().toString() : updatedAt
                    ));
                }
            }
        }
        return out;
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

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
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

    List<TaskStep> steps() {
        return steps != null ? steps : List.of();
    }

    List<TaskTransition> transitions() {
        return transitions != null ? transitions : List.of();
    }

    List<ParallelTask> parallelTasks() {
        return parallelTasks != null ? parallelTasks : List.of();
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

    private List<Map<String, Object>> renderSteps() {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskStep step : steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", step.id());
            row.put("title", step.title());
            row.put("status", step.status());
            row.put("evidence", step.evidence());
            row.put("updated_at", step.updatedAt());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> renderTransitions() {
        if (transitions == null || transitions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskTransition transition : transitions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", transition.at());
            row.put("event", transition.event());
            row.put("from", transition.fromStatus());
            row.put("to", transition.toStatus());
            row.put("actor", transition.actor());
            row.put("evidence", transition.evidence());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> renderParallelTasks() {
        if (parallelTasks == null || parallelTasks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParallelTask task : parallelTasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.id());
            row.put("label", task.label());
            row.put("goal", task.goal());
            row.put("status", task.status());
            row.put("evidence", task.evidence());
            row.put("updated_at", task.updatedAt());
            out.add(row);
        }
        return out;
    }

    record TaskStep(String id, String title, String status, String evidence, String updatedAt) {
        TaskStep withStatus(String status, String evidence, String updatedAt) {
            return new TaskStep(id, title, normalizeStepStatus(status), evidence != null && !evidence.isBlank() ? abbreviate(evidence, 180) : this.evidence, updatedAt);
        }
    }

    record TaskTransition(String at, String event, String fromStatus, String toStatus, String actor, String evidence) {
    }

    record ParallelTask(String id, String label, String goal, String status, String evidence, String updatedAt) {
    }
}
