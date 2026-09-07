package ricbot.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.agent.budget.BudgetSnapshot;
import ricbot.domain.agent.context.ConservativeContextTokenAccountant;
import ricbot.domain.agent.context.ModelInputCompiler;
import ricbot.domain.agent.context.ModelInputPlan;
import ricbot.domain.agent.usage.UsageDelta;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.runtime.*;
import ricbot.domain.runtime.dto.RuntimeDigest;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMFailureKind;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.*;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Shared narrow collaborators and deterministic helpers used by phase-owned behavior. */
final class AgentPhaseSupport implements CancellationPort {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static final String SPAWN_CHILD_RUNS = "spawn_child_runs";
    static final String MODEL_RETRY = "modelRetry";
    private final ToolRegistry tools;
    private final ToolDispatcher dispatcher;
    private final ModelInvocationRuntime models;
    private final DurableRuntimeStore store;
    private final TranscriptPort transcripts;
    private final LLMProvider provider;
    private final String defaultModel;
    private final Path workspace;
    private final Clock clock;
    private final String owner;
    private final ApprovalService approvals;
    private final ChangeSetService changes;
    private final ModelInputCompiler inputCompiler;
    private final CrashInjector crashes;
    private final RunExecutionRegistry runs = new RunExecutionRegistry();

    AgentPhaseSupport(DurableRuntimeStore store, TranscriptPort transcripts,
                              LLMProvider provider, ToolRegistry tools,
                              ApprovalService approvals, String defaultModel, Path workspace,
                              Clock clock, String owner) {
        this(store, transcripts, provider, tools, approvals, defaultModel, workspace, clock, owner,
                CrashInjector.NONE);
    }

    AgentPhaseSupport(DurableRuntimeStore store, TranscriptPort transcripts,
                              LLMProvider provider, ToolRegistry tools,
                              ApprovalService approvals, String defaultModel, Path workspace,
                              Clock clock, String owner, CrashInjector crashes) {
        this.store = Objects.requireNonNull(store, "store");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.dispatcher = new ToolDispatcher(tools, null);
        this.defaultModel = defaultModel != null && !defaultModel.isBlank() ? defaultModel : provider.getDefaultModel();
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
        this.changes = new ChangeSetService(this.workspace);
        this.inputCompiler = new ModelInputCompiler(new ConservativeContextTokenAccountant());
        this.models = new ModelInvocationRuntime(store, new ProviderAdapter(), clock, this.crashes);
    }

    public void attach(String runId, AgentRunSpec spec) {
        runs.attach(runId, spec);
    }

    public RunExecutionRegistry.InvocationObservation observation(String runId) {
        return runs.observation(runId);
    }

    public void detach(String runId) {
        models.detach(runId);
        runs.detach(runId);
    }

    @Override public void cancel(String runId) {
        runs.cancel(runId);
        models.cancel(runId);
    }

    void appendTranscript(String runId, String key, Map<String, Object> message) {
        transcripts.append(runId, key, message);
    }

    long transcriptSize(String runId) { return transcripts.size(runId); }

    void recordApprovalDecision(String requestId, boolean approved) {
        if (approved) approvals.approve(requestId); else approvals.reject(requestId);
    }

    Instant now() { return clock.instant(); }

    ModelInvocation invokeModel(ModelInvocationRuntime.ModelCall call) { return models.invoke(call); }

    ToolDispatcher dispatcher() { return dispatcher; }
    ApprovalService approvals() { return approvals; }
    DurableRuntimeStore store() { return store; }
    Clock clock() { return clock; }
    String owner() { return owner; }
    CrashInjector crashes() { return crashes; }
    ChangeSetService changes() { return changes; }
    Path workspace() { return workspace; }

