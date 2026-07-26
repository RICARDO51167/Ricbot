package ricbot.domain.task;

import ricbot.domain.task.TaskRole;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Security and DAG validation applied to untrusted leader model output. */
public final class TeamPlanValidator {
    private final int maxTasks;
    private final int maxDelegationDepth;
    private final Set<TaskRole> allowedRoles;
    private final Set<String> allowedTools;

    public TeamPlanValidator(int maxTasks, int maxDelegationDepth, Set<TaskRole> allowedRoles,
                             Set<String> allowedTools) {
        if (maxTasks < 1 || maxDelegationDepth < 0) throw new IllegalArgumentException("invalid plan limits");
        this.maxTasks = maxTasks;
        this.maxDelegationDepth = maxDelegationDepth;
        this.allowedRoles = Set.copyOf(allowedRoles != null ? allowedRoles : Set.of(TaskRole.values()));
        this.allowedTools = Set.copyOf(allowedTools != null ? allowedTools : Set.of());
    }

    public TeamPlan validate(TeamPlan plan) {
        if (plan == null) throw new IllegalArgumentException("team plan is required");
        if (plan.tasks().isEmpty()) throw new IllegalArgumentException("team plan requires at least one task");
        if (plan.tasks().size() > maxTasks) throw new IllegalArgumentException("team plan exceeds task limit " + maxTasks);
        Map<String, TaskSpec> byId = new LinkedHashMap<>();
        Set<Integer> orders = new LinkedHashSet<>();
        for (TaskSpec task : plan.tasks()) {
            if (!plan.parentRunId().equals(task.parentRunId())) throw new IllegalArgumentException("task parentRunId mismatch");
            if (!task.planId().isBlank() && !plan.planId().equals(task.planId())) {
                throw new IllegalArgumentException("task planId mismatch");
            }
            if (!task.planId().isBlank() && plan.revision() != task.planRevision()) {
                throw new IllegalArgumentException("task planRevision mismatch");
            }
            if (byId.putIfAbsent(task.taskId(), task) != null) throw new IllegalArgumentException("duplicate task: " + task.taskId());
            if (!orders.add(task.planOrder())) throw new IllegalArgumentException("duplicate task planOrder: " + task.planOrder());
            if (task.delegationDepth() > maxDelegationDepth) throw new IllegalArgumentException("delegation depth exceeded");
            if (!allowedRoles.contains(task.role())) throw new IllegalArgumentException("unknown or disabled role: " + task.role());
            if (!allowedTools.containsAll(task.allowedTools())) {
                Set<String> denied = new LinkedHashSet<>(task.allowedTools());
                denied.removeAll(allowedTools);
                throw new IllegalArgumentException("task requests unauthorized tools: " + denied);
            }
            if (task.workspaceMode() == TaskWorkspaceMode.SHARED_READ
                    && (task.role() == TaskRole.DEVELOPER || task.role() == TaskRole.TESTER)) {
                throw new IllegalArgumentException("writing role requires isolated or integration worktree: " + task.taskId());
            }
        }
        for (TaskSpec task : plan.tasks()) {
            for (String dependency : task.dependsOn()) {
                if (!byId.containsKey(dependency)) throw new IllegalArgumentException("unknown dependency " + dependency);
                if (dependency.equals(task.taskId())) throw new IllegalArgumentException("task cannot depend on itself");
            }
        }
        detectCycles(byId);
        return plan;
    }

    private static void detectCycles(Map<String, TaskSpec> tasks) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        tasks.keySet().forEach(id -> indegree.put(id, 0));
        tasks.values().forEach(task -> task.dependsOn().forEach(dependency -> {
            indegree.compute(task.taskId(), (ignored, value) -> value + 1);
            dependents.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(task.taskId());
        }));
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.addLast(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String dependent : dependents.getOrDefault(id, Set.of())) {
                int next = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (next == 0) ready.addLast(dependent);
            }
        }
        if (visited != tasks.size()) throw new IllegalArgumentException("team plan contains a dependency cycle");
    }
}
