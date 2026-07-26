package ricbot.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.runtime.RuntimeDriver;
import ricbot.application.runtime.LocalAgentRuntime;
import ricbot.domain.agent.context.ContextCompactionResult;
import ricbot.domain.agent.context.ContextCompactor;
import ricbot.domain.agent.graph.*;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.security.*;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMFailureKind;
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
import java.util.concurrent.ConcurrentHashMap;
import ricbot.domain.runtime.RunRequest;

/**
 * Concrete production Agent graph factory. Scheduling belongs exclusively to
 * {@link AgentGraphRuntime}; this class supplies node executors and projects results.
 */
public class AgentGraphFactory implements LocalAgentRuntime.GraphFactory, AutoCloseable {
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
    private final ToolRegistry defaultTools;
    private final SideEffectStore defaultSideEffects;
    private final ApprovalService defaultApprovals;
    private final Map<String, Execution> preparedExecutions = new ConcurrentHashMap<>();

    public AgentGraphFactory(LLMProvider provider) {
        this(provider, SHARED_EXECUTOR, false, null, SideEffectStore.disabled(), null);
    }

    public AgentGraphFactory(LLMProvider provider, ExecutorService executor, boolean ownsExecutor) {
        this(provider, executor, ownsExecutor, null, SideEffectStore.disabled(), null);
    }

