package ricbot.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.runtime.RuntimeDriver;
import ricbot.domain.agent.context.ContextCompactionResult;
import ricbot.domain.agent.context.ContextCompactor;
import ricbot.domain.agent.graph.*;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.security.*;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.ToolRiskDecision;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility adapter for one Agent invocation. Scheduling belongs exclusively to
 * {@link AgentGraphRuntime}; this class only supplies node executors and projects the result.
 */
public class GraphRunService implements AutoCloseable {
    public static final int MAX_INJECTIONS_PER_TURN = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ExecutorService SHARED_EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger sequence = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-graph-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private final LLMProvider provider;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public GraphRunService(LLMProvider provider) { this(provider, SHARED_EXECUTOR, false); }

    public GraphRunService(LLMProvider provider, ExecutorService executor, boolean ownsExecutor) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = executor != null ? executor : SHARED_EXECUTOR;
        this.ownsExecutor = ownsExecutor;
    }

    public AgentRunResult run(AgentRunSpec spec) throws Exception {
        Objects.requireNonNull(spec, "spec");
        Instant started = Instant.now();
        String runId = UUID.randomUUID().toString();
        GraphRuntimeStore store = spec.getWorkspace() != null
                ? new SqliteRuntimeStore(spec.getWorkspace()) : new InMemoryGraphRuntimeStore();
        Execution execution = new Execution(spec, runId, store);
        AgentGraphDefinition definition = AgentGraphRuntimeFactory.definition(spec.getMaxIterations());
        GraphExecutionState seed = GraphExecutionState.initial(definition.graphId(), runId,
                AgentNodeType.INGEST.name(), execution.initialChannels());
        GraphExecutionState state;
        try (AgentGraphRuntime runtime = new AgentGraphRuntime(definition, execution.nodes(),
                new GraphConditionRegistry(), AgentGraphRuntimeFactory.schema(), store, executor, seed)) {
            state = new RuntimeDriver().drive(runtime);
        } catch (Exception failure) {
            execution.notifyError(failure);
            return execution.failedResult(started, failure, store.events(runId));
        }
        return execution.result(started, state, store.events(runId));
    }

    @Override public void close() { if (ownsExecutor) executor.shutdownNow(); }

    private final class Execution {
        private final AgentRunSpec spec;
        private final String runId;
        private final GraphRuntimeStore graphStore;
        private final ToolRegistry tools;
        private final SideEffectCoordinator effects;
        private final ApprovalService approvals;
        private final List<String> toolsUsed = new ArrayList<>();
        private final List<Map<String, Object>> toolEvents = new ArrayList<>();
        private boolean hadInjections;

        private Execution(AgentRunSpec spec, String runId, GraphRuntimeStore graphStore) {
            this.spec = spec;
            this.runId = runId;
            this.graphStore = graphStore;
            this.tools = spec.getTools() != null ? spec.getTools() : new ToolRegistry();
            SideEffectStore sideEffects = spec.getWorkspace() != null
                    ? new SqliteRuntimeStore(spec.getWorkspace()).sideEffectStore() : spec.getSideEffectStore();
            this.effects = new SideEffectCoordinator(sideEffects);
            this.approvals = spec.getApprovalService() != null ? spec.getApprovalService()
                    : spec.getWorkspace() != null ? new ApprovalService(spec.getWorkspace()) : new ApprovalService();
        }

        private Map<String, Object> initialChannels() {
            Map<String, Object> channels = new LinkedHashMap<>();
            channels.put("messages", copyMessages(spec.getInitialMessages()));
            channels.put("modelResponse", Map.of());
            channels.put("pendingToolCalls", List.of());
            channels.put("toolBatch", Map.of());
            channels.put("approvalRequestIds", List.of());
            channels.put("approvalSignal", "");
            channels.put("compactRequested", false);
            channels.put("contextUtilization", utilization(spec.getInitialMessages()));
            channels.put("contextCompactedAt", "");
            channels.put("stopReason", "");
            channels.put("iterations", 0);
            channels.put("usage", Map.of());
            channels.put("finalContent", "");
            channels.put("error", "");
            return channels;
        }

        private GraphNodeRegistry nodes() {
            return new GraphNodeRegistry()
                    .register(AgentNodeType.INGEST.name(), (state, input) -> GraphNodeResult.next("next", Map.of()))
                    .register(AgentNodeType.CONTEXT.name(), this::context)
                    .register(AgentNodeType.COMPACT.name(), this::compact)
                    .register(AgentNodeType.MODEL.name(), this::model)
                    .register(AgentNodeType.TOOLS.name(), this::tool)
                    .register(AgentNodeType.APPROVAL.name(), this::approval)
                    .register(AgentNodeType.STEERING.name(), this::steering);
        }

        private GraphNodeResult context(GraphExecutionState state, Map<String, Object> ignored) {
            double ratio = utilization(messages(state));
            boolean requested = Boolean.TRUE.equals(state.channels().get("compactRequested"));
            return GraphNodeResult.next(requested || ratio >= ContextCompactor.TRIGGER_RATIO ? "compact" : "model",
                    Map.of("contextUtilization", ratio));
        }

        private GraphNodeResult compact(GraphExecutionState state, Map<String, Object> ignored) {
            int budget = Math.max(1, spec.getContextWindowTokens() != null ? spec.getContextWindowTokens() : 128_000);
            ContextCompactionResult result = new ContextCompactor().compact(messages(state), budget,
                    spec.getModel(), null);
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("messages", result.activeMessages());
            writes.put("compactRequested", false);
            writes.put("contextUtilization", result.resultTokens() / (double) budget);
            writes.put("contextCompactedAt", Instant.now().toString());
            return GraphNodeResult.next("next", writes);
        }

        private GraphNodeResult model(GraphExecutionState state, Map<String, Object> ignored) throws Exception {
            int iteration = number(state.channels().get("iterations")) + 1;
            List<Map<String, Object>> messages = messages(state);
            AgentHookContext context = hookContext(messages, iteration);
            invokeHook(() -> spec.getHook().beforeIteration(context));
            LLMResponse response;
            if (spec.getHook() != null && spec.getHook().wantsStreaming()) {
                response = provider.chatStream(messages, tools.getDefinitions(), spec.getModel(), null, null, null,
                        null, delta -> invokeHook(() -> spec.getHook().onStream(context, delta)),
                        end -> invokeHook(() -> spec.getHook().onStreamEnd(context, end.hasToolCalls())));
            } else {
                // Node retry policy is the only automatic retry owner.
                response = provider.chat(messages, tools.getDefinitions(), spec.getModel(), null, null, null, null);
            }
            context.setResponse(response).setToolCalls(response.getToolCalls()).setUsage(response.getUsage());
            invokeHook(() -> spec.getHook().afterIteration(context));
            Map<String, Object> assistant = assistant(response);
            List<Map<String, Object>> nextMessages = append(messages, assistant);
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("messages", nextMessages);
            writes.put("modelResponse", modelResponse(response));
            writes.put("pendingToolCalls", response.getToolCalls().stream().map(ToolCallRequest::toOpenAIToolCall).toList());
            writes.put("stopReason", clean(response.getFinishReason(), "stop"));
            writes.put("iterations", iteration);
            writes.put("usage", response.getUsage() != null ? response.getUsage() : Map.of());
            if (!response.hasToolCalls()) {
                String content = response.getContent() != null ? response.getContent() : "";
                if (spec.getHook() != null) content = spec.getHook().finalizeContent(context, content);
                writes.put("finalContent", content);
                return GraphNodeResult.next("terminal", writes);
            }
            return GraphNodeResult.next("tools", writes);
        }

        private GraphNodeResult tool(GraphExecutionState state, Map<String, Object> ignored) throws Exception {
            List<StoredCall> calls = storedCalls(state.channels().get("pendingToolCalls"));
            List<Map<String, Object>> nextMessages = new ArrayList<>(messages(state));
            List<Map<String, Object>> results = new ArrayList<>();
            List<String> approvalIds = new ArrayList<>();
            AgentHookContext context = hookContext(nextMessages, number(state.channels().get("iterations")));
            invokeHook(() -> spec.getHook().beforeExecuteTools(context));
            for (StoredCall call : calls) {
                ToolRegistry.PrepareResult prepared = tools.prepareCall(call.name(), call.arguments());
                if (prepared.error() != null) {
                    addToolResult(call, prepared.error(), false, nextMessages, results);
                    continue;
                }
                @SuppressWarnings("unchecked") Map<String, Object> arguments = (Map<String, Object>) prepared.params();
                ToolEffectPolicy policy = prepared.tool().effectPolicy();
                ToolRiskDecision riskDecision = prepared.tool().assessRisk(arguments);
                if (riskDecision.decision() == ToolRiskDecision.Decision.DENY) {
                    addToolResult(call, clean(riskDecision.reason(), "tool denied"), false, nextMessages, results);
                    continue;
                }
                String key = runId + ":" + call.id();
                boolean approvalRequired = policy.approval() == ToolEffectPolicy.Approval.ALWAYS
                        || riskDecision.decision() == ToolRiskDecision.Decision.REQUIRE_APPROVAL;
                if (approvalRequired) {
                    RiskAssessment risk = riskDecision.assessment() != null ? riskDecision.assessment()
                            : RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("tool policy requires approval"),
                            "", call.name(), List.of());
                    ApprovalBinding binding = new ApprovalBinding(runId, call.id(), "TOOL", key, call.name(),
                            ToolInvocationRecord.argumentsDigest(arguments));
                    PendingToolCall pending = PendingToolCall.create(null, call.name(), arguments,
                            sessionKey(), risk);
                    ApprovalRequest request = approvals.createRequest(risk, pending, binding);
                    effects.reserveApproval(sessionKey(), key, call.name(), arguments,
                            Map.of("requestId", request.requestId()));
                    approvalIds.add(request.requestId());
                    continue;
                }
                Object value;
                boolean ok;
                notifyToolStart(call.name(), arguments);
                try {
                    SideEffectOutcome outcome = effects.execute(tools, sessionKey(), key, call.name(), arguments,
                            policy.readOnly(), GraphRunService::successful);
                    value = outcome.result();
                    ok = successful(value);
                } catch (Exception failure) {
                    value = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
                    ok = false;
                    if (spec.isFailOnToolError()) throw failure;
                }
                addToolResult(call, value, ok, nextMessages, results);
            }
            invokeHook(() -> spec.getHook().afterExecuteTools(context));
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("messages", List.copyOf(nextMessages));
            writes.put("toolBatch", Map.of("results", List.copyOf(results)));
            writes.put("approvalRequestIds", List.copyOf(approvalIds));
            return GraphNodeResult.next(approvalIds.isEmpty() ? "next" : "approval", writes);
        }

        private GraphNodeResult approval(GraphExecutionState state, Map<String, Object> ignored) {
            String requestId = firstString(state.channels().get("approvalRequestIds"));
            ApprovalRequest request = approvals.find(requestId);
            if (request == null) throw new IllegalStateException("approval request is unavailable: " + requestId);
            return switch (request.status()) {
                case PENDING -> GraphNodeResult.waitFor("tool approval required",
                        GraphWait.external(requestId, request.binding().activationId(), "approval",
                                "tool approval required", Map.of("requestId", requestId)),
                        Map.of("stopReason", "approval_required"));
                case APPROVED, CLAIMED, CONSUMED -> GraphNodeResult.next("approved", Map.of("approvalSignal", "APPROVED"));
                case REJECTED -> {
                    StoredCall call = storedCalls(state.channels().get("pendingToolCalls")).stream().findFirst()
                            .orElse(new StoredCall(request.binding().activationId(), request.pendingToolCall().toolName(),
                                    request.pendingToolCall().arguments()));
                    List<Map<String, Object>> next = new ArrayList<>(messages(state));
                    List<Map<String, Object>> results = new ArrayList<>();
                    addToolResult(call, "tool call rejected by user", false, next, results);
                    yield GraphNodeResult.next("rejected", Map.of("messages", List.copyOf(next),
                            "pendingToolCalls", List.of(), "approvalRequestIds", List.of()));
                }
            };
        }

        private GraphNodeResult steering(GraphExecutionState state, Map<String, Object> ignored) throws Exception {
            List<Map<String, Object>> next = new ArrayList<>(messages(state));
            if (spec.getInjectionCallback() != null) {
                List<Map<String, Object>> injected = spec.getInjectionCallback().inject();
                if (injected != null && !injected.isEmpty()) {
                    next.addAll(injected.stream().limit(MAX_INJECTIONS_PER_TURN).map(LinkedHashMap::new).toList());
                    hadInjections = true;
                }
            }
            int iterations = number(state.channels().get("iterations"));
            if (iterations >= Math.max(1, spec.getMaxIterations())) return GraphNodeResult.next("terminal", Map.of(
                    "messages", List.copyOf(next), "stopReason", "max_iterations",
                    "finalContent", clean(spec.getMaxIterationsMessage(), "maximum iterations reached")));
            return GraphNodeResult.next("next", Map.of("messages", List.copyOf(next)));
        }

        private void addToolResult(StoredCall call, Object value, boolean ok,
                                   List<Map<String, Object>> messages, List<Map<String, Object>> results) {
            Object bounded = bound(value, spec.getMaxToolResultChars());
            Map<String, Object> payload = ok ? Map.of("ok", true, "result", bounded)
                    : Map.of("ok", false, "error", bounded);
            Map<String, Object> message = Map.of("role", "tool", "tool_call_id", clean(call.id(), ""),
                    "name", clean(call.name(), ""), "content", json(payload));
            messages.add(message);
            results.add(message);
            toolsUsed.add(call.name());
            Map<String, Object> event = Map.of("type", "tool_call", "tool", call.name(),
                    "callId", call.id(), "ok", ok);
            toolEvents.add(event);
            notifyToolFinish(event);
        }

        private AgentRunResult result(Instant started, GraphExecutionState state, List<GraphRuntimeEvent> events) {
            String stop = string(state.channels().get("stopReason"));
            if (state.status() == GraphExecutionStatus.PAUSED) stop = "approval_required";
            else if (state.status() == GraphExecutionStatus.CANCELLED) stop = "cancelled";
            else if (state.status() == GraphExecutionStatus.FAILED) stop = "error";
            if (stop.isBlank()) stop = "stop";
            String content = string(state.channels().get("finalContent"));
            if (state.status() == GraphExecutionStatus.PAUSED && content.isBlank())
                content = "工具调用等待审批：" + String.join(", ", strings(state.channels().get("approvalRequestIds")));
            AgentRunResult result = new AgentRunResult().setRunId(runId).setStartedAt(started.toString())
                    .setEndedAt(Instant.now().toString()).setIterations(number(state.channels().get("iterations")))
                    .setFinalContent(content).setStopReason(stop).setMessages(messages(state))
                    .setUsage(integerMap(state.channels().get("usage")));
            result.setToolsUsed(List.copyOf(toolsUsed));
            return result.setToolEvents(List.copyOf(toolEvents)).setHadInjections(hadInjections)
                    .setError(string(state.channels().get("error"))).setRunEvents(runEvents(events));
        }

        private AgentRunResult failedResult(Instant started, Exception failure, List<GraphRuntimeEvent> events) {
            AgentRunResult result = new AgentRunResult().setRunId(runId).setStartedAt(started.toString())
                    .setEndedAt(Instant.now().toString()).setFinalContent(spec.getErrorMessage())
                    .setStopReason("error").setError(failure.getMessage()).setMessages(copyMessages(spec.getInitialMessages()));
            result.setToolsUsed(List.copyOf(toolsUsed));
            return result.setToolEvents(List.copyOf(toolEvents)).setRunEvents(runEvents(events));
        }

        private void notifyError(Exception failure) {
            if (spec.getHook() == null) return;
            try { spec.getHook().onError(hookContext(copyMessages(spec.getInitialMessages()), 0), failure); }
            catch (Exception hookFailure) { if (spec.getHook().isReraise()) throw new RuntimeException(hookFailure); }
        }
        private AgentHookContext hookContext(List<Map<String, Object>> messages, int iteration) {
            return new AgentHookContext().setMessages(messages).setIteration(iteration).setSessionKey(spec.getSessionKey());
        }
        private void invokeHook(Checked action) throws Exception {
            if (spec.getHook() == null) return;
            try { action.run(); } catch (Exception failure) { if (spec.getHook().isReraise()) throw failure; }
        }
        private String sessionKey() { return clean(spec.getSessionKey(), "run:" + runId); }
        private List<Map<String, Object>> runEvents(List<GraphRuntimeEvent> events) {
            List<Map<String, Object>> combined = new ArrayList<>(project(events));
            combined.addAll(toolEvents);
            return List.copyOf(combined);
        }
        private double utilization(List<Map<String, Object>> messages) {
            int budget = Math.max(1, spec.getContextWindowTokens() != null ? spec.getContextWindowTokens() : 128_000);
            return Math.max(0d, json(messages).length() / 4d / budget);
        }
        private void notifyToolStart(String name, Map<String, Object> arguments) {
            if (spec.getToolLifecycleCallback() != null) spec.getToolLifecycleCallback().onToolStart(name, arguments);
        }
        private void notifyToolFinish(Map<String, Object> event) {
            if (spec.getToolLifecycleCallback() != null) spec.getToolLifecycleCallback().onToolFinish(event);
        }
    }

    private static Map<String, Object> assistant(LLMResponse response) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", response.getContent() != null ? response.getContent() : "");
        if (response.hasToolCalls()) message.put("tool_calls", response.getToolCalls().stream()
                .map(ToolCallRequest::toOpenAIToolCall).toList());
        return message;
    }
    private static Map<String, Object> modelResponse(LLMResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", response.getContent() != null ? response.getContent() : "");
        out.put("finishReason", clean(response.getFinishReason(), "stop"));
        out.put("usage", response.getUsage() != null ? response.getUsage() : Map.of());
        out.put("toolCalls", response.getToolCalls().stream().map(ToolCallRequest::toOpenAIToolCall).toList());
        return out;
    }
    private static List<Map<String, Object>> messages(GraphExecutionState state) {
        return copyMessages(state.channels().get("messages") instanceof List<?> list ? list : List.of());
    }
    private static List<Map<String, Object>> copyMessages(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map)
            out.add(MAPPER.convertValue(map, new TypeReference<>() {}));
        return List.copyOf(out);
    }
    private static List<Map<String, Object>> append(List<Map<String, Object>> source, Map<String, Object> item) {
        List<Map<String, Object>> out = new ArrayList<>(source); out.add(item); return List.copyOf(out);
    }
    private static List<StoredCall> storedCalls(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<StoredCall> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> call) {
            Object fnRaw = call.get("function");
            if (!(fnRaw instanceof Map<?, ?> fn)) continue;
            String id = string(call.get("id"));
            String name = string(fn.get("name"));
            Object arguments = fn.get("arguments");
            Map<String, Object> args = Map.of();
            try {
                if (arguments instanceof String text) args = MAPPER.readValue(text, new TypeReference<>() {});
                else if (arguments instanceof Map<?, ?> map) args = MAPPER.convertValue(map, new TypeReference<>() {});
            } catch (Exception ignored) { }
            out.add(new StoredCall(id, name, args));
        }
        return List.copyOf(out);
    }
    private static List<Map<String, Object>> project(List<GraphRuntimeEvent> events) {
        return events.stream().map(event -> {
            Map<String, Object> value = new LinkedHashMap<>(event.data());
            value.put("type", event.type().name().toLowerCase(Locale.ROOT));
            value.put("sequence", event.sequence());
            value.put("superstep", event.superstep());
            return Map.copyOf(value);
        }).toList();
    }
    private static boolean successful(Object result) {
        if (result instanceof String text) return !text.startsWith("Error:") && !text.startsWith("错误");
        return !(result instanceof Map<?, ?> map && (map.containsKey("error") || Boolean.FALSE.equals(map.get("ok"))));
    }
    private static Object bound(Object value, int maximum) {
        String text = value instanceof String string ? string : json(value);
        int limit = maximum > 0 ? maximum : 16_000;
        return text.length() <= limit ? value : text.substring(0, limit) + "\n...[truncated]";
    }
    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); } catch (Exception failure) { return String.valueOf(value); }
    }
    private static int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static String string(Object value) { return value != null ? String.valueOf(value) : ""; }
    private static String clean(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
    private static String firstString(Object raw) {
        List<String> values = strings(raw); return values.isEmpty() ? "" : values.get(0);
    }
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
    private static Map<String, Integer> integerMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>();
        map.forEach((key, value) -> { if (value instanceof Number number) out.put(String.valueOf(key), number.intValue()); });
        return Map.copyOf(out);
    }
    private record StoredCall(String id, String name, Map<String, Object> arguments) { }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
