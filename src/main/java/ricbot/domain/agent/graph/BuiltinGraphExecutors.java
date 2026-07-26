package ricbot.domain.agent.graph;

import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.task.LocalTaskScheduler;
import ricbot.domain.task.PatchApplyResult;
import ricbot.domain.task.PatchLedgerService;
import ricbot.domain.task.TaskDelivery;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.TaskWorkspaceLease;
import ricbot.domain.task.TaskWorktreeManager;
import ricbot.domain.task.TeamPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in executor ids and adapters used by agent and team graphs. */
public final class BuiltinGraphExecutors {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    public static final String MODEL = "builtin:model";
    public static final String TOOL = "builtin:tool";
    public static final String APPROVAL = "builtin:approval";
    public static final String WORKER = "builtin:worker";
    public static final String JOIN = "builtin:join";
    public static final String APPLY_CHANGE_SET = "builtin:apply-change-set";
    public static final String VERIFIER = "builtin:verifier";

    private BuiltinGraphExecutors() {}

    public static GraphNodeRegistry registerModel(GraphNodeRegistry registry, GraphNodeExecutor executor) {
        return registry.register(MODEL, executor);
    }
    public static GraphNodeRegistry registerTool(GraphNodeRegistry registry, GraphNodeExecutor executor) {
        return registry.register(TOOL, executor);
    }

    public static GraphNodeRegistry registerApproval(GraphNodeRegistry registry, ApprovalService approvals) {
        return registry.register(APPROVAL, (state, input) -> {
            String requestId = text(input.getOrDefault("requestId", state.channels().get("approvalRequestId")));
            ApprovalRequest request = approvals.find(requestId);
            if (request == null) throw new IllegalArgumentException("approval request not found: " + requestId);
            return switch (request.status()) {
                case APPROVED, CLAIMED, CONSUMED -> GraphNodeResult.next("approved", Map.of("approvalDecision", "APPROVED"));
                case REJECTED -> GraphNodeResult.next("rejected", Map.of("approvalDecision", "REJECTED"));
                case PENDING -> GraphNodeResult.waitFor("approval required",
                        GraphWait.external(request.requestId(), state.nodeId() + ":approval", "approval",
                                "approval required", Map.of("requestId", request.requestId(), "expiresAt", request.expiresAt())),
                        Map.of("approvalRequestId", request.requestId()));
            };
        });
    }

    public static GraphNodeRegistry registerWorker(GraphNodeRegistry registry, LocalTaskScheduler scheduler) {
        return registry.register(WORKER, (state, input) -> {
            TeamPlan plan = value(input, state.channels(), "teamPlan", TeamPlan.class);
            List<TaskRecord> tasks = scheduler.tasks(plan.parentRunId());
            java.util.Set<String> existingIds = tasks.stream().map(task -> task.spec().taskId())
                    .collect(java.util.stream.Collectors.toSet());
            if (plan.tasks().stream().anyMatch(task -> !existingIds.contains(task.taskId()))) {
                scheduler.submit(plan);
                tasks = scheduler.tasks(plan.parentRunId());
            }
            if (tasks.stream().allMatch(task -> task.status().terminal())) {
                boolean criticalFailure = tasks.stream().anyMatch(task -> task.status() == TaskStatus.FAILED
                        && task.spec().failurePolicy() == ricbot.domain.task.TaskFailurePolicy.FAIL_FAST);
                return GraphNodeResult.next(criticalFailure ? "failed" : "complete",
                        Map.of("taskIds", tasks.stream().map(task -> task.spec().taskId()).toList()));
            }
            return GraphNodeResult.waitFor("waiting for worker tasks",
                    GraphWait.external("tasks:" + plan.planId(), state.nodeId() + ":workers", "tasks",
                            "waiting for worker tasks", Map.of("parentRunId", plan.parentRunId())), Map.of());
        });
    }

    public static GraphNodeRegistry registerJoin(GraphNodeRegistry registry, int maxContextChars) {
        int limit = maxContextChars > 0 ? maxContextChars : 12_000;
        return registry.register(JOIN, (state, input) -> {
            List<TaskResult> results = taskResults(state.channels().get("workerResults"));
            results = results.stream().sorted(Comparator.comparingInt(TaskResult::planOrder)
                    .thenComparing(TaskResult::taskId)).toList();
            List<Map<String, Object>> joined = new ArrayList<>();
            int used = 0;
            for (TaskResult result : results) {
                String summary = bounded(result.summary(), Math.max(0, limit - used));
                used += summary.length();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", result.taskId());
                item.put("status", result.status().name());
                item.put("summary", summary);
                item.put("artifactRefs", result.artifacts());
                item.put("changedFiles", result.changedFiles());
                joined.add(Map.copyOf(item));
                if (used >= limit) break;
            }
            return GraphNodeResult.next("joined", Map.of("joinedWorkerContext", List.copyOf(joined)));
        });
    }