    public AgentGraphFactory(LLMProvider provider, ExecutorService executor, boolean ownsExecutor,
                           ToolRegistry defaultTools, SideEffectStore defaultSideEffects,
                           ApprovalService defaultApprovals) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = executor != null ? executor : SHARED_EXECUTOR;
        this.ownsExecutor = ownsExecutor;
        this.defaultTools = defaultTools;
        this.defaultSideEffects = defaultSideEffects != null ? defaultSideEffects : SideEffectStore.disabled();
        this.defaultApprovals = defaultApprovals;
    }

    /** In-package harness for graph node tests; production invocations enter through AgentRuntime. */
    AgentRunResult runForTest(AgentRunSpec spec) throws Exception {
        Objects.requireNonNull(spec, "spec");
        Instant started = Instant.now();
        String runId = UUID.randomUUID().toString();
        GraphRuntimeStore store = spec.getWorkspace() != null
                ? ricbot.app.bootstrap.RuntimeStoreRegistry.shared(spec.getWorkspace()) : new InMemoryGraphRuntimeStore();
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

    public void prepare(String runId, AgentRunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Path runtimeWorkspace = spec.getRuntimeWorkspace() != null ? spec.getRuntimeWorkspace() : spec.getWorkspace();
        GraphRuntimeStore store = runtimeWorkspace != null
                ? ricbot.app.bootstrap.RuntimeStoreRegistry.shared(runtimeWorkspace) : new InMemoryGraphRuntimeStore();
        Execution previous = preparedExecutions.putIfAbsent(runId, new Execution(spec, runId, store));
        if (previous != null) throw new IllegalStateException("run is already prepared: " + runId);
    }

    @Override public AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint) {
        Execution execution = preparedExecutions.computeIfAbsent(request.runId(), ignored -> {
            AgentRunSpec restored = restoreSpec(request, checkpoint);
            GraphRuntimeStore store = request.workspace() != null
                    ? ricbot.app.bootstrap.RuntimeStoreRegistry.shared(request.workspace())
                    : new InMemoryGraphRuntimeStore();
            return new Execution(restored, request.runId(), store);
        });
        AgentGraphDefinition definition = AgentGraphRuntimeFactory.definition(execution.spec.getMaxIterations());
        GraphExecutionState seed = checkpoint != null ? checkpoint
                : GraphExecutionState.initial(definition.graphId(), request.runId(), AgentNodeType.INGEST.name(),
                execution.initialChannels(request));
        return new AgentGraphRuntime(definition, execution.nodes(), new GraphConditionRegistry(),
                AgentGraphRuntimeFactory.schema(), execution.graphStore, executor, seed);
    }

    public AgentRunResult result(String runId, Instant started, GraphExecutionState state) {
        Execution execution = requireExecution(runId);
        return execution.result(started, state, execution.graphStore.events(runId));
    }

    public AgentRunResult failedResult(String runId, Instant started, Exception failure) {
        Execution execution = requireExecution(runId);
        execution.notifyError(failure);
        return execution.failedResult(started, failure, execution.graphStore.events(runId));
    }

    public void release(String runId) { preparedExecutions.remove(runId); }

    private Execution requireExecution(String runId) {
        Execution execution = preparedExecutions.get(runId);
        if (execution == null) throw new IllegalStateException("run execution is not prepared: " + runId);
        return execution;
    }

    private AgentRunSpec restoreSpec(RunRequest request, GraphExecutionState checkpoint) {
        Map<String, Object> channels = checkpoint != null ? checkpoint.channels() : Map.of();
        Map<String, Object> config = channels.get("runConfig") instanceof Map<?, ?> raw
                ? MAPPER.convertValue(raw, new TypeReference<>() { }) : request.metadata();
        Path toolWorkspace = config.get("toolWorkspace") != null
                && !string(config.get("toolWorkspace")).isBlank()
                ? Path.of(string(config.get("toolWorkspace"))).toAbsolutePath().normalize() : request.workspace();
        AgentRunSpec spec = new AgentRunSpec().setWorkspace(toolWorkspace).setRuntimeWorkspace(request.workspace()).setSessionKey(
                        string(channels.getOrDefault("sessionId", request.sessionId())))
                .setInitialMessages(copyMessages(channels.getOrDefault("messages", List.of())))
                .setModel(string(config.getOrDefault("model", provider.getDefaultModel())))
                .setCompactModel(string(config.getOrDefault("compactModel",
                        config.getOrDefault("model", provider.getDefaultModel()))))
                .setMaxIterations(Math.max(1, number(config.getOrDefault("maxIterations", 20))))
                .setMaxToolResultChars(Math.max(1_000,
                        number(config.getOrDefault("maxToolResultChars", 16_000))))
                .setContextWindowTokens(Math.max(1_000,
                        number(config.getOrDefault("contextWindowTokens", 128_000))))
                .setProviderRetryMode(string(config.getOrDefault("providerRetryMode", "standard")))
                .setTools(defaultTools != null ? defaultTools : new ToolRegistry())
                .setSideEffectStore(defaultSideEffects)
                .setApprovalService(defaultApprovals);
        Object allowed = config.get("allowedTools");
        if (allowed instanceof List<?> list) spec.setAllowedTools(list.stream().map(String::valueOf).toList());
        Object metadata = config.get("metadata");
        if (metadata instanceof Map<?, ?> rawMetadata) {
            spec.setMetadata(MAPPER.convertValue(rawMetadata, new TypeReference<>() { }));
        }
        return spec;
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
            Path runtimeWorkspace = spec.getRuntimeWorkspace() != null ? spec.getRuntimeWorkspace() : spec.getWorkspace();
            SideEffectStore sideEffects = spec.getSideEffectStore() != null
                    && spec.getSideEffectStore() != SideEffectStore.disabled()
                    ? spec.getSideEffectStore() : defaultSideEffects;
            this.effects = runtimeWorkspace != null
                    ? new SideEffectCoordinator(sideEffects,
                    ricbot.app.bootstrap.RuntimeStoreRegistry.lifecycle(runtimeWorkspace).instance().instanceId(),
                    java.time.Duration.ofSeconds(30))
                    : new SideEffectCoordinator(sideEffects);
            this.approvals = spec.getApprovalService() != null ? spec.getApprovalService()
                    : spec.getWorkspace() != null ? new ApprovalService(spec.getWorkspace()) : new ApprovalService();
        }

        private Map<String, Object> initialChannels() {
            return initialChannels(null);
        }

        private Map<String, Object> initialChannels(RunRequest request) {
            Map<String, Object> channels = new LinkedHashMap<>();
            channels.put("messages", copyMessages(spec.getInitialMessages()));
            channels.put("modelResponse", Map.of());
            channels.put("pendingToolCalls", List.of());
            channels.put("toolBatch", Map.of());
            channels.put("approvalRequestIds", List.of());
            channels.put("approvalSignal", "");
            channels.put("compactRequested", false);
            channels.put("overflowCompactions", 0);
            channels.put("contextUtilization", utilization(spec.getInitialMessages()));
            channels.put("contextCompactedAt", "");
            channels.put("stopReason", "");
            channels.put("iterations", 0);
            channels.put("usage", Map.of());
            channels.put("finalContent", "");
            channels.put("error", "");
            channels.put("goal", request != null ? request.goal() : lastUserGoal(spec.getInitialMessages()));
            channels.put("sessionId", sessionKey());
            Map<String, Object> runConfig = new LinkedHashMap<>();
            runConfig.put("model", clean(spec.getModel(), provider.getDefaultModel()));
            runConfig.put("compactModel", clean(spec.getCompactModel(),
                    clean(spec.getModel(), provider.getDefaultModel())));
            runConfig.put("maxIterations", spec.getMaxIterations());
            runConfig.put("maxToolResultChars", spec.getMaxToolResultChars());
            runConfig.put("contextWindowTokens", spec.getContextWindowTokens() != null
                    ? spec.getContextWindowTokens() : 128_000);
            runConfig.put("providerRetryMode", clean(spec.getProviderRetryMode(), "standard"));
            runConfig.put("allowedTools", spec.getAllowedTools() != null ? spec.getAllowedTools() : List.of());
            runConfig.put("toolWorkspace", spec.getWorkspace() != null ? spec.getWorkspace().toString() : "");
            runConfig.put("metadata", serializableMetadata(spec.getMetadata()));
            if (spec.getMetadata() != null) {
                runConfig.put("taskId", string(spec.getMetadata().get("taskId")));
                runConfig.put("parentRunId", string(spec.getMetadata().get("parentRunId")));
            }
            channels.put("runConfig", Map.copyOf(runConfig));
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
            ApprovalRequest control = approvals.list().stream()
                    .filter(request -> request.binding() != null && request.binding().bound())
                    .filter(request -> runId.equals(request.binding().runId()))
                    .filter(request -> SideEffectApplicationService.RETRY_ACTION.equals(request.binding().actionType())
                            || SideEffectApplicationService.COMPENSATE_ACTION.equals(request.binding().actionType()))
                    .filter(request -> request.status() == ApprovalRequest.ApprovalStatus.APPROVED
                            || request.status() == ApprovalRequest.ApprovalStatus.CLAIMED)
                    .filter(request -> !request.consumed())
                    .findFirst().orElse(null);
            if (control != null) {
                Map<String, Object> arguments = new LinkedHashMap<>(control.pendingToolCall().arguments());
                arguments.put("request_id", control.requestId());
                Map<String, Object> call = Map.of("id", "control:" + control.requestId(), "type", "function",
                        "function", Map.of("name", control.binding().actionType(),
                                "arguments", json(arguments)));
                return GraphNodeResult.next("tools", Map.of("pendingToolCalls", List.of(call),
                        "approvalRequestIds", List.of(control.requestId())));
            }
            double ratio = utilization(messages(state));
            boolean requested = Boolean.TRUE.equals(state.channels().get("compactRequested"));
            return GraphNodeResult.next(requested || ratio >= ContextCompactor.TRIGGER_RATIO ? "compact" : "model",
                    Map.of("contextUtilization", ratio));
        }

        private GraphNodeResult compact(GraphExecutionState state, Map<String, Object> ignored) {
            int budget = Math.max(1, spec.getContextWindowTokens() != null ? spec.getContextWindowTokens() : 128_000);
            boolean forced = Boolean.TRUE.equals(state.channels().get("compactRequested"));
            String compactModel = clean(spec.getCompactModel(), clean(spec.getModel(), provider.getDefaultModel()));
            ContextCompactionResult result = new ContextCompactor().compact(messages(state), budget,
                    compactModel, this::summarize, forced);
            if (result.compacted()) {
                graphStore.append(runId, state.superstep(), GraphRuntimeEventType.CONTEXT_COMPACTED,
                        Map.of("messages", result.eventMessages(), "sourceMessageIds", result.sourceMessageIds(),
                                "model", result.model(), "sourceTokens", result.sourceTokens(),
                                "resultTokens", result.resultTokens(), "promptDigest", result.promptDigest(),
                                "resultDigest", result.resultDigest(), "degraded", result.degraded()),
                        "context-compacted:" + state.superstep());
            }
            if (result.degraded()) {
                graphStore.append(runId, state.superstep(), GraphRuntimeEventType.CONTEXT_COMPACTION_DEGRADED,
                        Map.of("model", compactModel, "sourceMessageIds", result.sourceMessageIds(),
                                "promptDigest", result.promptDigest(), "resultDigest", result.resultDigest()),
                        "compact-degraded:" + state.superstep());
            }
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("messages", result.activeMessages());
            writes.put("compactRequested", false);
            writes.put("contextUtilization", result.resultTokens() / (double) budget);
            writes.put("contextCompactedAt", Instant.now().toString());
            return GraphNodeResult.next("next", writes);
        }

        private ricbot.domain.agent.context.StructuredContextSummary summarize(
                List<Map<String, Object>> source, String prompt) throws Exception {
            LLMResponse response = LLMFailureException.requireSuccess(provider.chat(
                    List.of(Map.of("role", "system", "content", prompt)), List.of(),
                    clean(spec.getCompactModel(), clean(spec.getModel(), provider.getDefaultModel())),
                    null, null, null, null));
            String content = response.getContent() != null ? response.getContent().trim() : "";
            if (content.startsWith("```")) {
                content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            if (content.isBlank()) throw new IllegalStateException("compact model returned an empty summary");
            return MAPPER.readValue(content, ricbot.domain.agent.context.StructuredContextSummary.class);
        }

        private GraphNodeResult model(GraphExecutionState state, Map<String, Object> ignored) throws Exception {
            int iteration = number(state.channels().get("iterations")) + 1;
            List<Map<String, Object>> messages = messages(state);
            AgentHookContext context = hookContext(messages, iteration);
            invokeHook(() -> spec.getHook().beforeIteration(context));
            LLMResponse response;
            try {
                if (spec.getHook() != null && spec.getHook().wantsStreaming()) {
                    response = LLMFailureException.requireSuccess(provider.chatStream(messages,
                            tools.getDefinitions(), spec.getModel(), null, null, null,
                            null, delta -> invokeHook(() -> spec.getHook().onStream(context, delta)),
                            end -> invokeHook(() -> spec.getHook().onStreamEnd(context, end.hasToolCalls()))));
                } else {
                    // Node retry policy is the only automatic retry owner.
                    response = LLMFailureException.requireSuccess(provider.chat(messages,
                            tools.getDefinitions(), spec.getModel(), null, null, null, null));
                }
            } catch (Exception providerFailure) {
                LLMFailureException failure = LLMFailureException.classify(providerFailure);
                if (failure.kind() == LLMFailureKind.CONTEXT_OVERFLOW) {
                    int completedCompactions = number(state.channels().get("overflowCompactions"));
                    if (completedCompactions == 0) {
                        return GraphNodeResult.next("overflow", Map.of("compactRequested", true,
                                "overflowCompactions", 1, "stopReason", "context_overflow"));
                    }
                    throw new GraphNonRetryableException("model context overflow after compaction", failure);
                }
                if (failure.kind() == LLMFailureKind.AUTH || failure.kind() == LLMFailureKind.PERMANENT) {
                    throw new GraphNonRetryableException("non-retryable model failure: " + failure.getMessage(), failure);
                }
                throw failure;
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
                if (SideEffectApplicationService.RETRY_ACTION.equals(call.name())
                        || SideEffectApplicationService.COMPENSATE_ACTION.equals(call.name())) {
                    executeSideEffectControl(state, call, nextMessages, results);
                    continue;
                }
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
                ApprovalRequest recoveryApproval = approvals.findByBinding(runId, key,
                        SideEffectApplicationService.RETRY_ACTION);
                if (recoveryApproval != null) {
                    switch (recoveryApproval.status()) {
                        case PENDING -> {
                            approvalIds.add(recoveryApproval.requestId());
                            continue;
                        }
                        case REJECTED -> {
                            addToolResult(call, "uncertain side effect retry rejected by user", false,
                                    nextMessages, results);
                            continue;
                        }
                        case APPROVED -> {
                            recoveryApproval = approvals.claim(recoveryApproval.requestId());
                            effects.authorizeRetry(key, recoveryApproval.requestId());
                        }
                        case CLAIMED -> {
                            SideEffectRecord current = effects.load(key).orElse(null);
                            if (current != null && current.status() == SideEffectStatus.UNKNOWN) {
                                effects.authorizeRetry(key, recoveryApproval.requestId());
                            }
                        }
                        case CONSUMED -> {
                            SideEffectRecord current = effects.load(key).orElse(null);
                            if (current != null && (current.status() == SideEffectStatus.SUCCEEDED
                                    || current.status() == SideEffectStatus.FAILED)) {
                                addToolResult(call, current.result(), successful(current.result()),
                                        nextMessages, results);
                                continue;
                            }
                            throw new IllegalStateException(
                                    "consumed retry approval has no durable side-effect result");
                        }
                    }
                }
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
                    SideEffectExecutionIdentity identity = effectIdentity(state, call.id());
                    SideEffectRecord effect = effects.reserveApproval(identity, key, call.name(), arguments,
                            Map.of("requestId", request.requestId()));
                    switch (request.status()) {
                        case PENDING -> {
                            approvalIds.add(request.requestId());
                            continue;
                        }
                        case REJECTED -> {
                            addToolResult(call, "tool call rejected by user", false, nextMessages, results);
                            continue;
                        }
                        case CONSUMED -> {
                            addToolResult(call, effect.result(), successful(effect.result()), nextMessages, results);
                            continue;
                        }
                        case APPROVED, CLAIMED -> {
                            if (request.status() == ApprovalRequest.ApprovalStatus.APPROVED) {
                                request = approvals.claim(request.requestId());
                            }
                            if (effect.status() == SideEffectStatus.AWAITING_APPROVAL) {
                                effects.authorizeApproval(key, request.requestId());
                            }
                        }
                    }
                }
                Object value;
                boolean ok;
                notifyToolStart(call.name(), arguments);
                try {
                    SideEffectOutcome outcome = effects.execute(tools, effectIdentity(state, call.id()), key,
                            call.name(), arguments, policy, AgentGraphFactory::successful,
                            ignoredResult -> false);
                    value = outcome.result();
                    ok = successful(value);
                    if (approvalRequired) {
                        ApprovalRequest request = approvals.findByBinding(runId, key, "TOOL");
                        if (request != null && !request.consumed()) approvals.completeClaim(request.requestId());
                    }
                    if (recoveryApproval != null && !recoveryApproval.consumed()) {
                        approvals.completeClaim(recoveryApproval.requestId());
                    }
                } catch (Exception failure) {
                    boolean confirmationRequired = failure instanceof SideEffectConfirmationRequiredException;
                    boolean durableRetry = policy.retry() == ToolEffectPolicy.Retry.READ_ONLY_3
                            || policy.retry() == ToolEffectPolicy.Retry.IDEMPOTENT_3;
                    if (durableRetry && !confirmationRequired) throw failure;
                    SideEffectRecord uncertain = effects.load(key).orElse(null);
                    if (confirmationRequired || uncertain != null && uncertain.status() == SideEffectStatus.UNKNOWN) {
                        if (uncertain == null) throw failure;
                        ApprovalBinding binding = new ApprovalBinding(runId,
                                effectIdentity(state, call.id()).activationId(),
                                SideEffectApplicationService.RETRY_ACTION, key, call.name(),
                                uncertain.argumentsDigest());
                        RiskAssessment risk = RiskAssessment.of(CommandRiskLevel.HIGH,
                                List.of("External result is unknown",
                                        "Explicit retry authorization is required"),
                                SideEffectApplicationService.RETRY_ACTION, call.name(), List.of());
                        PendingToolCall pending = PendingToolCall.create(null,
                                SideEffectApplicationService.RETRY_ACTION,
                                Map.of("idempotency_key", key), sessionKey(), risk);
                        ApprovalRequest request = approvals.createRequest(risk, pending, binding);
                        approvalIds.add(request.requestId());
                        continue;
                    }
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

        private void executeSideEffectControl(GraphExecutionState state, StoredCall call,
                                              List<Map<String, Object>> messages,
                                              List<Map<String, Object>> results) {
            String requestId = string(call.arguments().get("request_id"));
            String key = string(call.arguments().get("idempotency_key"));
            ApprovalRequest request = approvals.find(requestId);
            if (request == null || request.binding() == null || !request.binding().bound()
                    || !runId.equals(request.binding().runId())
                    || !key.equals(request.binding().idempotencyKey())
                    || !call.name().equals(request.binding().actionType())) {
                throw new GraphNonRetryableException("invalid side-effect control approval binding", null);
            }
            if (request.status() == ApprovalRequest.ApprovalStatus.APPROVED) request = approvals.claim(requestId);
            if (request.status() != ApprovalRequest.ApprovalStatus.CLAIMED) {
                throw new GraphNonRetryableException("side-effect control approval is not claimable", null);
            }
            SideEffectRecord record = effects.load(key).orElseThrow(() ->
                    new GraphNonRetryableException("side effect does not exist: " + key, null));
            Object value;
            if (SideEffectApplicationService.RETRY_ACTION.equals(call.name())) {
                if (record.status() == SideEffectStatus.UNKNOWN) {
                    record = effects.authorizeRetry(key, requestId);
                }
                ricbot.tool.api.Tool retryTool = tools.get(record.toolName());
                if (retryTool == null) {
                    throw new GraphNonRetryableException("tool is unavailable: " + record.toolName(), null);
                }
                ToolEffectPolicy policy = retryTool.effectPolicy();
                SideEffectOutcome outcome = effects.execute(tools,
                        new SideEffectExecutionIdentity(record.runId(), record.sessionKey(), record.taskId(),
                        record.activationId()), key, record.toolName(), record.arguments(),
                        policy, AgentGraphFactory::successful, ignored -> false);
                value = outcome.result();
            } else {
                value = effects.compensate(tools, key, record.arguments(), requestId).result();
            }
            approvals.completeClaim(requestId);
            addToolResult(call, value, successful(value), messages, results);
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

        private SideEffectExecutionIdentity effectIdentity(GraphExecutionState state, String callId) {
            String activationId = state.activeNodes().isEmpty() ? callId
                    : state.activeNodes().get(0).activationId();
            String taskId = spec.getMetadata() != null
                    ? string(spec.getMetadata().getOrDefault("taskId", "")) : "";
            return new SideEffectExecutionIdentity(runId, sessionKey(), taskId, activationId);
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
    private static String lastUserGoal(List<Map<String, Object>> messages) {
        if (messages != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                Map<String, Object> message = messages.get(index);
                if ("user".equals(string(message.get("role")))) {
                    String content = string(message.get("content")).trim();
                    if (!content.isBlank()) return content;
                }
            }
        }
        return "agent invocation";
    }
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
    private static Map<String, Object> serializableMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        try { return MAPPER.convertValue(MAPPER.valueToTree(metadata), new TypeReference<>() { }); }
        catch (Exception ignored) { return Map.of(); }
    }
    private record StoredCall(String id, String name, Map<String, Object> arguments) { }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
