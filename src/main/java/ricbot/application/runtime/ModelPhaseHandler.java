package ricbot.application.runtime;

import ricbot.domain.agent.context.ModelInputPlan;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.runtime.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModelPhaseHandler implements AgentPhaseHandler {
    private final AgentPhaseSupport services;
    ModelPhaseHandler(AgentPhaseSupport services) { this.services = services; }

    @Override public PhaseResult execute(PhaseContext context) {
        if (AgentPhaseSupport.changeActionRun(context.state())) return PhaseResult.route(RuntimePhase.TOOLS);
        List<Map<String, Object>> pendingCalls = AgentPhaseSupport.objectList(
                context.state().channels().get("pendingToolCalls"));
        if (!pendingCalls.isEmpty()
                && (!AgentPhaseSupport.objectMap(context.state().channels().get("approvalDecisions")).isEmpty()
                || context.state().channels().containsKey("pendingEffectId")
                || Boolean.TRUE.equals(context.state().channels().get("continueToolBatch")))) {
            return AgentPhaseSupport.routeToolCall(context.state(), pendingCalls.get(0));
        }
        ModelInputPlan input = services.compileModelInput(context.state());
        if (!input.tokens().fits()) {
            return new PhaseResult(List.of(ChannelWrite.set("modelInputPlan",
                    AgentPhaseSupport.planMetadata(input))), List.of(new RuntimeCommand.Fail(
                    "CONTEXT_WINDOW_EXCEEDED", "model input no longer fits the configured context window")));
        }
        Map<String, Object> persistedPlan = AgentPhaseSupport.objectMap(
                context.state().channels().get("modelInputPlan"));
        if (!persistedPlan.isEmpty() && !input.requestDigest().equals(
                String.valueOf(persistedPlan.getOrDefault("requestDigest", "")))) {
            return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail("MODEL_INPUT_CHANGED",
                    "compiled model input changed after the CONTEXT commit")));
        }
        Map<String, Object> retry = AgentPhaseSupport.objectMap(
                context.state().channels().get(AgentPhaseSupport.MODEL_RETRY));
        int attempt = Math.max(1, AgentPhaseSupport.number(retry.get("attempt")));
        String invocationBase = String.valueOf(retry.getOrDefault("invocationBase", context.activationId()));
        if (invocationBase.isBlank()) invocationBase = context.activationId();
        String invocationId = invocationBase + ":attempt-" + attempt;
        long reservedTokens = Math.max(1L, input.tokens().totalTokens());
        Map<String, Object> request = Map.of("messages", input.messages(), "tools", input.tools(),
                "model", services.modelName(context.state()), "maxTokens",
                Math.min(Integer.MAX_VALUE, input.tokens().outputReserveTokens()));
        Instant modelStarted = services.now();
        ModelInvocation invocation;
        try {
            invocation = services.invokeModel(new ModelInvocationRuntime.ModelCall(invocationId,
                    context.state().spec().runId(), context.activationId(), attempt, reservedTokens, request,
                    Boolean.TRUE.equals(retry.get("possibleDuplicateCharge")),
                    AgentPhaseSupport.unknownModelPolicy(context.state())));
        } catch (RuntimeException failure) {
            if (AgentPhaseSupport.message(failure).contains("budget reservation rejected")) {
                String reason = AgentPhaseSupport.budgetReason(AgentPhaseSupport.message(failure));
                return new PhaseResult(List.of(ChannelWrite.set("budgetStopReason", reason)),
                        List.of(new RuntimeCommand.Fail("BUDGET_EXHAUSTED",
                                AgentPhaseSupport.message(failure))));
            }
            throw failure;
        }
        long modelActiveMillis = AgentPhaseSupport.elapsedMillis(modelStarted, services.now());
        if (invocation.status() == ModelInvocation.Status.UNKNOWN) {
            if (invocation.unknownPolicy() == ModelInvocation.UnknownPolicy.RECONCILE_THEN_RETRY
                    && invocation.attempt() < AgentPhaseSupport.maxModelAttempts(context.state())) {
                return services.scheduleModelRetry(invocation, true, 0);
            }
            return new PhaseResult(List.of(new ChannelWrite(AgentPhaseSupport.MODEL_RETRY,
                    ChannelWrite.Operation.REMOVE, null)), List.of(new RuntimeCommand.Fail(
                    "MODEL_OUTCOME_UNKNOWN", "model dispatch outcome is unknown and cannot be reconciled: "
                    + invocation.invocationId())));
        }
        if (invocation.status() == ModelInvocation.Status.FAILED) {
            if (invocation.retryableFailure()
                    && invocation.attempt() < AgentPhaseSupport.maxModelAttempts(context.state())
                    && invocation.unknownPolicy() == ModelInvocation.UnknownPolicy.RECONCILE_THEN_RETRY) {
                return services.scheduleModelRetry(invocation, invocation.possibleDuplicateCharge(),
                        invocation.retryAfterMillis());
            }
            return new PhaseResult(List.of(new ChannelWrite(AgentPhaseSupport.MODEL_RETRY,
                    ChannelWrite.Operation.REMOVE, null)),
                    List.of(new RuntimeCommand.Fail("MODEL_FAILED", invocation.failure())));
        }
        Map<String, Object> response = invocation.response();
        List<Map<String, Object>> calls = AgentPhaseSupport.objectList(response.get("toolCalls"));
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", String.valueOf(response.getOrDefault("content", "")));
        if (!calls.isEmpty()) assistant.put("tool_calls", calls);
        services.appendTranscript(context.state().spec().runId(), context.activationId() + ":assistant",
                Map.copyOf(assistant));
        Map<String, Integer> usage = AgentPhaseSupport.integerMap(response.get("usage"));
        int iteration = AgentPhaseSupport.number(context.state().channels().get("iterations")) + 1;
        UsageLedger ledger = UsageLedger.from(context.state().channels().get("usageLedger"))
                .plus(AgentPhaseSupport.modelUsage(context.state(), usage, modelActiveMillis));
        services.observeModel(context, usage, String.valueOf(response.getOrDefault("finishReason", "stop")));
        List<ChannelWrite> writes = new ArrayList<>(services.transcriptWrites(context.state()));
        writes.add(ChannelWrite.set("modelResponseReference", invocation.responseReference()));
        writes.add(ChannelWrite.set("usage", usage));
        writes.add(ChannelWrite.set("usageLedger", ledger));
        writes.add(ChannelWrite.set("iterations", iteration));
        writes.add(ChannelWrite.set("stopReason", String.valueOf(response.getOrDefault("finishReason", "stop"))));
        writes.add(ChannelWrite.set("pendingToolCalls", calls));
        writes.add(ChannelWrite.set("continueToolBatch", false));
        writes.add(new ChannelWrite(AgentPhaseSupport.MODEL_RETRY, ChannelWrite.Operation.REMOVE, null));
        writes.add(new ChannelWrite("approvalDecisions", ChannelWrite.Operation.REMOVE, null));
        if (!calls.isEmpty()) {
            Map<String, Object> first = calls.get(0);
            if (AgentPhaseSupport.SPAWN_CHILD_RUNS.equals(String.valueOf(first.getOrDefault("name", "")))
                    && AgentPhaseSupport.delegationAllowed(context.state())) {
                writes.add(ChannelWrite.set("pendingRuntimeControl", first));
                return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(RuntimePhase.DELEGATE)));
            }
            return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(RuntimePhase.TOOLS)));
        }
        return new PhaseResult(writes, List.of(new RuntimeCommand.Complete(
                Map.of("content", String.valueOf(response.getOrDefault("content", ""))))));
    }
}
