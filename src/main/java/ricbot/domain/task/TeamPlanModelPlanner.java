package ricbot.domain.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.domain.agent.structured.StructuredOutputService;
import ricbot.domain.agent.structured.StructuredRequest;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.config.ModelCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ricbot.domain.runtime.dto.RuntimeDigest;

/** Converts a leader model's schema-constrained tool call into an untrusted TeamPlan. */
public final class TeamPlanModelPlanner {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final LLMProvider provider;
    private final String model;
    private final ModelCard.Pricing pricing;
    private UsageLedger lastUsage = UsageLedger.empty();

    public TeamPlanModelPlanner(LLMProvider provider, String model) {
        this(provider, model, null);
    }

    public TeamPlanModelPlanner(LLMProvider provider, String model, ModelCard.Pricing pricing) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        this.model = model != null && !model.isBlank() ? model : provider.getDefaultModel();
        this.pricing = pricing;
    }

    public TeamPlan plan(GraphExecutionState state) throws Exception {
        return plan(state, number(state.channels().get("revision")));
    }

    public TeamPlan plan(GraphExecutionState state, int revision) throws Exception {
        String goal = String.valueOf(state.channels().getOrDefault("goal", ""));
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "Create a local multi-agent task DAG. Use only the declared roles and tools. "
                        + "Explorer and Reviewer are read-only; Developer uses an isolated worktree. Return submit_team_plan."),
                Map.of("role", "user", "content", goal + (revision > 0 ? "\nRevision round: " + revision
                        + "\nPrior results: " + state.channels().getOrDefault("joinedWorkerContext", List.of())
                        + "\nStructured verification evidence: " + state.channels().getOrDefault("verification", Map.of()) : ""))
        );
        @SuppressWarnings("unchecked") Class<Map<String, Object>> mapType = (Class<Map<String, Object>>) (Class<?>) Map.class;
        Map<String, Object> parameters = castMap(castMap(schema().get("function")).get("parameters"));
        var structured = new StructuredOutputService(provider, pricing).execute(messages, model,
                new StructuredRequest<>("submit_team_plan", "Submit a validated local task DAG", parameters,
                        mapType, value -> value != null && value.get("tasks") instanceof List<?>, 1), true, true);
        if (!structured.valid()) throw new IllegalArgumentException("invalid leader plan: " + structured.error());
        lastUsage = structured.usage();
        Map<String, Object> payload = structured.value();
        return convert(state, payload, revision);
    }

    public UsageLedger lastUsage() { return lastUsage; }

    private TeamPlan convert(GraphExecutionState state, Map<String, Object> payload, int revision) {
        Object rawTasks = payload.get("tasks");
        if (!(rawTasks instanceof List<?> list)) throw new IllegalArgumentException("leader plan has no tasks array");
        String planId = "plan-" + RuntimeDigest.sha256(state.runId() + ":revision:" + revision).substring(0, 20);
        List<TaskSpec> tasks = new ArrayList<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        Map<String, String> globalIds = new LinkedHashMap<>();
        int order = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("leader task must be an object");
            Map<String, Object> task = new LinkedHashMap<>();
            raw.forEach((key, value) -> task.put(String.valueOf(key), value));
            String localId = text(task.get("id"));
            if (localId.isBlank()) localId = "task-" + order;
            String globalId = deterministicTaskId(state.runId(), revision, localId);
            if (globalIds.putIfAbsent(localId, globalId) != null) {
                throw new IllegalArgumentException("duplicate leader local task id: " + localId);
            }
            task.put("localId", localId);
            normalized.add(task);
            order++;
        }
        order = 0;
        for (Map<String, Object> task : normalized) {
            String localId = text(task.get("localId"));
            String id = globalIds.get(localId);
            TaskRole role = TaskRole.valueOf(text(task.get("role")).toUpperCase(java.util.Locale.ROOT));
            TaskWorkspaceMode workspaceMode = task.get("workspaceMode") != null
                    ? TaskWorkspaceMode.valueOf(text(task.get("workspaceMode")).toUpperCase(java.util.Locale.ROOT))
                    : writing(role) ? TaskWorkspaceMode.ISOLATED_WORKTREE : TaskWorkspaceMode.SHARED_READ;
            TaskFailurePolicy policy = task.get("failurePolicy") != null
                    ? TaskFailurePolicy.valueOf(text(task.get("failurePolicy")).toUpperCase(java.util.Locale.ROOT))
                    : role == TaskRole.EXPLORER || role == TaskRole.REVIEWER
                        ? TaskFailurePolicy.TOLERATE : TaskFailurePolicy.FAIL_FAST;
            List<String> dependencies = strings(task.get("dependsOn")).stream().map(dependency -> {
                String mapped = globalIds.get(dependency);
                if (mapped == null) throw new IllegalArgumentException("unknown local dependency " + dependency);
                return mapped;
            }).toList();
            tasks.add(new TaskSpec(id, state.runId(), planId, revision, localId,
                    state.nodeId() + ":" + state.superstep(), order++, 0,
                    role, text(task.get("goal")), dependencies, strings(task.get("allowedTools")),
                    workspaceMode, policy, Boolean.TRUE.equals(task.get("allowFailedDependencies")),
                    strings(task.get("requiredCheckIds")), strings(task.get("acceptanceCriteria"))));
        }
        return new TeamPlan(planId, state.runId(), revision, tasks);
    }

    public static String deterministicTaskId(String parentRunId, int revision, String localId) {
        String digest = RuntimeDigest.sha256(parentRunId + "\u0000" + revision + "\u0000" + localId);
        return "task-" + digest.substring(0, 24);
    }

    public static Map<String, Object> schema() {
        Map<String, Object> task = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("id", "role", "goal", "dependsOn", "allowedTools", "workspaceMode", "failurePolicy",
                        "requiredCheckIds", "acceptanceCriteria"),
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "role", Map.of("type", "string", "enum", java.util.Arrays.stream(TaskRole.values()).map(Enum::name).toList()),
                        "goal", Map.of("type", "string"),
                        "dependsOn", Map.of("type", "array", "items", Map.of("type", "string")),
                        "allowedTools", Map.of("type", "array", "items", Map.of("type", "string")),
                        "workspaceMode", Map.of("type", "string", "enum", java.util.Arrays.stream(TaskWorkspaceMode.values()).map(Enum::name).toList()),
                        "failurePolicy", Map.of("type", "string", "enum", java.util.Arrays.stream(TaskFailurePolicy.values()).map(Enum::name).toList()),
                        "allowFailedDependencies", Map.of("type", "boolean"),
                        "requiredCheckIds", Map.of("type", "array", "items", Map.of("type", "string")),
                        "acceptanceCriteria", Map.of("type", "array", "items", Map.of("type", "string"))
                ));
        return Map.of("type", "function", "function", Map.of(
                "name", "submit_team_plan", "description", "Submit a validated local task DAG", "strict", true,
                "parameters", Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("tasks"), "properties", Map.of("tasks",
                                Map.of("type", "array", "minItems", 1, "maxItems", 8, "items", task)))));
    }

    private static boolean writing(TaskRole role) { return role == TaskRole.DEVELOPER || role == TaskRole.TESTER; }
    private static int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static String text(Object value) { return value != null ? String.valueOf(value).trim() : ""; }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(TeamPlanModelPlanner::text).filter(item -> !item.isBlank()).toList();
    }
    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }
}
