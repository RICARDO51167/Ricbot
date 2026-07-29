package ricbot.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.runtime.RuntimeDriver;
import ricbot.application.runtime.LocalAgentRuntime;
import ricbot.domain.agent.context.dto.ContextCompactionResult;
import ricbot.domain.agent.context.ContextCompactor;
import ricbot.domain.agent.context.dto.StructuredContextSummary;
import ricbot.domain.agent.dto.SideEffectExecutionIdentity;
import ricbot.domain.agent.dto.SideEffectOutcome;
import ricbot.domain.agent.dto.SideEffectRecord;
import ricbot.domain.agent.dto.ToolInvocationRecord;
import ricbot.domain.agent.eump.AgentNodeType;
import ricbot.domain.agent.eump.SideEffectStatus;
import ricbot.domain.agent.graph.*;
import ricbot.domain.agent.graph.dto.*;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;
import ricbot.domain.agent.graph.enump.GraphRuntimeEventType;
import ricbot.domain.agent.graph.exceptionp.GraphNonRetryableException;
import ricbot.domain.agent.graph.interfacep.GraphRuntimeStore;
import ricbot.domain.agent.interfacep.SideEffectStore;
import ricbot.domain.agent.artifact.ArtifactRef;
import ricbot.domain.agent.artifact.ArtifactStore;
import ricbot.domain.agent.event.AgentEvent;
import ricbot.domain.agent.event.AgentEventReconstructor;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.agent.budget.BudgetSnapshot;
import ricbot.domain.agent.budget.BudgetReservation;
import ricbot.domain.agent.budget.BudgetCoordinator;
import ricbot.domain.agent.hint.RuntimeHint;
import ricbot.domain.agent.usage.UsageDelta;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.agent.usage.UsagePricer;
import ricbot.domain.agent.structured.StructuredOutputService;
import ricbot.domain.agent.structured.StructuredRequest;
import ricbot.domain.agent.middleware.AgentMiddleware;
import ricbot.domain.agent.middleware.AgentMiddlewareChain;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.security.*;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMFailureKind;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.ToolRiskDecision;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.api.ToolExposure;
import ricbot.tool.api.ToolGroup;
import ricbot.tool.api.ManageToolGroupsTool;
import ricbot.tool.artifact.ArtifactGrepTool;
import ricbot.tool.artifact.ArtifactListTool;
import ricbot.tool.artifact.ArtifactReadTool;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import ricbot.domain.runtime.dto.RunRequest;

/**
 * Concrete production Agent graph factory. Scheduling belongs exclusively to
 * {@link AgentGraphRuntime}; this class supplies node executors and projects results.
 */
public class AgentGraphFactory implements LocalAgentRuntime.GraphFactory, AutoCloseable {
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

