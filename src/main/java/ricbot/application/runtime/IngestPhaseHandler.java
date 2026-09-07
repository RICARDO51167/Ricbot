package ricbot.application.runtime;

import ricbot.domain.runtime.PhaseContext;
import ricbot.domain.runtime.PhaseResult;
import ricbot.domain.runtime.ChannelWrite;
import ricbot.domain.runtime.ExternalEvent;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.runtime.RuntimePhase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IngestPhaseHandler implements AgentPhaseHandler {
    private final AgentPhaseSupport services;
    IngestPhaseHandler(AgentPhaseSupport services) { this.services = services; }
    @Override public PhaseResult execute(PhaseContext context) {
        Map<String, Object> decisions = new LinkedHashMap<>();
        boolean externalResult = false;
        for (ExternalEvent event : context.inbox()) {
            if (event instanceof ExternalEvent.UserMessage || event instanceof ExternalEvent.SteeringMessage) {
                String content = String.valueOf(event.payload().getOrDefault("text",
                        event.payload().getOrDefault("content", "")));
                if (!content.isBlank()) services.appendTranscript(context.state().spec().runId(),
                        "external:" + event.eventId(), Map.of("role", "user", "content", content));
            } else if (event instanceof ExternalEvent.ApprovalDecision) {
                String requestId = String.valueOf(event.payload().getOrDefault(
                        "requestId", event.correlationId()));
                boolean approved = Boolean.parseBoolean(String.valueOf(
                        event.payload().getOrDefault("approved", false)));
                services.recordApprovalDecision(requestId, approved);
                decisions.put(requestId, approved ? "APPROVED" : "REJECTED");
            } else if (event instanceof ExternalEvent.ExternalActionResult) {
                String callId = String.valueOf(event.payload().getOrDefault("callId", event.correlationId()));
                String tool = String.valueOf(event.payload().getOrDefault("tool", "external"));
                String content = String.valueOf(event.payload().getOrDefault("resultReference",
                        event.payload().getOrDefault("content", "external action completed")));
                services.appendTranscript(context.state().spec().runId(),
                        "external-result:" + event.eventId(), AgentPhaseSupport.toolMessage(
                                callId, tool, content));
                externalResult = true;
            } else if (event instanceof ExternalEvent.ChildRunCompleted) {
                services.appendTranscript(context.state().spec().runId(),
                        "child-result:" + event.eventId(), Map.of("role", "system",
                                "name", "child_run", "content", String.valueOf(event.payload())));
            }
        }
        if (services.transcriptSize(context.state().spec().runId()) == 0) {
            services.appendTranscript(context.state().spec().runId(),
                    "goal:" + context.state().spec().runId(),
                    Map.of("role", "user", "content", context.state().spec().goal()));
        }
        List<ChannelWrite> writes = new ArrayList<>(services.transcriptWrites(context.state()));
        if (!decisions.isEmpty()) {
            writes.add(new ChannelWrite("approvalDecisions", ChannelWrite.Operation.MERGE, decisions));
        }
        if (externalResult) {
            List<Map<String, Object>> pending = AgentPhaseSupport.objectList(
                    context.state().channels().get("pendingToolCalls"));
            writes.add(ChannelWrite.set("pendingToolCalls", pending.isEmpty() ? List.of()
                    : List.copyOf(pending.subList(1, pending.size()))));
            writes.add(new ChannelWrite("pendingExternalCall", ChannelWrite.Operation.REMOVE, null));
            writes.add(new ChannelWrite("pendingEffectId", ChannelWrite.Operation.REMOVE, null));
            writes.add(ChannelWrite.set("continueToolBatch", pending.size() > 1));
        }
        return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(RuntimePhase.CONTEXT)));
    }
}