    public static GraphNodeRegistry registerApplyChangeSets(GraphNodeRegistry registry,
                                                            TaskWorktreeManager worktrees,
                                                            PatchLedgerService ledger) {
        return registry.register(APPLY_CHANGE_SET, (state, input) -> {
            List<TaskResult> results = taskResults(state.channels().get("workerResults")).stream()
                    .filter(result -> !result.patch().isBlank()).toList();
            TaskWorkspaceLease integration = worktrees.integration(state.runId());
            List<PatchApplyResult> outcomes = ledger.applyOrdered(state.runId(), integration.path(), results);
            boolean conflict = outcomes.stream().anyMatch(result -> result.status() == PatchApplyResult.Status.CONFLICT);
            if (conflict) {
                PatchApplyResult evidence = outcomes.stream().filter(result -> result.status() == PatchApplyResult.Status.CONFLICT)
                        .findFirst().orElseThrow();
                return GraphNodeResult.waitFor("patch conflict requires human resolution",
                        GraphWait.external("patch-conflict:" + evidence.digest(), state.nodeId() + ":patch", "conflict",
                                evidence.evidence(), Map.of("taskId", evidence.taskId(), "workspace", integration.path().toString())),
                        Map.of("patchOutcomes", outcomes));
            }
            return GraphNodeResult.next("applied", Map.of("patchOutcomes", outcomes,
                    "integrationWorkspace", integration.path().toString()));
        });
    }

    public static GraphNodeRegistry registerVerifier(GraphNodeRegistry registry, Verifier verifier) {
        return registry.register(VERIFIER, (state, input) -> {
            VerificationDecision decision = verifier.verify(state, input);
            return GraphNodeResult.next(decision.outcome(), Map.of("verification", decision.details()));
        });
    }

    public static GraphStateSchema standardSchema() {
        return GraphStateSchema.builder()
                .channel("goal", StateReducers.replaceOnce())
                .channel("messages", StateReducers.orderedAppend())
                .channel("modelResponse", StateReducers.replace())
                .channel("toolResults", StateReducers.orderedAppend())
                .channel("approvalRequestId", StateReducers.replaceOnce())
                .channel("approvalDecision", StateReducers.replace())
                .channel("teamPlan", StateReducers.replace())
                .channel("taskIds", StateReducers.setUnion())
                .channel("workerResults", StateReducers.taskResultsByAttempt())
                .channel("joinedWorkerContext", StateReducers.replace())
                .channel("patchOutcomes", StateReducers.replace())
                .channel("integrationWorkspace", StateReducers.replaceOnce())
                .channel("verification", StateReducers.replace())
                .channel("verificationProfileDigest", StateReducers.replaceOnce())
                .channel("revision", StateReducers.replace())
                .build();
    }

    private static List<TaskResult> taskResults(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(item -> item instanceof TaskDelivery delivery ? delivery.result() : item)
                .map(item -> {
                    if (item instanceof TaskResult result) return result;
                    if (item instanceof Map<?, ?>) return MAPPER.convertValue(item, TaskResult.class);
                    return null;
                }).filter(java.util.Objects::nonNull).toList();
    }
    private static String bounded(String value, int limit) {
        String clean = value != null ? value : "";
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
    private static String text(Object value) { return value != null ? String.valueOf(value).trim() : ""; }
    private static <T> T value(Map<String, Object> input, Map<String, Object> channels, String key, Class<T> type) {
        Object value = input.containsKey(key) ? input.get(key) : channels.get(key);
        if (value instanceof Map<?, ?> && !type.isInstance(value)) value = MAPPER.convertValue(value, type);
        if (!type.isInstance(value)) throw new IllegalArgumentException(key + " is required and must be " + type.getSimpleName());
        return type.cast(value);
    }

    @FunctionalInterface
    public interface Verifier { VerificationDecision verify(GraphExecutionState state, Map<String, Object> input) throws Exception; }
    public record VerificationDecision(String outcome, Map<String, Object> details) {
        public VerificationDecision {
            outcome = outcome != null && !outcome.isBlank() ? outcome : "needs_human";
            details = Map.copyOf(details != null ? details : Map.of());
        }
    }
}
