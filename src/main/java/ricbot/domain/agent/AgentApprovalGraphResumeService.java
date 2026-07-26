package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.graph.*;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.ApprovalBinding;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.tool.api.ToolRiskDecision;
import ricbot.tool.api.ToolRegistry;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.application.runtime.RuntimeDriver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rebuilds the durable Agent graph for an externally approved tool action. */
public final class AgentApprovalGraphResumeService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "agent-approval-graph"); thread.setDaemon(true); return thread;
    });
    private final Path workspace;
    private final LLMProvider provider;
    private final String model;
    private final ToolRegistry tools;
    private final ApprovalService approvals;

    public AgentApprovalGraphResumeService(Path workspace, LLMProvider provider, String model,
                                           ToolRegistry tools, ApprovalService approvals) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.provider = provider;
        this.model = model;
        this.tools = tools;
        this.approvals = approvals;
    }

    public GraphExecutionState resume(String requestId) {
        ApprovalRequest request = approvals.find(requestId);
        if (request == null || request.binding() == null || !request.binding().bound())
            throw new IllegalArgumentException("approval is not bound to an Agent graph: " + requestId);
        GraphRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState checkpoint = store.loadCheckpoint(request.binding().runId()).orElseThrow(() ->
                new IllegalArgumentException("agent graph run not found: " + request.binding().runId()));
        if (!AgentGraphRuntimeFactory.AGENT_GRAPH_ID.equals(checkpoint.graphId()))
            throw new IllegalArgumentException("approval is not bound to an Agent graph");
        AgentGraphRuntime runtime = runtime(checkpoint, store);
        if (runtime.state().status() == GraphExecutionStatus.PAUSED
                || runtime.state().status() == GraphExecutionStatus.WAITING
                || runtime.state().status() == GraphExecutionStatus.RECOVERING) {
            runtime.resume(Map.of("approvalSignal", request.status().name()));
        }
        return new RuntimeDriver().drive(runtime);
    }

    private AgentGraphRuntime runtime(GraphExecutionState seed, GraphRuntimeStore store) {
        GraphNodeRegistry nodes = new GraphNodeRegistry()
                .register(AgentNodeType.INGEST.name(), (state, input) -> GraphNodeResult.next("next", Map.of()))
                .register(AgentNodeType.CONTEXT.name(), (state, input) -> GraphNodeResult.next("model", Map.of()))
                .register(AgentNodeType.COMPACT.name(), (state, input) -> GraphNodeResult.next("next", Map.of()))
                .register(AgentNodeType.APPROVAL.name(), (state, input) -> approvalNode(state))
                .register(AgentNodeType.TOOLS.name(), (state, input) -> approvedToolNode(state))
                .register(AgentNodeType.MODEL.name(), (state, input) -> modelNode(state))
                .register(AgentNodeType.STEERING.name(), (state, input) -> GraphNodeResult.next("next", Map.of()));
        return new AgentGraphRuntime(AgentGraphRuntimeFactory.definition(100), nodes,
                new GraphConditionRegistry(), AgentGraphRuntimeFactory.schema(), store, EXECUTOR, seed);
    }

    private GraphNodeResult approvalNode(GraphExecutionState state) {
        String requestId = firstString(state.channels().get("approvalRequestIds"));
        ApprovalRequest request = approvals.find(requestId);
        validate(state, request);
        return switch (request.status()) {
            case PENDING -> GraphNodeResult.waitFor("tool approval required",
                    GraphWait.external(requestId, "legacy-activation", "approval", "tool approval required",
                            Map.of("requestId", requestId)), Map.of());
            case APPROVED, CLAIMED, CONSUMED -> {
                if (!request.consumed()) approvals.claim(requestId);
                yield GraphNodeResult.next("approved", Map.of("approvalSignal", "CLAIMED"));
            }
            case REJECTED -> GraphNodeResult.next("rejected", Map.of(
                    "messages", append(messages(state), toolMessage(request.binding().activationId(), request.pendingToolCall(), false,
                            "tool call rejected by user")),
                    "pendingToolCalls", List.of(), "approvalRequestIds", List.of()));
        };
    }

    private GraphNodeResult approvedToolNode(GraphExecutionState state) {
        String requestId = firstString(state.channels().get("approvalRequestIds"));
        if (requestId.isBlank()) return regularToolNode(state);
        ApprovalRequest request = approvals.find(requestId);
        validate(state, request);
        PendingToolCall call = request.pendingToolCall();
        SideEffectCoordinator effects = new SideEffectCoordinator(new SqliteRuntimeStore(workspace).sideEffectStore());
        if (request.consumed()) {
            Object recovered = effects.load(request.binding().idempotencyKey()).map(SideEffectRecord::result)
                    .orElseThrow(() -> new IllegalStateException("consumed tool result is unavailable"));
            Map<String, Object> result = toolMessage(request.binding().activationId(), call, ok(recovered), recovered);
            List<String> remaining = remainingStrings(state.channels().get("approvalRequestIds"));
            return GraphNodeResult.next(remaining.isEmpty() ? "next" : "approval", Map.of(
                    "messages", append(messages(state), result),
                    "toolBatch", Map.of("results", List.of(result)), "pendingToolCalls",
                    remaining.isEmpty() ? List.of() : state.channels().get("pendingToolCalls"),
                    "approvalRequestIds", remaining));
        }
        effects.authorizeApproval(request.binding().idempotencyKey(), requestId);
        SideEffectOutcome outcome = effects.execute(tools, call.sessionId(), request.binding().idempotencyKey(),
                call.toolName(), call.arguments(), false, AgentApprovalGraphResumeService::ok);
        approvals.completeClaim(requestId);
        Map<String, Object> result = toolMessage(request.binding().activationId(), call, ok(outcome.result()), outcome.result());
        List<String> remaining = remainingStrings(state.channels().get("approvalRequestIds"));
        return GraphNodeResult.next(remaining.isEmpty() ? "next" : "approval", Map.of(
                "messages", append(messages(state), result),
                "toolBatch", Map.of("results", List.of(result)), "pendingToolCalls",
                remaining.isEmpty() ? List.of() : state.channels().get("pendingToolCalls"),
                "approvalRequestIds", remaining));
    }

    private GraphNodeResult regularToolNode(GraphExecutionState state) {
        List<StoredCall> calls = storedCalls(state.channels().get("pendingToolCalls"));
        List<Map<String, Object>> nextMessages = new ArrayList<>(messages(state));
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> approvalIds = new ArrayList<>();
        SideEffectCoordinator effects = new SideEffectCoordinator(new SqliteRuntimeStore(workspace).sideEffectStore());
        for (StoredCall call : calls) {
            if (tools == null || tools.get(call.name()) == null) {
                Map<String, Object> result = toolMessage(call.id(),
                        PendingToolCall.create(null, call.name(), call.arguments(), "run:" + state.runId(), null),
                        false, "tool not found");
                results.add(result); nextMessages.add(result); continue;
            }
            ToolRiskDecision decision = tools.get(call.name()).assessRisk(call.arguments());
            if (decision.decision() == ToolRiskDecision.Decision.DENY) {
                Map<String, Object> result = toolMessage(call.id(),
                        PendingToolCall.create(null, call.name(), call.arguments(), "run:" + state.runId(), decision.assessment()),
                        false, !decision.reason().isBlank() ? decision.reason() : "tool denied");
                results.add(result); nextMessages.add(result); continue;
            }
            String key = state.runId() + ":" + call.id();
            if (decision.decision() == ToolRiskDecision.Decision.REQUIRE_APPROVAL) {
                RiskAssessment risk = decision.assessment() != null ? decision.assessment()
                        : RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("tool approval required"), "",
                                call.name(), List.of());
                ApprovalBinding binding = new ApprovalBinding(state.runId(), call.id(), "TOOL", key, call.name(),
                        ToolInvocationRecord.argumentsDigest(call.arguments()));
                PendingToolCall pending = PendingToolCall.create(null, call.name(), call.arguments(),
                        "run:" + state.runId(), risk);
                ApprovalRequest approval = approvals.createRequest(risk, pending, binding);
                effects.reserveApproval(pending.sessionId(), key, call.name(), call.arguments(),
                        Map.of("requestId", approval.requestId()));
                approvalIds.add(approval.requestId());
                continue;
            }
            boolean readOnly = tools.policyFor(call.name()).readOnly();
            SideEffectOutcome outcome = effects.execute(tools, "run:" + state.runId(), key, call.name(),
                    call.arguments(), readOnly, AgentApprovalGraphResumeService::ok);
            Map<String, Object> result = toolMessage(call.id(),
                    PendingToolCall.create(null, call.name(), call.arguments(), "run:" + state.runId(), null),
                    ok(outcome.result()), outcome.result());
            results.add(result); nextMessages.add(result);
        }
        return GraphNodeResult.next(approvalIds.isEmpty() ? "next" : "approval", Map.of(
                "messages", List.copyOf(nextMessages), "toolBatch", Map.of("results", List.copyOf(results)),
                "approvalRequestIds", List.copyOf(approvalIds)));
    }

    private GraphNodeResult modelNode(GraphExecutionState state) throws Exception {
        if (provider == null) throw new IllegalStateException("LLM provider is unavailable for graph resume");
        LLMResponse response = provider.chat(messages(state), tools != null ? tools.getDefinitions() : List.of(),
                model, null, null, null, null);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", response.getContent() != null ? response.getContent() : "");
        if (response.hasToolCalls()) assistant.put("tool_calls", response.getToolCalls().stream()
                .map(ricbot.integration.llm.api.ToolCallRequest::toOpenAIToolCall).toList());
        Map<String, Object> modelResponse = new LinkedHashMap<>();
        modelResponse.put("content", response.getContent() != null ? response.getContent() : "");
        modelResponse.put("finishReason", response.getFinishReason() != null ? response.getFinishReason() : "");
        modelResponse.put("usage", response.getUsage() != null ? response.getUsage() : Map.of());
        modelResponse.put("toolCalls", response.getToolCalls().stream()
                .map(ricbot.integration.llm.api.ToolCallRequest::toOpenAIToolCall).toList());
        return GraphNodeResult.next(response.hasToolCalls() ? "tools" : "terminal", Map.of(
                "messages", append(messages(state), assistant),
                "modelResponse", modelResponse, "stopReason",
                response.getFinishReason() != null ? response.getFinishReason() : "stop",
                "pendingToolCalls", response.getToolCalls().stream()
                        .map(ricbot.integration.llm.api.ToolCallRequest::toOpenAIToolCall).toList()));
    }

    private void validate(GraphExecutionState state, ApprovalRequest request) {
        if (request == null || request.pendingToolCall() == null || request.binding() == null
                || !request.binding().bound() || !state.runId().equals(request.binding().runId())
                || !"TOOL".equals(request.binding().actionType())
                || !request.pendingToolCall().toolName().equals(request.binding().targetId())
                || !ToolInvocationRecord.argumentsDigest(request.pendingToolCall().arguments())
                    .equals(request.binding().actionDigest())
                || !containsBoundToolCall(state, request))
            throw new IllegalStateException("approval binding does not match Agent graph tool action");
        if (request.isExpired(java.time.Instant.now())) throw new IllegalStateException("approval request expired");
    }
    private static boolean containsBoundToolCall(GraphExecutionState state, ApprovalRequest request) {
        Object raw = state.channels().get("pendingToolCalls");
        if (!(raw instanceof List<?> list)) return false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> call)) continue;
            if (!request.binding().activationId().equals(String.valueOf(call.get("id")))) continue;
            Object function = call.get("function");
            if (function instanceof Map<?, ?> fn
                    && request.pendingToolCall().toolName().equals(String.valueOf(fn.get("name")))) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(GraphExecutionState state) {
        Object raw = state.channels().get("messages");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map)
            out.add(MAPPER.convertValue(map, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        return List.copyOf(out);
    }
    private static List<Map<String, Object>> append(List<Map<String, Object>> messages, Map<String, Object> value) {
        List<Map<String, Object>> out = new ArrayList<>(messages); out.add(Map.copyOf(value)); return List.copyOf(out);
    }
    private static Map<String, Object> toolMessage(String callId, PendingToolCall call, boolean ok, Object result) {
        Map<String, Object> payload = Map.of("ok", ok, ok ? "result" : "error", result != null ? result : "");
        return Map.of("role", "tool", "tool_call_id", callId != null ? callId : "",
                "name", call != null ? call.toolName() : "", "content", encode(payload));
    }
    private static String encode(Object value) { try { return MAPPER.writeValueAsString(value); } catch (Exception e) { return String.valueOf(value); } }
    private static String firstString(Object raw) {
        return raw instanceof List<?> list && !list.isEmpty() ? String.valueOf(list.get(0)) : "";
    }
    private static List<String> remainingStrings(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() <= 1) return List.of();
        return list.subList(1, list.size()).stream().map(String::valueOf).toList();
    }
    private static List<StoredCall> storedCalls(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<StoredCall> calls = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> call) || !(call.get("function") instanceof Map<?, ?> fn)) continue;
            String id = String.valueOf(call.get("id"));
            String name = String.valueOf(fn.get("name"));
            Map<String, Object> arguments = Map.of();
            Object rawArguments = fn.get("arguments");
            try {
                if (rawArguments instanceof Map<?, ?> map) arguments = MAPPER.convertValue(map,
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                else if (rawArguments instanceof String json && !json.isBlank()) arguments = MAPPER.readValue(json,
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception e) { throw new IllegalArgumentException("invalid persisted tool arguments", e); }
            calls.add(new StoredCall(id, name, arguments));
        }
        return List.copyOf(calls);
    }
    private record StoredCall(String id, String name, Map<String, Object> arguments) { }
    private static boolean ok(Object value) {
        if (value instanceof Map<?, ?> map && map.get("ok") instanceof Boolean flag) return flag;
        if (value instanceof Map<?, ?> map && map.get("error") != null) return false;
        if (value instanceof String text) return !text.toLowerCase(java.util.Locale.ROOT).startsWith("error");
        return true;
    }
}
