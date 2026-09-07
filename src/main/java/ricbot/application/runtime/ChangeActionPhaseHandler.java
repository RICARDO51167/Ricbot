package ricbot.application.runtime;

import ricbot.domain.runtime.PhaseContext;
import ricbot.domain.runtime.PhaseResult;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.domain.runtime.*;
import ricbot.domain.artifact.ArtifactDelta;
import ricbot.domain.artifact.ArtifactIntegrator;
import ricbot.domain.change.*;
import ricbot.domain.runtime.dto.RuntimeDigest;
import ricbot.domain.security.ApprovalBinding;
import ricbot.domain.security.ApprovalRequest;
import ricbot.integration.artifact.GitChangeSetArtifactIntegrator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ChangeActionPhaseHandler implements AgentPhaseHandler {
    private final AgentPhaseSupport services;
    ChangeActionPhaseHandler(AgentPhaseSupport services) { this.services = services; }
    @Override public PhaseResult execute(PhaseContext context) {
        return switch (context.state().phase()) {
            case INGEST -> ingest(context);
            case CONTEXT -> PhaseResult.route(RuntimePhase.MODEL);
            case MODEL -> PhaseResult.route(RuntimePhase.TOOLS);
            case TOOLS -> executeAction(context);
            case COMPACT, DELEGATE, WAIT, TERMINAL -> throw new IllegalStateException(
                    "change action cannot execute phase: " + context.state().phase());
        };
    }

    private PhaseResult ingest(PhaseContext context) {
        PendingChangeAction action = AgentPhaseSupport.changeAction(context.state());
        String requestId = String.valueOf(context.state().channels().getOrDefault("approvalRequestId", ""));
        if (requestId.isBlank()) {
            GitChangeSet changeSet = services.changes().load(action.changeSetId());
            if (changeSet == null) throw new IllegalArgumentException("changeset not found: " + action.changeSetId());
            if (action.actionType() == PendingChangeAction.ActionType.COMMIT) {
                services.changes().requireCommitEligible(changeSet);
            }
            String digest = ChangeSetActionAuthorization.digest(action.actionType(), action.changeSetId(),
                    action.commitMessage());
            ApprovalBinding binding = new ApprovalBinding(context.state().spec().runId(), context.activationId(),
                    "CHANGE_" + action.actionType().name(),
                    context.state().spec().runId() + ":" + action.actionType().name(),
                    action.changeSetId(), digest);
            ApprovalRequest request = services.approvals().createChangeActionRequest(
                    action.riskAssessment(), action, binding);
            return new PhaseResult(List.of(ChannelWrite.set("approvalRequestId", request.requestId())),
                    List.of(new RuntimeCommand.Suspend(new WaitReason.ApprovalWait(
                            request.requestId(), request.requestId()))));
        }
        ExternalEvent.ApprovalDecision decision = context.inbox().stream()
                .filter(ExternalEvent.ApprovalDecision.class::isInstance)
                .map(ExternalEvent.ApprovalDecision.class::cast)
                .filter(event -> requestId.equals(event.correlationId()) || requestId.equals(
                        String.valueOf(event.payload().getOrDefault("requestId", ""))))
                .findFirst().orElse(null);
        if (decision != null) {
            boolean approved = Boolean.parseBoolean(String.valueOf(
                    decision.payload().getOrDefault("approved", false)));
            if (!approved) {
                services.approvals().reject(requestId);
                return new PhaseResult(List.of(ChannelWrite.set("approvalDecision", "REJECTED")),
                        List.of(new RuntimeCommand.Complete(Map.of(
                                "status", "REJECTED", "requestId", requestId))));
            }
            services.approvals().approve(requestId);
            return new PhaseResult(List.of(ChannelWrite.set("approvalDecision", "APPROVED")),
                    List.of(new RuntimeCommand.Transition(RuntimePhase.CONTEXT)));
        }
        if ("APPROVED".equals(context.state().channels().get("approvalDecision"))
                && context.inbox().stream().anyMatch(ExternalEvent.EffectConfirmation.class::isInstance)) {
            return PhaseResult.route(RuntimePhase.CONTEXT);
        }
        return new PhaseResult(List.of(), List.of(new RuntimeCommand.Suspend(
                new WaitReason.ApprovalWait(requestId, requestId))));
    }

    private PhaseResult executeAction(PhaseContext context) {
        PendingChangeAction action = AgentPhaseSupport.changeAction(context.state());
        String requestId = String.valueOf(context.state().channels().getOrDefault("approvalRequestId", ""));
        ApprovalRequest request = services.approvals().find(requestId);
        if (request == null) return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail(
                "APPROVAL_MISSING", "approval request not found: " + requestId)));
        if (!request.consumed() && request.status() == ApprovalRequest.ApprovalStatus.APPROVED) {
            request = services.approvals().claim(requestId);
        }
        if (!request.consumed() && request.status() != ApprovalRequest.ApprovalStatus.CLAIMED) {
            return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail(
                    "APPROVAL_INVALID", "approval request is not claimed: " + requestId)));
        }
        ApprovalRequest claimed = request;
        GitChangeSet source = services.changes().load(action.changeSetId());
        if (source == null) return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail(
                "ARTIFACT_DELTA_MISSING", "changeset not found: " + action.changeSetId())));
        ArtifactDelta delta = new ArtifactDelta("delta:" + source.id(), "git-patch", source.baseCommit(),
                List.of("artifact:changeset:" + source.id()), Map.of(
                "changeSetId", source.id(), "action", action.actionType().name(),
                "commitMessage", action.commitMessage(), "changedFiles", source.changedFiles()));
        ArtifactIntegrator integrator = new GitChangeSetArtifactIntegrator(services.changes(),
                ChangeSetActionAuthorization.claimed(claimed));
        String effectId = "effect:change:" + context.state().spec().runId();
        EffectIntent intent = new EffectIntent(effectId, context.state().spec().runId(),
                context.activationId(), "artifact.integrate.git-patch", RuntimeDigest.sha256(delta),
                context.state().spec().runId() + ":" + action.actionType().name(),
                List.of("artifact:changeset:" + action.changeSetId(), "git:" + services.workspace()),
                Map.of("approvalRequestId", requestId, "actionDigest", claimed.binding().actionDigest(),
                        "deltaId", delta.deltaId()), "changeset-status");
        EffectRuntime effects = new EffectRuntime(services.store(), new EffectRuntime.ToolEffectPort() {
            @Override public EffectRuntime.EffectExecution dispatch(EffectIntent ignored) {
                ArtifactIntegrator.IntegrationResult result = integrator.integrate(delta);
                return new EffectRuntime.EffectExecution(result.resultReference(), result.evidence());
            }
            @Override public Optional<EffectRuntime.EffectExecution> reconcile(
                    EffectIntent ignored, Map<String, Object> evidence) {
                return integrator.reconcile(delta, evidence).map(result ->
                        new EffectRuntime.EffectExecution(result.resultReference(), result.evidence()));
            }
        }, services.clock(), services.owner(), Duration.ofSeconds(30), services.crashes());
        EffectRuntime.Outcome outcome = effects.execute(intent);
        if (outcome.retryRequired()) return services.scheduleEffectRetry(context, effectId);
        if (outcome.requiresConfirmation()) {
            return new PhaseResult(List.of(), List.of(new RuntimeCommand.Suspend(
                    new WaitReason.ExternalEventWait(effectId, "EffectConfirmation",
                            Map.of("effectId", effectId)))));
        }
        if (outcome.record().status() == EffectRecord.Status.FAILED) {
            return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail(
                    "CHANGE_ACTION_FAILED", outcome.record().failure())));
        }
        if (!request.consumed()) services.approvals().completeClaim(requestId);
        GitChangeSet result = services.changes().load(action.changeSetId());
        List<String> artifacts = new ArrayList<>(context.state().artifactReferences());
        artifacts.add("artifact-delta:" + delta.deltaId());
        if (!outcome.record().resultReference().isBlank()) artifacts.add(outcome.record().resultReference());
        return new PhaseResult(List.of(
                ChannelWrite.set("artifactReferences", artifacts.stream().distinct().toList()),
                ChannelWrite.set("changeActionResult", result != null ? result.toMap()
                        : Map.of("reference", outcome.record().resultReference()))),
                List.of(new RuntimeCommand.Complete(Map.of("status", "SUCCEEDED",
                        "artifactDelta", delta.deltaId(),
                        "artifactReference", outcome.record().resultReference()))));
    }
}