    PhaseResult finishTool(PhaseContext context, AgentRunSpec invocation, UsageLedger ledger,
                                   List<Map<String, Object>> remaining, String callId, String name,
                                   ToolResult result) {
        String output = preview(result);
        transcripts.append(context.state().spec().runId(), context.activationId() + ":tool:" + callId,
                toolMessage(callId, name, output));
        boolean ok = !(result instanceof ToolResult.Failure);
        observeTool(context, callId, name, ok, output);
        notifyToolFinish(invocation, name, callId, ok, output);
        String approvalRequestId = String.valueOf(objectMap(context.state().channels()
                .get("toolApprovalRequests")).getOrDefault(callId, ""));
        ApprovalRequest approval = approvalRequestId.isBlank() ? null : approvals.find(approvalRequestId);
        if (approval != null && approval.status() == ApprovalRequest.ApprovalStatus.CLAIMED) {
            approvals.completeClaim(approvalRequestId);
        }
        String reservationId = toolBudgetId(context.state().spec().runId(), callId);
        store.settleToolCall(reservationId, clock.instant());
        long activeMillis = runs.consumeToolActiveMillis(reservationId);
        List<ChannelWrite> writes = new ArrayList<>(transcriptWrites(context.state()));
        writes.add(ChannelWrite.set("pendingToolCalls", remaining));
        writes.add(ChannelWrite.set("usageLedger", ledger.plus(UsageDelta.tool(activeMillis))));
        writes.add(new ChannelWrite("pendingEffectId", ChannelWrite.Operation.REMOVE, null));
        writes.add(new ChannelWrite("pendingExternalCall", ChannelWrite.Operation.REMOVE, null));
        writes.add(ChannelWrite.set("continueToolBatch", !remaining.isEmpty()));
        writes.addAll(mutationWrites(context.state(), result.mutations()));
        return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(
                remaining.isEmpty() ? RuntimePhase.CONTEXT : RuntimePhase.MODEL)));
    }

    AgentRunSpec invocationSpec(String runId) { return runs.spec(runId); }

    static boolean changeActionRun(RunState state) {
        return "change-action".equals(state.spec().metadata().get("runtimeOperation"));
    }

    static PendingChangeAction changeAction(RunState state) {
        Object value = state.spec().metadata().get("changeAction");
        if (value instanceof PendingChangeAction action) return action;
        if (value == null) throw new IllegalStateException("change action metadata is missing");
        return MAPPER.convertValue(value, PendingChangeAction.class);
    }

    ToolExecutionContext toolContext(PhaseContext context) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = runs.cancellationFlag(
                context.state().spec().runId());
        if (context.state().cancelRequested()) cancelled.set(true);
        return new ToolExecutionContext(context.state().spec().runId(),
                String.valueOf(context.state().spec().metadata().getOrDefault("sessionId", "")), "",
                context.activationId(), workspace.toString(), workspace, "agent", null,
                Map.of("maxParallelReadCalls", boundedPolicyInt(context.state(), "maxParallelReadCalls", 4, 1, 64),
                        "requireReadReceipt", Boolean.parseBoolean(String.valueOf(
                                context.state().spec().metadata().getOrDefault("requireReadReceipt", true))),
                        "externalActionsEnabled", Boolean.parseBoolean(String.valueOf(
                                context.state().spec().metadata().getOrDefault("externalActionsEnabled", false)))),
                objectMap(context.state().channels().get("fileReadReceipts")),
                cancelled);
    }

    EffectIntent toolEffectIntent(PhaseContext context, ToolDispatcher.Prepared prepared,
                                          String callId, String name) {
        return new EffectIntent("effect:" + context.state().spec().runId() + ":" + callId,
                context.state().spec().runId(),
                context.activationId(), name, prepared.invocationDigest(),
                context.state().spec().runId() + ":" + callId, prepared.resourceKeys(),
                Map.of("decision", prepared.authorization().decision().name(),
                        "evidence", prepared.authorization().evidence()), "tool-reconcile");
    }

    Set<ToolGroup> activeToolGroups(RunState state) {
        Object value = state.channels().get("activeToolGroups");
        if (!(value instanceof Collection<?> groups)) return tools.exposure().activeGroups();
        Set<ToolGroup> active = new LinkedHashSet<>();
        for (Object group : groups) active.add(ToolGroup.parse(String.valueOf(group)));
        active.add(ToolGroup.BASIC);
        if (!tools.exposure().allowedGroups().containsAll(active)) {
            throw new SecurityException("persisted tool exposure exceeds the configured policy");
        }
        return Set.copyOf(active);
    }

    private List<Map<String, Object>> toolDefinitions(RunState state) {
        List<Map<String, Object>> definitions = new ArrayList<>(tools.getDefinitions(activeToolGroups(state)));
        if (delegationAllowed(state)) definitions.add(spawnChildRunsDefinition());
        return List.copyOf(definitions);
    }

    static PhaseResult routeToolCall(RunState state, Map<String, Object> call) {
        if (SPAWN_CHILD_RUNS.equals(String.valueOf(call.getOrDefault("name", "")))
                && delegationAllowed(state)) {
            return new PhaseResult(List.of(ChannelWrite.set("pendingRuntimeControl", call)),
                    List.of(new RuntimeCommand.Transition(RuntimePhase.DELEGATE)));
        }
        return PhaseResult.route(RuntimePhase.TOOLS);
    }

    PhaseResult scheduleModelRetry(ModelInvocation invocation, boolean possibleDuplicateCharge,
                                           long providerDelayMillis) {
        int nextAttempt = invocation.attempt() + 1;
        long delayMillis = Math.max(providerDelayMillis, modelBackoffMillis(invocation.attempt()));
        Instant dueAt = clock.instant().plusMillis(delayMillis);
        String base = invocation.invocationId().replaceFirst(":attempt-[0-9]+$", "");
        Map<String, Object> plan = Map.of("invocationBase", base, "attempt", nextAttempt,
                "possibleDuplicateCharge", possibleDuplicateCharge,
                "previousInvocationId", invocation.invocationId());
        return new PhaseResult(List.of(ChannelWrite.set(MODEL_RETRY, plan)),
                List.of(new RuntimeCommand.Suspend(new WaitReason.RetryWait(
                        "model-retry:" + base, dueAt, nextAttempt))));
    }

    PhaseResult scheduleEffectRetry(PhaseContext context, String effectId) {
        Map<String, Object> attempts = objectMap(context.state().channels().get("effectRetryAttempts"));
        int attempt = number(attempts.get(effectId)) + 1;
        Instant dueAt = clock.instant().plusMillis(effectBackoffMillis(attempt));
        return new PhaseResult(List.of(ChannelWrite.set("pendingEffectId", effectId),
                        ChannelWrite.set("continueToolBatch", true),
                        new ChannelWrite("effectRetryAttempts", ChannelWrite.Operation.MERGE,
                                Map.of(effectId, attempt))),
                List.of(new RuntimeCommand.Suspend(new WaitReason.RetryWait(
                        "effect-retry:" + effectId, dueAt, attempt))));
    }

    private static long modelBackoffMillis(int completedAttempt) {
        int exponent = Math.min(5, Math.max(0, completedAttempt - 1));
        return Math.min(30_000L, 1_000L << exponent);
    }

    private static long effectBackoffMillis(int attempt) {
        int exponent = Math.min(5, Math.max(0, attempt - 1));
        return Math.min(5_000L, 250L << exponent);
    }

    static int maxModelAttempts(RunState state) {
        Object value = state.spec().metadata().get("maxModelAttempts");
        return value instanceof Number number ? Math.max(1, Math.min(10, number.intValue())) : 3;
    }

    static boolean delegationAllowed(RunState state) {
        Object allowed = state.spec().metadata().get("allowChildRuns");
        if (allowed != null && !Boolean.parseBoolean(String.valueOf(allowed))) return false;
        int depth = number(state.spec().metadata().get("delegationDepth"));
        int maxDepth = boundedPolicyInt(state, "maxChildDepth", 4, 0, 16);
        int maxChildren = boundedPolicyInt(state, "maxChildRuns", 8, 0, 64);
        return depth < maxDepth && state.childRunIds().size() < maxChildren;
    }

    private static int boundedPolicyInt(RunState state, String key, int fallback, int minimum, int maximum) {
        Object value = state.spec().metadata().get(key);
        int parsed = value instanceof Number number ? number.intValue() : fallback;
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static Map<String, Object> spawnChildRunsDefinition() {
        return Map.of("type", "function", "function", Map.of(
                "name", SPAWN_CHILD_RUNS,
                "description", "Create policy-bounded durable Child Runs. Dependencies may reference earlier child keys.",
                "parameters", spawnChildRunsParameters()));
    }

    static Map<String, Object> spawnChildRunsParameters() {
        Map<String, Object> child = Map.of(
                "type", "object",
                "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Unique key for dependency references"),
                        "goal", Map.of("type", "string", "description", "Bounded objective for the child Run"),
                        "dependsOn", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Keys of earlier children that must finish first")),
                "required", List.of("goal"),
                "additionalProperties", false);
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "children", Map.of("type", "array", "minItems", 1, "items", child),
                        "waitForAll", Map.of("type", "boolean",
                                "description", "Wait for every child before continuing; defaults to true")),
                "required", List.of("children"),
                "additionalProperties", false);
        return parameters;
    }

    static ChildBatch childBatch(RunState parent, String callId, Map<String, Object> arguments) {
        List<Map<String, Object>> requested = objectList(arguments.get("children"));
        if (requested.isEmpty()) throw new IllegalArgumentException("children must contain at least one child Run");
        int maximum = boundedPolicyInt(parent, "maxChildRuns", 8, 0, 64);
        if (parent.childRunIds().size() + requested.size() > maximum) {
            throw new IllegalArgumentException("child Run policy limit exceeded: " + maximum);
        }
        int depth = number(parent.spec().metadata().get("delegationDepth"));
        int maximumDepth = boundedPolicyInt(parent, "maxChildDepth", 4, 0, 16);
        if (depth >= maximumDepth) throw new IllegalArgumentException("child Run depth policy limit exceeded");
        Map<String, String> idsByKey = new LinkedHashMap<>();
        List<RunSpec> children = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            Map<String, Object> child = requested.get(index);
            String goal = String.valueOf(child.getOrDefault("goal", "")).trim();
            if (goal.isBlank()) throw new IllegalArgumentException("child goal is required at index " + index);
            String key = String.valueOf(child.getOrDefault("key", "child-" + (index + 1))).trim();
            if (key.isBlank() || idsByKey.containsKey(key)) {
                throw new IllegalArgumentException("child keys must be non-blank and unique: " + key);
            }
            String childId = deterministicChildRunId(parent.spec().runId(), callId, index);
            List<String> dependencies = new ArrayList<>();
            Object rawDependencies = child.get("dependsOn");
            if (rawDependencies instanceof Collection<?> values) {
                for (Object value : values) {
                    String dependencyKey = String.valueOf(value).trim();
                    String dependencyId = idsByKey.get(dependencyKey);
                    if (dependencyId == null) {
                        throw new IllegalArgumentException("dependency must reference an earlier child key: "
                                + dependencyKey);
                    }
                    dependencies.add(dependencyId);
                }
            }
            Map<String, Object> metadata = new LinkedHashMap<>(parent.spec().metadata());
            metadata.put("delegatedByRunId", parent.spec().runId());
            metadata.put("delegationDepth", depth + 1);
            metadata.put("runtimeControlCallId", callId);
            RunSpec spec = new RunSpec(childId, parent.spec().runId(), parent.spec().rootRunId(), "",
                    dependencies, goal, parent.spec().executionPolicyRef(), parent.spec().maxSupersteps(), metadata);
            idsByKey.put(key, childId);
            children.add(spec);
        }
        boolean waitForAll = !arguments.containsKey("waitForAll")
                || Boolean.parseBoolean(String.valueOf(arguments.get("waitForAll")));
        return new ChildBatch(List.copyOf(children), waitForAll);
    }

    private static String deterministicChildRunId(String parentRunId, String callId, int index) {
        UUID value = UUID.nameUUIDFromBytes((parentRunId + "\n" + callId + "\n" + index)
                .getBytes(StandardCharsets.UTF_8));
        return "run-" + value;
    }

    private static List<ChannelWrite> mutationWrites(RunState state, List<ToolStateMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) return List.of();
        List<ChannelWrite> writes = new ArrayList<>();
        Map<String, Object> receipts = new LinkedHashMap<>(objectMap(state.channels().get("fileReadReceipts")));
        boolean receiptsChanged = false;
        for (ToolStateMutation mutation : mutations) {
            if (mutation instanceof ToolStateMutation.SetToolExposure exposure) {
                writes.add(ChannelWrite.set("activeToolGroups", exposure.activeGroups().stream()
                        .map(Enum::name).sorted().toList()));
            } else if (mutation instanceof ToolStateMutation.RecordFileReadReceipt receipt) {
                if (!receipt.key().isBlank()) {
                    receipts.put(receipt.key(), receipt.receipt());
                    receiptsChanged = true;
                }
            } else if (mutation instanceof ToolStateMutation.InvalidateFileReadReceipts invalidation) {
                invalidation.keys().forEach(receipts::remove);
                receiptsChanged = true;
            }
        }
        if (receiptsChanged) writes.add(ChannelWrite.set("fileReadReceipts", Map.copyOf(receipts)));
        return List.copyOf(writes);
    }

    static Map<String, Object> encodeToolResult(ToolResult result) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("resultDigest", RuntimeDigest.sha256(result));
        encoded.put("preview", preview(result));
        encoded.put("mutations", result.mutations().stream().map(AgentPhaseSupport::encodeMutation).toList());
        if (result instanceof ToolResult.Success success) {
            encoded.put("kind", "success");
            encoded.put("value", success.value() != null ? success.value() : "");
            encoded.put("metadata", success.metadata());
        } else if (result instanceof ToolResult.Failure failure) {
            encoded.put("kind", "failure");
            encoded.put("code", failure.code());
            encoded.put("message", failure.message());
            encoded.put("retryable", failure.retryable());
        } else if (result instanceof ToolResult.ExternalPending external) {
            encoded.put("kind", "external");
            encoded.put("actionId", external.actionId());
            encoded.put("invocationDigest", external.invocationDigest());
            encoded.put("request", external.request());
        }
        return Map.copyOf(encoded);
    }

    private static Map<String, Object> encodeMutation(ToolStateMutation mutation) {
        if (mutation instanceof ToolStateMutation.SetToolExposure exposure) {
            return Map.of("type", "toolExposure", "activeGroups",
                    exposure.activeGroups().stream().map(Enum::name).sorted().toList());
        }
        if (mutation instanceof ToolStateMutation.RecordFileReadReceipt receipt) {
            return Map.of("type", "recordReceipt", "key", receipt.key(), "receipt", receipt.receipt());
        }
        ToolStateMutation.InvalidateFileReadReceipts invalidation =
                (ToolStateMutation.InvalidateFileReadReceipts) mutation;
        return Map.of("type", "invalidateReceipts", "keys", invalidation.keys().stream().sorted().toList(),
                "reason", invalidation.reason());
    }

    static ToolResult decodeToolResult(Map<String, Object> evidence, String fallbackReference) {
        List<ToolStateMutation> mutations = new ArrayList<>();
        for (Map<String, Object> encoded : objectList(evidence.get("mutations"))) {
            String type = String.valueOf(encoded.getOrDefault("type", ""));
            if ("toolExposure".equals(type) && encoded.get("activeGroups") instanceof Collection<?> groups) {
                Set<ToolGroup> active = new LinkedHashSet<>();
                groups.stream().map(String::valueOf).map(ToolGroup::parse).forEach(active::add);
                mutations.add(new ToolStateMutation.SetToolExposure(active));
            } else if ("recordReceipt".equals(type)) {
                mutations.add(new ToolStateMutation.RecordFileReadReceipt(
                        String.valueOf(encoded.getOrDefault("key", "")), objectMap(encoded.get("receipt"))));
            } else if ("invalidateReceipts".equals(type) && encoded.get("keys") instanceof Collection<?> keys) {
                mutations.add(new ToolStateMutation.InvalidateFileReadReceipts(
                        keys.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()),
                        String.valueOf(encoded.getOrDefault("reason", ""))));
            }
        }
        String kind = String.valueOf(evidence.getOrDefault("kind", "success"));
        if ("failure".equals(kind)) {
            return new ToolResult.Failure(String.valueOf(evidence.getOrDefault("code", "TOOL_FAILED")),
                    String.valueOf(evidence.getOrDefault("message", "")),
                    Boolean.parseBoolean(String.valueOf(evidence.getOrDefault("retryable", false))), mutations);
        }
        return new ToolResult.Success(evidence.getOrDefault("value", fallbackReference),
                String.valueOf(evidence.getOrDefault("preview", fallbackReference)), mutations,
                objectMap(evidence.get("metadata")));
    }

    private ToolResult execute(ToolDispatcher.Prepared prepared, ToolExecutionContext context) {
        Thread thread = Thread.currentThread();
        runs.beginTool(context.runId(), thread);
        String reservationId = toolBudgetId(context.runId(), prepared.invocation().callId());
        Instant started = clock.instant();
        ActiveTimeReservation activeTime = ActiveTimeReservation.start(
                store, context.runId(), reservationId, clock);
        ToolResult result;
        try { result = dispatcher.execute(prepared, context, ToolChunkSink.discard()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("tool interrupted", interrupted); }
        catch (Exception failure) { throw new IllegalStateException(failure.getMessage(), failure); }
        finally {
            activeTime.close();
            runs.finishTool(context.runId(), thread, reservationId,
                    elapsedMillis(started, clock.instant()));
        }
        if (activeTime.failure() != null) throw activeTime.failure();
        return result;
    }

    ToolResult executePrepared(ToolDispatcher.Prepared prepared, ToolExecutionContext context) {
        return execute(prepared, context);
    }

    String modelName(RunState state) {
        return String.valueOf(state.spec().metadata().getOrDefault("model", defaultModel));
    }

    ModelInputPlan compileModelInput(RunState state) {
        List<Map<String, Object>> transcript = messages(state);
        int cursor = compactedCursor(state, transcript.size());
        int leadingSystem = leadingSystemMessages(transcript);
        List<Map<String, Object>> durableMessages = new ArrayList<>(transcript.size() - cursor + leadingSystem);
        durableMessages.addAll(transcript.subList(0, leadingSystem));
        durableMessages.addAll(transcript.subList(Math.max(cursor, leadingSystem), transcript.size()));
        List<Map<String, Object>> definitions = toolDefinitions(state);
        Set<String> basic = tools.toolNames().stream()
                .filter(name -> tools.groupFor(name) == ToolGroup.BASIC)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return inputCompiler.compile(durableMessages, state.channels().get("contextSummary"), definitions,
                basic, runtimeHints(state), outputReserveTokens(state), contextWindowTokens(state),
                modelName(state), false);
    }

    static Map<String, Object> planMetadata(ModelInputPlan plan) {
        List<Map<String, Object>> candidates = plan.candidates().stream().map(candidate -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", candidate.id());
            item.put("source", candidate.source());
            item.put("priority", candidate.priority());
            item.put("estimatedTokens", candidate.tokenCost());
            item.put("selected", candidate.selected());
            item.put("reason", candidate.reason());
            if (candidate.reference() != null) item.put("reference", Map.of(
                    "uri", candidate.reference().uri(), "summary", candidate.reference().summary(),
                    "recoveryTool", candidate.reference().recoveryTool(),
                    "ownerRunId", candidate.reference().ownerRunId()));
            return Map.copyOf(item);
        }).toList();
        return Map.of("requestDigest", plan.requestDigest(),
                "inputTokens", plan.tokens().inputTokens(),
                "outputReserveTokens", plan.tokens().outputReserveTokens(),
                "reservedTokens", plan.tokens().totalTokens(),
                "contextWindowTokens", plan.tokens().contextWindowTokens(),
                "utilization", plan.tokens().utilization(),
                "partitions", plan.tokens().partitions(),
                "toolExposureNarrowed", plan.toolExposureNarrowed(),
                "visibleTools", plan.tools().stream().map(AgentPhaseSupport::definitionName).toList(),
                "candidates", candidates);
    }

    private static String definitionName(Map<String, Object> definition) {
        Object function = definition.get("function");
        return function instanceof Map<?, ?> map
                ? String.valueOf(map.get("name") != null ? map.get("name") : "")
                : String.valueOf(definition.getOrDefault("name", ""));
    }

    private static Map<String, Object> runtimeHints(RunState state) {
        UsageLedger usage = UsageLedger.from(state.channels().get("usageLedger"));
        BudgetSnapshot budget = BudgetSnapshot.evaluate(budgetPolicy(state, "budgetPolicy"), usage, false);
        Map<String, Object> budgetHint = Map.of("remainingTokens", budget.remainingTokens(),
                "remainingCostMicrousd", budget.remainingCostMicrousd(),
                "remainingActiveMillis", budget.remainingActiveMillis(),
                "remainingToolCalls", budget.remainingToolCalls(),
                "exhausted", budget.exhausted(), "reason", budget.reason());
        return Map.of("budget", budgetHint,
                "context", Map.of("compactedThroughCursor", compactedCursor(state, Integer.MAX_VALUE),
                        "compactionCount", number(state.channels().get("compactionCount"))),
                "timezone", String.valueOf(state.spec().metadata().getOrDefault("timezone", "UTC")),
                "offload", objectMap(state.spec().metadata().get("contextOffload")));
    }

    boolean shouldCompact(RunState state, ModelInputPlan plan) {
        double trigger = ratio(state.spec().metadata().get("contextTriggerRatio"), 0.80d);
        return plan.tokens().utilization() >= trigger
                && canCompact(state);
    }

    private boolean canCompact(RunState state) {
        List<Map<String, Object>> transcript = messages(state);
        int start = Math.max(compactedCursor(state, transcript.size()), leadingSystemMessages(transcript));
        return transcript.size() - start > 2;
    }

    static int leadingSystemMessages(List<Map<String, Object>> transcript) {
        int count = 0;
        while (count < transcript.size()
                && "system".equals(String.valueOf(transcript.get(count).getOrDefault("role", "")))) count++;
        return count;
    }

    static int compactedCursor(RunState state, int transcriptSize) {
        int cursor = number(state.channels().get("compactedThroughCursor"));
        return Math.max(0, Math.min(transcriptSize, cursor));
    }

    static long contextWindowTokens(RunState state) {
        Object value = state.spec().metadata().get("contextWindowTokens");
        return value instanceof Number number ? Math.max(1L, number.longValue()) : 64_000L;
    }

    private static long outputReserveTokens(RunState state) {
        Object value = state.spec().metadata().get("modelOutputReserveTokens");
        long configured = value instanceof Number number ? number.longValue() : 1_024L;
        return Math.max(1L, Math.min(configured, Math.max(1L, contextWindowTokens(state) / 2L)));
    }

    private static Map<String, Object> budgetMap(RunState state, String key) {
        return objectMap(state.spec().metadata().get(key));
    }

    private static BudgetPolicy budgetPolicy(RunState state, String key) {
        Map<String, Object> value = budgetMap(state, key);
        return new BudgetPolicy(nullableLong(value.get("maxTotalTokens")),
                nullableLong(value.get("maxCostMicrousd")), nullableLong(value.get("maxActiveSeconds")),
                nullableLong(value.get("maxToolCalls")), longValue(value.get("finalizationTokens"), 1_024L),
                String.valueOf(value.getOrDefault("parentRunId", "")));
    }

    static UsageDelta modelUsage(RunState state, Map<String, Integer> usage, long activeMillis) {
        long input = usageNumber(usage, "prompt_tokens", "input_tokens");
        long output = usageNumber(usage, "completion_tokens", "output_tokens");
        long total = usageNumber(usage, "total_tokens");
        if (total == 0) total = saturatedAdd(input, output);
        Map<String, Object> pricing = objectMap(state.spec().metadata().get("modelPricing"));
        boolean known = pricing.containsKey("inputUsd") && pricing.containsKey("outputUsd");
        long cost = known ? priceMicrousd(input, output, pricing) : 0L;
        return new UsageDelta(input, output, total, 1, 0, 0, 0,
                activeMillis, cost, String.valueOf(state.spec().metadata().getOrDefault("model", "")), known);
    }

    private static long priceMicrousd(long inputTokens, long outputTokens, Map<String, Object> pricing) {
        try {
            BigDecimal unit = BigDecimal.valueOf(Math.max(1L, longValue(pricing.get("unitTokens"), 1_000_000L)));
            BigDecimal input = new BigDecimal(String.valueOf(pricing.get("inputUsd")))
                    .multiply(BigDecimal.valueOf(Math.max(0L, inputTokens)));
            BigDecimal output = new BigDecimal(String.valueOf(pricing.get("outputUsd")))
                    .multiply(BigDecimal.valueOf(Math.max(0L, outputTokens)));
            return input.add(output).multiply(BigDecimal.valueOf(1_000_000L)).divide(unit, 0, RoundingMode.CEILING)
                    .min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
        } catch (RuntimeException invalidPricing) {
            return 0L;
        }
    }

    private static long usageNumber(Map<String, Integer> usage, String... keys) {
        for (String key : keys) {
            Number value = usage.get(key);
            if (value != null) return Math.max(0L, value.longValue());
        }
        return 0L;
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? Math.max(1L, number.longValue()) : null;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : fallback;
    }

    static long elapsedMillis(Instant start, Instant end) {
        try { return Math.max(0L, Duration.between(start, end).toMillis()); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private static long saturatedAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private static double ratio(Object value, double fallback) {
        double parsed = value instanceof Number number ? number.doubleValue() : fallback;
        return parsed > 0d && parsed <= 1d ? parsed : fallback;
    }

    static String summaryLine(Map<String, Object> message) {
        String role = String.valueOf(message.getOrDefault("role", "message"));
        String name = String.valueOf(message.getOrDefault("name", ""));
        String content = String.valueOf(message.getOrDefault("content", ""));
        return role + (name.isBlank() ? "" : "[" + name + "]") + ": " + content;
    }

    static String trimSummary(String value, int maxChars) {
        String text = value != null ? value.trim() : "";
        if (text.length() <= maxChars) return text;
        return "…" + text.substring(text.length() - Math.max(1, maxChars - 1));
    }

    static String message(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
    static String toolBudgetId(String runId, String callId) {
        return "tool:" + runId + ":" + callId;
    }
    static String budgetReason(String message) {
        for (String reason : List.of("cost_budget_unpriced", "token_budget", "cost_budget",
                "tool_call_budget", "active_time_budget")) {
            if (message != null && message.contains(reason)) return reason;
        }
        return "budget_exhausted";
    }
    static ModelInvocation.UnknownPolicy unknownModelPolicy(RunState state) {
        String mode = String.valueOf(state.spec().metadata().getOrDefault("providerRetryMode", "standard"));
        return "manual".equalsIgnoreCase(mode) || "never".equalsIgnoreCase(mode)
                ? ModelInvocation.UnknownPolicy.RECONCILE_THEN_WAIT
                : ModelInvocation.UnknownPolicy.RECONCILE_THEN_RETRY;
    }
    List<Map<String, Object>> messages(RunState state) {
        Object value = state.channels().get("transcriptCursor");
        long cursor = value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
        return new ArrayList<>(transcripts.read(state.spec().runId(), cursor));
    }
    static Map<String, Object> toolMessage(String id, String name, String content) {
        return Map.of("role", "tool", "tool_call_id", id, "name", name, "content", content);
    }
    private static String preview(ToolResult result) {
        if (result instanceof ToolResult.Success success) return success.preview();
        if (result instanceof ToolResult.Failure failure) return "ERROR: " + failure.message();
        return "external action pending";
    }
    @SuppressWarnings("unchecked") static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return (Map<String, Object>) map;
    }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return (List<Map<String, Object>>) (List<?>) list;
    }

    private final class ProviderAdapter implements ModelInvocationRuntime.ModelProviderPort {
        @Override public String prepareRequestId(ModelInvocationRuntime.ModelCall call) {
            return "provider:" + call.invocationId();
        }

        @Override public ModelInvocationRuntime.ModelObservation dispatch(ModelInvocationRuntime.ModelCall call) {
            String requestId = prepareRequestId(call);
            try {
                Map<String, Object> request = call.request();
                LLMResponse response = LLMFailureException.requireSuccess(provider.chat(
                        objectList(request.get("messages")), objectList(request.get("tools")),
                        String.valueOf(request.get("model")), number(request.get("maxTokens")),
                        null, null, null));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("content", response.getContent() != null ? response.getContent() : "");
                result.put("finishReason", response.getFinishReason() != null ? response.getFinishReason() : "stop");
                result.put("usage", response.getUsage() != null ? response.getUsage() : Map.of());
                result.put("toolCalls", response.getToolCalls().stream().map(AgentPhaseSupport::toolCall).toList());
                return new ModelInvocationRuntime.ModelObservation(requestId,
                        "model:" + RuntimeDigest.sha256(result), result);
            } catch (LLMFailureException failure) {
                if (ambiguous(failure)) {
                    throw new ModelInvocationRuntime.UncertainDispatchException(
                            failure.getMessage(), requestId, failure);
                }
                if (failure.kind() == LLMFailureKind.TRANSIENT
                        || failure.kind() == LLMFailureKind.RATE_LIMIT) {
                    long millis = failure.retryAfterSeconds() != null
                            ? Math.max(0L, Math.round(failure.retryAfterSeconds() * 1_000d)) : 0L;
                    throw new ModelInvocationRuntime.RetryableDispatchException(
                            failure.getMessage(), requestId, Duration.ofMillis(millis), failure);
                }
                throw new IllegalStateException(failure.getMessage(), failure);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                LLMFailureException classified = LLMFailureException.classify(failure);
                if (classified.kind() == LLMFailureKind.TRANSIENT || hasTransportCause(failure)) {
                    throw new ModelInvocationRuntime.UncertainDispatchException(
                            classified.getMessage(), requestId, failure);
                }
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }

        private boolean ambiguous(LLMFailureException failure) {
            return failure.kind() == LLMFailureKind.TRANSIENT
                    && failure.statusCode() == null;
        }

        private boolean hasTransportCause(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof IOException || current instanceof HttpTimeoutException
                        || current instanceof InterruptedException) return true;
            }
            return false;
        }
    }

    private static Map<String, Object> toolCall(ToolCallRequest call) {
        return Map.of("id", call.getId(), "name", call.getName(), "arguments", call.getArguments());
    }

    List<ChannelWrite> transcriptWrites(RunState state) {
        return List.of(ChannelWrite.set("transcriptReference", transcripts.reference(state.spec().runId())),
                ChannelWrite.set("transcriptCursor", transcripts.size(state.spec().runId())));
    }

    void observeModel(PhaseContext context, Map<String, Integer> usage, String finishReason) {
        runs.recordModel(context.state(), context.activationId(), clock.instant(),
                modelName(context.state()), usage);
        AgentRunSpec spec = runs.spec(context.state().spec().runId());
        if (spec != null && spec.getHook() != null) {
            try {
                spec.getHook().afterIteration(new AgentHookContext().setMessages(messages(context.state()))
                        .setIteration(number(context.state().channels().get("iterations")) + 1)
                        .setSessionKey(spec.getSessionKey()).setUsage(usage).setStopReason(finishReason));
            } catch (Exception failure) { throw new IllegalStateException("agent hook failed", failure); }
        }
    }

    private void observeTool(PhaseContext context, String callId, String name, boolean ok, String output) {
        runs.recordTool(context.state(), callId, clock.instant(), name, ok, output);
    }

    static void invokeBeforeTools(AgentRunSpec spec, PhaseContext context,
                                          List<Map<String, Object>> messages) {
        if (spec == null || spec.getHook() == null) return;
        try {
            spec.getHook().beforeExecuteTools(new AgentHookContext().setMessages(messages)
                    .setIteration(number(context.state().channels().get("iterations")))
                    .setSessionKey(spec.getSessionKey()));
        } catch (Exception failure) { throw new IllegalStateException("agent hook failed", failure); }
    }

    static void notifyToolStart(AgentRunSpec spec, String name, Map<String, Object> arguments) {
        if (spec != null && spec.getToolLifecycleCallback() != null) {
            spec.getToolLifecycleCallback().onToolStart(name, arguments);
        }
    }

    private static void notifyToolFinish(AgentRunSpec spec, String name, String callId,
                                         boolean ok, String output) {
        if (spec != null && spec.getToolLifecycleCallback() != null) {
            spec.getToolLifecycleCallback().onToolFinish(Map.of("toolName", name, "callId", callId,
                    "ok", ok, "output", output != null ? output : ""));
        }
    }

    static int number(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    static Map<String, Integer> integerMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (item instanceof Number number) result.put(String.valueOf(key), Math.max(0, number.intValue()));
        });
        return Map.copyOf(result);
    }

    record ChildBatch(List<RunSpec> children, boolean waitForAll) { }
}
