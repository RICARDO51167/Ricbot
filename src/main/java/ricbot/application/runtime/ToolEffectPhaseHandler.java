package ricbot.application.runtime;

import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.runtime.*;
import ricbot.domain.security.*;
import ricbot.tool.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ToolEffectPhaseHandler implements AgentPhaseHandler {
    private final AgentPhaseSupport services;
    ToolEffectPhaseHandler(AgentPhaseSupport services) { this.services = services; }

    @Override public PhaseResult execute(PhaseContext context) {
        List<Map<String, Object>> calls = AgentPhaseSupport.objectList(
                context.state().channels().get("pendingToolCalls"));
        if (calls.isEmpty()) return PhaseResult.route(RuntimePhase.CONTEXT);
        UsageLedger ledger = UsageLedger.from(context.state().channels().get("usageLedger"));
        AgentRunSpec invocation = services.invocationSpec(context.state().spec().runId());
        AgentPhaseSupport.invokeBeforeTools(invocation, context, services.messages(context.state()));
        Map<String, Object> call = calls.get(0);
        List<Map<String, Object>> remaining = List.copyOf(calls.subList(1, calls.size()));
        String callId = String.valueOf(call.getOrDefault("id", context.activationId() + ":tool"));
        String name = String.valueOf(call.getOrDefault("name", ""));
        Map<String, Object> arguments = AgentPhaseSupport.objectMap(call.get("arguments"));
        ToolExecutionContext toolContext = services.toolContext(context);
        ToolDispatcher.Prepared prepared;
        try {
            prepared = services.dispatcher().prepare(new ToolInvocation(callId, name, arguments),
                    toolContext, services.activeToolGroups(context.state()));
        } catch (ToolDispatcher.ToolDispatchException failure) {
            return services.finishTool(context, invocation, ledger, remaining, callId, name,
                    new ToolResult.Failure(failure.code(), failure.getMessage(), false, List.of()));
        }
        if (prepared.authorization().decision() == ToolAuthorizationDecision.Decision.REQUIRE_APPROVAL) {
            Map<String, Object> requestIds = AgentPhaseSupport.objectMap(
                    context.state().channels().get("toolApprovalRequests"));
            String requestId = String.valueOf(requestIds.getOrDefault(callId, ""));
            ApprovalRequest request = requestId.isBlank() ? null : services.approvals().find(requestId);
            if (request == null) {
                RiskAssessment risk = RiskAssessment.of(CommandRiskLevel.HIGH,
                        List.of(prepared.authorization().reason()), "", name, prepared.resourceKeys());
                ApprovalBinding binding = new ApprovalBinding(context.state().spec().runId(),
                        context.activationId(), "TOOL", context.state().spec().runId() + ":" + callId,
                        name, prepared.invocationDigest());
                PendingToolCall pending = PendingToolCall.create(null, name, arguments,
                        String.valueOf(context.state().spec().metadata().getOrDefault("sessionId", "")), risk);
                request = services.approvals().createRequest(risk, pending, binding);
                return new PhaseResult(List.of(new ChannelWrite("toolApprovalRequests",
                                ChannelWrite.Operation.MERGE, Map.of(callId, request.requestId()))),
                        List.of(new RuntimeCommand.Suspend(new WaitReason.ApprovalWait(
                                request.requestId(), request.requestId()))));
            }
            if (request.status() == ApprovalRequest.ApprovalStatus.PENDING) {
                return new PhaseResult(List.of(), List.of(new RuntimeCommand.Suspend(
                        new WaitReason.ApprovalWait(requestId, requestId))));
            }
            if (request.status() == ApprovalRequest.ApprovalStatus.REJECTED) {
                return services.finishTool(context, invocation, ledger, remaining, callId, name,
                        new ToolResult.Failure("APPROVAL_REJECTED", "tool approval rejected", false, List.of()));
            }
            if (request.status() == ApprovalRequest.ApprovalStatus.APPROVED) {
                services.approvals().claim(requestId);
            }
            prepared = new ToolDispatcher.Prepared(prepared.tool(), prepared.invocation(),
                    new ToolAuthorizationDecision(ToolAuthorizationDecision.Decision.ALLOW,
                            "approved by " + requestId, List.of("approval:" + requestId), false),
                    prepared.resourceKeys(), prepared.invocationDigest());
        }
        String toolBudgetId = AgentPhaseSupport.toolBudgetId(context.state().spec().runId(), callId);
        try {
            services.store().reserveToolCall(context.state().spec().runId(), toolBudgetId, services.now());
        } catch (RuntimeException rejected) {
            if (!AgentPhaseSupport.message(rejected).contains("budget reservation rejected")) throw rejected;
            String reason = AgentPhaseSupport.budgetReason(AgentPhaseSupport.message(rejected));
            return new PhaseResult(List.of(ChannelWrite.set("budgetStopReason", reason)),
                    List.of(new RuntimeCommand.Fail("BUDGET_EXHAUSTED", AgentPhaseSupport.message(rejected))));
        }
        if (prepared.tool().descriptor().executionMode() == ExecutionMode.EXTERNAL) {
            EffectIntent intent = services.toolEffectIntent(context, prepared, callId, name);
            EffectRecord record = services.store().effect(intent.effectId()).orElse(null);
            if (record == null) {
                record = services.store().saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED,
                        1, -1, Map.of(), "", "", services.now()));
            }
            if (record.status() == EffectRecord.Status.PREPARED) {
                record = services.store().saveEffect(new EffectRecord(record.intent(),
                        EffectRecord.Status.DISPATCHING, record.attempt(), record.committedSuperstep(),
                        Map.of("externalActionId", callId), "", "", services.now()));
            }
            if (record.status() == EffectRecord.Status.UNKNOWN) {
                return new PhaseResult(List.of(ChannelWrite.set("pendingEffectId", intent.effectId())),
                        List.of(new RuntimeCommand.Suspend(new WaitReason.ExternalEventWait(
                                intent.effectId(), "EffectConfirmation", Map.of("effectId", intent.effectId())))));
            }
            if (record.status() == EffectRecord.Status.FAILED) {
                return services.finishTool(context, invocation, ledger, remaining, callId, name,
                        new ToolResult.Failure("EXTERNAL_ACTION_FAILED", record.failure(), false, List.of()));
            }
            if (record.status() == EffectRecord.Status.SUCCEEDED) {
                Object value = record.executionEvidence().isEmpty()
                        ? record.resultReference() : record.executionEvidence();
                return services.finishTool(context, invocation, ledger, remaining, callId, name,
                        ToolResult.Success.of(value));
            }
            return new PhaseResult(List.of(ChannelWrite.set("pendingExternalCall", call),
                            ChannelWrite.set("pendingEffectId", intent.effectId())),
                    List.of(new RuntimeCommand.Suspend(new WaitReason.ExternalEventWait(callId,
                            "ExternalActionResult", Map.of("tool", name, "effectId", intent.effectId(),
                            "invocationDigest", prepared.invocationDigest())))));
        }
        AgentPhaseSupport.notifyToolStart(invocation, name, arguments);
        ToolResult result;
        if (prepared.tool().descriptor().effectPolicy().readOnly()) {
            result = services.executePrepared(prepared, toolContext);
        } else {
            ToolDispatcher.Prepared authorized = prepared;
            EffectIntent intent = services.toolEffectIntent(context, prepared, callId, name);
            ToolResult[] captured = new ToolResult[1];
            EffectRuntime effects = new EffectRuntime(services.store(), new EffectRuntime.ToolEffectPort() {
                @Override public EffectRuntime.EffectExecution dispatch(EffectIntent ignored) {
                    captured[0] = services.executePrepared(authorized, toolContext);
                    return new EffectRuntime.EffectExecution("effect-result:" + callId,
                            AgentPhaseSupport.encodeToolResult(captured[0]));
                }
                @Override public Optional<EffectRuntime.EffectExecution> reconcile(
                        EffectIntent ignored, Map<String, Object> evidence) {
                    try {
                        return authorized.tool().reconcile(authorized.invocation(), toolContext, evidence)
                                .map(reconciled -> new EffectRuntime.EffectExecution("effect-result:" + callId,
                                        AgentPhaseSupport.encodeToolResult(reconciled)));
                    } catch (Exception failure) { return Optional.empty(); }
                }
            }, services.clock(), services.owner(), Duration.ofSeconds(30), services.crashes());
            EffectRuntime.Outcome outcome = effects.execute(intent);
            if (outcome.retryRequired()) return services.scheduleEffectRetry(context, intent.effectId());
            if (outcome.requiresConfirmation()) {
                return new PhaseResult(List.of(ChannelWrite.set("pendingEffectId", intent.effectId())),
                        List.of(new RuntimeCommand.Suspend(new WaitReason.ExternalEventWait(
                                intent.effectId(), "EffectConfirmation", Map.of("effectId", intent.effectId())))));
            }
            result = captured[0] != null ? captured[0] : AgentPhaseSupport.decodeToolResult(
                    outcome.record().executionEvidence(), outcome.record().resultReference());
        }
        return services.finishTool(context, invocation, ledger, remaining, callId, name, result);
    }
}