    public AgentGraphFactory(LLMProvider provider, ExecutorService executor, boolean ownsExecutor,
                           ToolRegistry defaultTools, SideEffectStore defaultSideEffects,
                           ApprovalService defaultApprovals) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = executor != null ? executor : SHARED_EXECUTOR;
        this.ownsExecutor = ownsExecutor;
        this.defaultTools = Objects.requireNonNull(defaultTools, "defaultTools");
        this.defaultSideEffects = Objects.requireNonNull(defaultSideEffects, "defaultSideEffects");
        this.defaultApprovals = Objects.requireNonNull(defaultApprovals, "defaultApprovals");
    }

    /** In-package harness for graph node tests; production invocations enter through AgentRuntime. */
    AgentRunResult runForTest(AgentRunSpec spec) throws Exception {
        Objects.requireNonNull(spec, "spec");
        Instant started = Instant.now();
        String runId = UUID.randomUUID().toString();
        GraphRuntimeStore store = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(
                Objects.requireNonNull(spec.getWorkspace(), "workspace"));
        Execution execution = new Execution(spec, runId, store);
        AgentGraphDefinition definition = AgentGraphRuntimeFactory.definition(spec.getMaxIterations());
        GraphExecutionState seed = GraphExecutionState.initial(definition.graphId(), runId,
                AgentNodeType.INGEST.name(), execution.initialChannels());
        GraphExecutionState state;
        try (AgentGraphRuntime runtime = new AgentGraphRuntime(definition, execution.nodes(),
                new GraphConditionRegistry(), AgentGraphRuntimeFactory.schema(), store, executor, seed)) {
            state = new RuntimeDriver().drive(runtime);
        } catch (Exception failure) {
            return execution.failedResult(started, failure, store.events(runId));
        }
        return execution.result(started, state, store.events(runId));
    }

    public void prepare(String runId, AgentRunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Path runtimeWorkspace = spec.getRuntimeWorkspace() != null ? spec.getRuntimeWorkspace() : spec.getWorkspace();
        GraphRuntimeStore store = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(
                Objects.requireNonNull(runtimeWorkspace, "runtimeWorkspace"));
        Execution previous = preparedExecutions.putIfAbsent(runId, new Execution(spec, runId, store));
        if (previous != null) throw new IllegalStateException("run is already prepared: " + runId);
    }

    @Override public AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint) {
        Execution execution = preparedExecutions.computeIfAbsent(request.runId(), ignored -> {
            AgentRunSpec restored = restoreSpec(request, checkpoint);
            GraphRuntimeStore store = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(
                    Objects.requireNonNull(request.workspace(), "runtime workspace"));
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
        Object budget = config.get("budgetPolicy");
        if (budget != null) spec.setBudgetPolicy(MAPPER.convertValue(budget, BudgetPolicy.class));
        Object rootBudget = config.get("rootBudgetPolicy");
        if (rootBudget != null) spec.setRootBudgetPolicy(MAPPER.convertValue(rootBudget, BudgetPolicy.class));
        Object pricing = config.get("modelPricing");
        if (pricing != null) spec.setModelPricing(MAPPER.convertValue(pricing, ricbot.domain.config.ModelCard.Pricing.class));
        spec.setContextOffloadEnabled(!Boolean.FALSE.equals(config.get("contextOffloadEnabled")))
                .setOffloadPreviewChars(Math.max(0, number(config.getOrDefault("offloadPreviewChars", 1200))))
                .setArtifactReadChunkChars(Math.max(1, number(config.getOrDefault("artifactReadChunkChars", 16000))))
                .setTimezone(clean(string(config.get("timezone")), "UTC"));
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
        private final List<ArtifactRef> newArtifactRefs = new ArrayList<>();
        private final List<AgentEvent> agentEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicLong agentEventSequence = new java.util.concurrent.atomic.AtomicLong();
        private final ArtifactStore artifacts;
        private final AgentMiddlewareChain middleware;
        private final BudgetCoordinator budgetCoordinator;
        private final String rootRunId;
        private final String taskId;
        private final Map<String, List<BudgetReservation>> toolReservations = new HashMap<>();
        private final Map<String, Long> toolStartedAt = new HashMap<>();
        private UsageLedger nodeToolUsage = UsageLedger.empty();
        private UsageLedger lastStructuredUsage = UsageLedger.empty();

        private Execution(AgentRunSpec spec, String runId, GraphRuntimeStore graphStore) {
            this.spec = spec;
            this.runId = runId;
            this.graphStore = graphStore;
            this.tools = spec.getTools() != null ? spec.getTools().copy() : new ToolRegistry();
            Path runtimeWorkspace = spec.getRuntimeWorkspace() != null ? spec.getRuntimeWorkspace() : spec.getWorkspace();
            String parentRunId = spec.getMetadata() != null ? string(spec.getMetadata().get("parentRunId")) : "";
            String taskId = spec.getMetadata() != null ? string(spec.getMetadata().get("taskId")) : "";
            this.rootRunId = parentRunId.isBlank() ? runId : parentRunId;
            this.taskId = taskId;
            this.artifacts = new ArtifactStore(Objects.requireNonNull(runtimeWorkspace, "runtimeWorkspace"),
                    rootRunId, runId, taskId);
            this.tools.register(new ArtifactListTool(artifacts), ToolGroup.BASIC);
            this.tools.register(new ArtifactReadTool(artifacts, spec.getArtifactReadChunkChars()), ToolGroup.BASIC);
            this.tools.register(new ArtifactGrepTool(artifacts), ToolGroup.BASIC);
            this.tools.register(new ManageToolGroupsTool(this.tools), ToolGroup.BASIC);
            if ("team-worker".equals(spec.getMetadata() != null ? spec.getMetadata().get("mode") : null)) {
                boolean writable = this.tools.toolNames().contains("write_file") || this.tools.toolNames().contains("edit_file");
                Set<ToolGroup> groups = writable ? Set.of(ToolGroup.BASIC, ToolGroup.CODING) : Set.of(ToolGroup.BASIC);
                this.tools.setExposure(new ToolExposure(groups, groups));
            }
            SideEffectStore sideEffects = spec.getSideEffectStore() != null ? spec.getSideEffectStore() : defaultSideEffects;
            this.effects = new SideEffectCoordinator(sideEffects,
                    ricbot.app.bootstrap.RuntimeStoreRegistry.lifecycle(
                            Objects.requireNonNull(runtimeWorkspace, "runtimeWorkspace")).instance().instanceId(),
                    java.time.Duration.ofSeconds(30));
            this.approvals = spec.getApprovalService() != null ? spec.getApprovalService()
                    : defaultApprovals;
            this.middleware = new AgentMiddlewareChain(List.of(
                    new NamedMiddleware("tracing"), new NamedMiddleware("budget"),
                    new NamedMiddleware("runtime-hint"), new NamedMiddleware("memory"),
                    new NamedMiddleware("tool-exposure"), new NamedMiddleware("provider-fallback"),
                    new NamedMiddleware("context-offload")));
            this.budgetCoordinator = new BudgetCoordinator(graphStore);
            if (spec.getBudgetPolicy().maxCostMicrousd() != null && spec.getModelPricing() == null) {
                throw new IllegalArgumentException("cost budget requires model pricing");
            }
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
            channels.put("usageLedger", UsageLedger.empty());
            channels.put("budgetState", BudgetSnapshot.evaluate(spec.getBudgetPolicy(), UsageLedger.empty(), false));
            channels.put("middlewareState", Map.of("schemaVersion", 1, "versions", middleware.stateVersions()));
            channels.put("runtimeHints", Map.of());
            channels.put("toolExposure", exposureMap());
            channels.put("artifactRefs", List.of());
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
            runConfig.put("budgetPolicy", spec.getBudgetPolicy());
            if (spec.getRootBudgetPolicy() != null) runConfig.put("rootBudgetPolicy", spec.getRootBudgetPolicy());
            if (spec.getModelPricing() != null) runConfig.put("modelPricing", spec.getModelPricing());
            runConfig.put("contextOffloadEnabled", spec.isContextOffloadEnabled());
            runConfig.put("offloadPreviewChars", spec.getOffloadPreviewChars());
            runConfig.put("artifactReadChunkChars", spec.getArtifactReadChunkChars());
            runConfig.put("timezone", spec.getTimezone());
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
                    .filter(request -> SideEffectApplicationService.RETRY_ACTION.equals(request.binding().actionType()))
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

        private GraphNodeResult compact(GraphExecutionState state, Map<String, Object> ignored) throws Exception {
            long activeStarted = System.nanoTime();
            lastStructuredUsage = UsageLedger.empty();
            int budget = Math.max(1, spec.getContextWindowTokens() != null ? spec.getContextWindowTokens() : 128_000);
            boolean forced = Boolean.TRUE.equals(state.channels().get("compactRequested"));
            String compactModel = clean(spec.getCompactModel(), clean(spec.getModel(), provider.getDefaultModel()));
            ContextCompactionResult result = (ContextCompactionResult) middleware.compression(
                    new AgentMiddleware.CompressionContext(middlewareContext(state), messages(state), compactModel),
                    request -> new ContextCompactor().compact(request.messages(), budget,
                            request.model(), this::summarize, forced));
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
            if (result.compacted() && spec.isContextOffloadEnabled()) {
                try {
                    ArtifactRef ref = artifacts.writeText(json(result.eventMessages()),
                            "context-compaction:" + state.superstep(), spec.getOffloadPreviewChars());
                    writes.put("artifactRefs", List.of(artifactMap(ref)));
                    graphStore.append(runId, state.superstep(), GraphRuntimeEventType.ARTIFACT_OFFLOADED,
                            MAPPER.convertValue(ref, new TypeReference<>() { }), "artifact:" + ref.artifactId());
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot offload compacted context", failure);
                }
            }
            UsageDelta compactUsage = lastStructuredUsage.modelCalls() + lastStructuredUsage.repairCalls() > 0
                    ? new UsageDelta(lastStructuredUsage.inputTokens(), lastStructuredUsage.outputTokens(),
                    lastStructuredUsage.totalTokens(), 0, 1, lastStructuredUsage.repairCalls(), 0,
                    lastStructuredUsage.activeMillis(), lastStructuredUsage.costMicrousd(), compactModel,
                    lastStructuredUsage.costKnown())
                    : UsagePricer.price(UsageDelta.compression(compactModel, Map.of(),
                    elapsedMillis(activeStarted)), spec.getModelPricing());
            writes.put("usageLedger", UsageLedger.empty().plus(compactUsage));
            return GraphNodeResult.next("next", writes);
        }

        private StructuredContextSummary summarize(
                List<Map<String, Object>> source, String prompt) throws Exception {
            Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                    "required", List.of("taskOverview", "currentState", "importantDiscoveries", "nextSteps", "contextToPreserve"),
                    "properties", Map.of(
                            "taskOverview", Map.of("type", "string"),
                            "currentState", Map.of("type", "string"),
                            "importantDiscoveries", Map.of("type", "array", "items", Map.of("type", "string")),
                            "nextSteps", Map.of("type", "array", "items", Map.of("type", "string")),
                            "contextToPreserve", Map.of("type", "array", "items", Map.of("type", "string"))));
            var result = new StructuredOutputService(provider, spec.getModelPricing()).execute(
                    List.of(Map.of("role", "system", "content", prompt)),
                    clean(spec.getCompactModel(), clean(spec.getModel(), provider.getDefaultModel())),
                    new StructuredRequest<>("submit_context_summary", "Submit the compact context summary", schema,
                            StructuredContextSummary.class, Objects::nonNull, 1), false, true);
            if (!result.valid()) throw new IllegalStateException("compact model returned invalid summary: " + result.error());
            lastStructuredUsage = result.usage();
            return result.value();
        }

        private GraphNodeResult model(GraphExecutionState state, Map<String, Object> ignored) throws Exception {
            int iteration = number(state.channels().get("iterations")) + 1;
            List<Map<String, Object>> messages = messages(state);
            UsageLedger ledger = UsageLedger.from(state.channels().get("usageLedger"));
            BudgetSnapshot budget = BudgetSnapshot.evaluate(spec.getBudgetPolicy(), ledger, false);
            boolean finalizing = budget.exhausted();
            String reservationId = runId + ":model:" + state.superstep() + ":" + iteration;
            long inputEstimate = Math.max(1, json(messages).length() / 4L);
            long outputEstimate = finalizing ? spec.getBudgetPolicy().finalizationTokens() : 1024L;
            UsageDelta estimated = UsagePricer.price(new UsageDelta(inputEstimate, outputEstimate,
                    inputEstimate + outputEstimate, 1, 0, 0, 0, 0, 0,
                    clean(spec.getModel(), provider.getDefaultModel()), false), spec.getModelPricing());
            List<BudgetReservation> reservations;
            try {
                reservations = reserveBudget(reservationId, estimated, 0, finalizing);
            } catch (BudgetCoordinator.BudgetExhaustedException exhausted) {
                finalizing = true;
                budget = new BudgetSnapshot(spec.getBudgetPolicy(), ledger, true, exhausted.reason(),
                        budget.remainingTokens(), budget.remainingCostMicrousd(), budget.remainingActiveMillis(),
                        budget.remainingToolCalls(), true);
                try {
                    UsageDelta finalEstimate = UsagePricer.price(new UsageDelta(inputEstimate,
                            spec.getBudgetPolicy().finalizationTokens(),
                            inputEstimate + spec.getBudgetPolicy().finalizationTokens(), 1, 0, 0, 0,
                            0, 0, clean(spec.getModel(), provider.getDefaultModel()), false), spec.getModelPricing());
                    reservations = reserveBudget(reservationId + ":final", finalEstimate, 0, true);
                } catch (BudgetCoordinator.BudgetExhaustedException noFinalizationBudget) {
                    return GraphNodeResult.next("terminal", Map.of("stopReason", "budget_exhausted",
                            "finalContent", deterministicBudgetSummary(budget), "budgetState", budget));
                }
            }
            Map<String, Object> hints = runtimeHints(state, budget);
            List<Map<String, Object>> modelMessages = new ArrayList<>(messages);
            modelMessages.add(Map.of("role", "system", "name", "ricbot_runtime",
                    "content", runtimeHintText(hints, finalizing)));
            AgentHookContext context = hookContext(messages, iteration);
            LLMResponse response;
            long activeStarted = System.nanoTime();
            String messageId = runId + ":assistant:" + iteration + ":" + UUID.randomUUID();
            emit(new AgentEvent.ModelCall(meta(), "started", spec.getModel(), Map.of()));
            emit(new AgentEvent.MessageStart(meta(), messageId), context);
            try {
                AgentMiddleware.MiddlewareContext runtime = middlewareContext(state);
                List<Map<String, Object>> definitions = middleware.tools(runtime,
                        finalizing ? List.of() : tools.getDefinitions());
                response = middleware.model(new AgentMiddleware.ModelCallContext(runtime, modelMessages,
                        definitions, spec.getModel(), finalizing), request -> {
                    if (spec.getHook() != null && spec.getHook().wantsStreaming()) {
                        return LLMFailureException.requireSuccess(provider.chatStream(request.messages(),
                                request.tools(), request.model(), null, null, null, null, delta -> {
                                    emit(new AgentEvent.MessageDelta(meta(), messageId, delta), context);
                                }, end -> {
                                    emit(new AgentEvent.MessageEnd(meta(), messageId,
                                            clean(end.getFinishReason(), end.hasToolCalls() ? "tool_calls" : "stop")), context);
                                }));
                    }
                    return LLMFailureException.requireSuccess(provider.chat(request.messages(), request.tools(),
                            request.model(), null, null, null, null));
                });
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
            if (spec.getHook() == null || !spec.getHook().wantsStreaming()) {
                emit(new AgentEvent.MessageDelta(meta(), messageId,
                        response.getContent() != null ? response.getContent() : ""), context);
                emit(new AgentEvent.MessageEnd(meta(), messageId, clean(response.getFinishReason(), "stop")), context);
            }
            emit(new AgentEvent.ModelCall(meta(), "completed", spec.getModel(),
                    response.getUsage() != null ? new LinkedHashMap<>(response.getUsage()) : Map.of()));
            invokeHook(() -> spec.getHook().afterIteration(context));
            Map<String, Object> assistant = assistant(response);
            List<Map<String, Object>> nextMessages = append(messages, assistant);
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("messages", nextMessages);
            writes.put("modelResponse", modelResponse(response));
            writes.put("pendingToolCalls", response.getToolCalls().stream().map(ToolCallRequest::toOpenAIToolCall).toList());
            writes.put("stopReason", clean(response.getFinishReason(), "stop"));
            writes.put("iterations", iteration);
            UsageDelta usage = UsagePricer.price(UsageDelta.model(clean(spec.getModel(), provider.getDefaultModel()),
                    response.getUsage(), elapsedMillis(activeStarted)), spec.getModelPricing());
            reservations.forEach(reservation -> budgetCoordinator.settle(reservation, usage));
            writes.put("usageLedger", UsageLedger.empty().plus(usage));
            UsageLedger nextLedger = ledger.plus(usage);
            BudgetSnapshot nextBudget = BudgetSnapshot.evaluate(spec.getBudgetPolicy(), nextLedger, finalizing);
            writes.put("budgetState", nextBudget);
            writes.put("runtimeHints", hints);
            writes.put("toolExposure", exposureMap());
            graphStore.append(runId, state.superstep(), GraphRuntimeEventType.USAGE_RECORDED,
                    usage.toMap(), "usage:model:" + state.superstep());
            if (nextBudget.exhausted()) graphStore.append(runId, state.superstep(), GraphRuntimeEventType.BUDGET_EXHAUSTED,
                    Map.of("reason", nextBudget.reason(), "finalizing", finalizing), "budget-exhausted:" + state.superstep());
            graphStore.append(runId, state.superstep(), GraphRuntimeEventType.RUNTIME_HINT_UPDATED,
                    hints, "runtime-hints:" + state.superstep());
            if (finalizing && response.hasToolCalls()) {
                writes.put("pendingToolCalls", List.of());
                writes.put("stopReason", "budget_exhausted");
                writes.put("finalContent", deterministicBudgetSummary(nextBudget));
                return GraphNodeResult.next("terminal", writes);
            }
            if (!response.hasToolCalls()) {
                String content = new AgentEventReconstructor().reconstruct(List.copyOf(agentEvents), messageId);
                if (spec.getHook() != null) {
                    String finalized = spec.getHook().finalizeContent(context, content);
                    if (!Objects.equals(finalized, content)) {
                        String finalMessageId = messageId + ":ui-final";
                        emit(new AgentEvent.MessageStart(meta(), finalMessageId));
                        emit(new AgentEvent.MessageDelta(meta(), finalMessageId, finalized));
                        emit(new AgentEvent.MessageEnd(meta(), finalMessageId, "stop"));
                        content = new AgentEventReconstructor().reconstruct(List.copyOf(agentEvents), finalMessageId);
                    }
                }
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
            newArtifactRefs.clear();
            nodeToolUsage = UsageLedger.empty();
            AgentHookContext context = hookContext(nextMessages, number(state.channels().get("iterations")));
            invokeHook(() -> spec.getHook().beforeExecuteTools(context));
            for (StoredCall call : calls) {
                BudgetSnapshot currentBudget = BudgetSnapshot.evaluate(spec.getBudgetPolicy(),
                        UsageLedger.from(state.channels().get("usageLedger")).plus(nodeToolUsage), false);
                if (currentBudget.exhausted() || currentBudget.remainingToolCalls() == 0) {
                    addToolResult(call, "Tool execution disabled because the Run budget is exhausted.", false,
                            nextMessages, results);
                    continue;
                }
                String toolReservationId = runId + ":tool:" + state.superstep() + ":" + call.id();
                try {
                    List<BudgetReservation> toolReservation = reserveBudget(toolReservationId,
                            new UsageDelta(0, 0, 0, 0, 0, 0, 1, 0, 0, "", true), 1, false);
                    toolReservations.put(call.id(), toolReservation);
                    toolStartedAt.put(call.id(), System.nanoTime());
                } catch (BudgetCoordinator.BudgetExhaustedException exhausted) {
                    addToolResult(call, "Tool execution disabled because the Run budget is exhausted ("
                            + exhausted.reason() + ").", false, nextMessages, results);
                    continue;
                }
                if (SideEffectApplicationService.RETRY_ACTION.equals(call.name())) {
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
                emit(new AgentEvent.ToolCall(meta(), "started", call.id(), call.name(), true));
                try {
                    SideEffectOutcome outcome = (SideEffectOutcome) middleware.tool(
                            new AgentMiddleware.ToolCallContext(middlewareContext(state), call.id(), call.name(), arguments),
                            request -> effects.execute(tools, effectIdentity(state, request.callId()), key,
                                    request.tool(), request.arguments(), policy, AgentGraphFactory::successful,
                                    ignoredResult -> false));
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
                }
                addToolResult(call, value, ok, nextMessages, results);
                emit(new AgentEvent.ToolCall(meta(), "completed", call.id(), call.name(), ok));
            }
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("messages", List.copyOf(nextMessages));
            writes.put("toolBatch", Map.of("results", List.copyOf(results)));
            writes.put("approvalRequestIds", List.copyOf(approvalIds));
            writes.put("usageLedger", nodeToolUsage);
            writes.put("budgetState", BudgetSnapshot.evaluate(spec.getBudgetPolicy(),
                    UsageLedger.from(state.channels().get("usageLedger")).plus(nodeToolUsage), false));
            if (!newArtifactRefs.isEmpty()) {
                writes.put("artifactRefs", newArtifactRefs.stream().map(this::artifactMap).toList());
            }
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
            approvals.completeClaim(requestId);
            addToolResult(call, outcome.result(), successful(outcome.result()), messages, results);
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
            int iterations = number(state.channels().get("iterations"));
            if (iterations >= Math.max(1, spec.getMaxIterations())) return GraphNodeResult.next("terminal", Map.of(
                    "messages", List.copyOf(next), "stopReason", "max_iterations",
                    "finalContent", clean(spec.getMaxIterationsMessage(), "maximum iterations reached")));
            return GraphNodeResult.next("next", Map.of("messages", List.copyOf(next)));
        }

        private void addToolResult(StoredCall call, Object value, boolean ok,
                                   List<Map<String, Object>> messages, List<Map<String, Object>> results) {
            List<BudgetReservation> reservations = toolReservations.remove(call.id());
            Long started = toolStartedAt.remove(call.id());
            if (reservations != null) {
                UsageDelta usage = UsageDelta.tool(started != null ? elapsedMillis(started) : 0);
                reservations.forEach(reservation -> budgetCoordinator.settle(reservation, usage));
                nodeToolUsage = nodeToolUsage.plus(usage);
            }
            Object bounded = offload(call, value);
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

        private Object offload(StoredCall call, Object value) {
            String text = value instanceof String string ? string : json(value);
            int limit = spec.getMaxToolResultChars() > 0 ? spec.getMaxToolResultChars() : 16_000;
            if (!spec.isContextOffloadEnabled() || text.length() <= limit) return value;
            try {
                ArtifactRef ref = artifacts.writeText(text, "tool:" + call.name() + ":" + call.id(),
                        spec.getOffloadPreviewChars());
                newArtifactRefs.add(ref);
                graphStore.append(runId, 0, GraphRuntimeEventType.ARTIFACT_OFFLOADED,
                        MAPPER.convertValue(ref, new TypeReference<>() { }), "artifact:" + ref.artifactId());
                return Map.of("offloaded", true, "artifact", ref, "preview", ref.summary(),
                        "instructions", "Use artifact_read or artifact_grep with uri " + ref.uri()
                                + " to recover the complete result. The preview is not the complete content.");
            } catch (Exception failure) {
                throw new IllegalStateException("cannot offload oversized tool result", failure);
            }
        }

        /**
         * Checkpoint channels are intentionally limited to JSON-stable primitives. In
         * particular, Jackson's untyped representation of Instant is provider/config
         * dependent and would otherwise change the checkpoint digest after a restart.
         */
        private Map<String, Object> artifactMap(ArtifactRef ref) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("artifactId", ref.artifactId());
            value.put("uri", ref.uri());
            value.put("path", ref.path());
            value.put("sha256", ref.sha256());
            value.put("byteSize", ref.byteSize());
            value.put("charCount", ref.charCount());
            value.put("summary", ref.summary());
            value.put("source", ref.source());
            value.put("rootRunId", ref.rootRunId());
            value.put("runId", ref.runId());
            value.put("taskId", ref.taskId());
            value.put("mediaType", ref.mediaType());
            value.put("createdAt", ref.createdAt() != null ? ref.createdAt().toString() : "");
            return Map.copyOf(value);
        }

        private Map<String, Object> runtimeHints(GraphExecutionState state, BudgetSnapshot budget) {
            java.time.ZoneId zone;
            try { zone = java.time.ZoneId.of(clean(spec.getTimezone(), "UTC")); }
            catch (Exception ignored) { zone = java.time.ZoneOffset.UTC; }
            Instant minute = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 60) * 60);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("time", Map.of("time", minute.toString(), "timezone", zone.getId()));
            result.put("budget", MAPPER.convertValue(budget, new TypeReference<Map<String, Object>>() { }));
            result.put("context", Map.of("utilization", numberDouble(state.channels().get("contextUtilization")),
                    "artifacts", collectionSize(state.channels().get("artifactRefs")),
                    "compactions", state.channels().get("contextCompactedAt") != null
                            && !string(state.channels().get("contextCompactedAt")).isBlank() ? 1 : 0));
            result.put("tasks", Map.of("pending", 0, "running", 0, "completed", 0));
            result.put("workspace", Map.of("path", spec.getWorkspace() != null ? spec.getWorkspace().toString() : "",
                    "mode", string(spec.getMetadata() != null ? spec.getMetadata().get("mode") : "agent"),
                    "state", "active"));
            return Map.copyOf(result);
        }

        private String runtimeHintText(Map<String, Object> hints, boolean finalizing) {
            String suffix = finalizing
                    ? "\nThe Run budget is exhausted. Do not call tools. Give a concise final summary of progress, evidence, and unfinished work."
                    : "";
            return "Runtime hints (dynamic; not identity instructions): " + json(hints) + suffix;
        }

        private Map<String, Object> exposureMap() {
            List<String> allowed = tools.exposure().allowedGroups().stream().map(Enum::name).sorted().toList();
            List<String> active = tools.exposure().activeGroups().stream().map(Enum::name).sorted().toList();
            return Map.of("allowedGroups", allowed, "activeGroups", active,
                    "visibleTools", tools.visibleToolNames());
        }

        private AgentMiddleware.MiddlewareContext middlewareContext(GraphExecutionState state) {
            middleware.validateStateVersions(state.channels().get("middlewareState"));
            Map<String, Object> middlewareState = state.channels().get("middlewareState") instanceof Map<?, ?> raw
                    ? MAPPER.convertValue(raw, new TypeReference<>() { }) : Map.of();
            String taskId = spec.getMetadata() != null ? string(spec.getMetadata().get("taskId")) : "";
            return new AgentMiddleware.MiddlewareContext(runId, sessionKey(), taskId, middlewareState);
        }

        private record NamedMiddleware(String id) implements AgentMiddleware { }

        private AgentEvent.EventMeta meta() {
            String taskId = spec.getMetadata() != null ? string(spec.getMetadata().get("taskId")) : "";
            return new AgentEvent.EventMeta(UUID.randomUUID().toString(), agentEventSequence.incrementAndGet(), runId,
                    sessionKey(), taskId, "", runId, Instant.now());
        }

        private void emit(AgentEvent event) {
            agentEvents.add(event);
            graphStore.append(runId, 0, GraphRuntimeEventType.AGENT_EVENT_EMITTED,
                    MAPPER.convertValue(event, new TypeReference<>() { }), "agent-event:" + event.meta().eventId());
        }

        private void emit(AgentEvent event, AgentHookContext context) {
            emit(event);
            if (spec.getHook() != null && spec.getHook().wantsStreaming()) {
                try { invokeHook(() -> spec.getHook().onEvent(context, event)); }
                catch (Exception failure) { throw new IllegalStateException("agent event adapter failed", failure); }
            }
        }

        private String deterministicBudgetSummary(BudgetSnapshot snapshot) {
            return "Run budget exhausted (" + snapshot.reason() + "). Usage: "
                    + json(snapshot.usage()) + ". Tools are disabled; start a new Run with a larger explicit budget to continue.";
        }

        private BudgetPolicy withoutFinalizationReserve(BudgetPolicy policy) {
            BudgetPolicy value = policy != null ? policy : BudgetPolicy.unlimited();
            return new BudgetPolicy(value.maxTotalTokens(), value.maxCostMicrousd(),
                    value.maxActiveSeconds(), value.maxToolCalls(), 0, value.parentRunId());
        }

        private List<BudgetReservation> reserveBudget(String reservationId, UsageDelta estimate,
                                                      long toolCalls, boolean finalization) {
            List<BudgetReservation> reservations = new ArrayList<>();
            BudgetPolicy rootPolicy = spec.getRootBudgetPolicy() != null
                    ? spec.getRootBudgetPolicy() : spec.getBudgetPolicy();
            BudgetPolicy effectiveRoot = finalization ? withoutFinalizationReserve(rootPolicy) : rootPolicy;
            BudgetReservation rootReservation = budgetCoordinator.reserve(rootRunId, runId, taskId,
                    reservationId + ":root", effectiveRoot, estimate.totalTokens(), estimate.costMicrousd(),
                    toolCalls, 0);
            reservations.add(rootReservation);
            if (!rootRunId.equals(runId)) {
                try {
                    BudgetPolicy local = finalization ? withoutFinalizationReserve(spec.getBudgetPolicy())
                            : spec.getBudgetPolicy();
                    reservations.add(budgetCoordinator.reserve(runId, runId, taskId,
                            reservationId + ":worker", local, estimate.totalTokens(), estimate.costMicrousd(),
                            toolCalls, 0));
                } catch (RuntimeException rejected) {
                    budgetCoordinator.settle(rootReservation, new UsageDelta(0, 0, 0, 0, 0, 0,
                            0, 0, 0, estimate.model(), estimate.costKnown()));
                    throw rejected;
                }
            }
            return List.copyOf(reservations);
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
                    .setUsageLedger(UsageLedger.from(state.channels().get("usageLedger")));
            result.setToolsUsed(List.copyOf(toolsUsed));
            return result.setEvents(List.copyOf(agentEvents)).setToolEvents(List.copyOf(toolEvents))
                    .setError(string(state.channels().get("error"))).setRunEvents(runEvents(events));
        }

        private AgentRunResult failedResult(Instant started, Exception failure, List<GraphRuntimeEvent> events) {
            org.slf4j.LoggerFactory.getLogger(AgentGraphFactory.class).error("agent graph run failed: {}", runId, failure);
            AgentRunResult result = new AgentRunResult().setRunId(runId).setStartedAt(started.toString())
                    .setEndedAt(Instant.now().toString()).setFinalContent(spec.getErrorMessage())
                    .setStopReason("error").setError(failure.getMessage()).setMessages(copyMessages(spec.getInitialMessages()));
            result.setToolsUsed(List.copyOf(toolsUsed));
            return result.setEvents(List.copyOf(agentEvents)).setToolEvents(List.copyOf(toolEvents)).setRunEvents(runEvents(events));
        }

        private AgentHookContext hookContext(List<Map<String, Object>> messages, int iteration) {
            return new AgentHookContext().setMessages(messages).setIteration(iteration).setSessionKey(spec.getSessionKey());
        }
        private void invokeHook(Checked action) throws Exception {
            if (spec.getHook() == null) return;
            action.run();
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
    private static double numberDouble(Object value) { return value instanceof Number number ? number.doubleValue() : 0d; }
    private static int collectionSize(Object value) { return value instanceof Collection<?> collection ? collection.size() : 0; }
    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }
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
