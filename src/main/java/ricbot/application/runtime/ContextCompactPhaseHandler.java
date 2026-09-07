package ricbot.application.runtime;

import ricbot.domain.runtime.PhaseContext;
import ricbot.domain.runtime.PhaseResult;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.domain.runtime.ChannelWrite;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.agent.context.ModelInputPlan;

import java.util.List;
import java.util.Map;

final class ContextCompactPhaseHandler implements AgentPhaseHandler {
    private final AgentPhaseSupport services;
    ContextCompactPhaseHandler(AgentPhaseSupport services) { this.services = services; }
    @Override public PhaseResult execute(PhaseContext context) {
        return context.state().phase() == RuntimePhase.CONTEXT ? context(context) : compact(context);
    }

    private PhaseResult context(PhaseContext context) {
        if (AgentPhaseSupport.changeActionRun(context.state())) return PhaseResult.route(RuntimePhase.MODEL);
        List<Map<String, Object>> pendingCalls = AgentPhaseSupport.objectList(
                context.state().channels().get("pendingToolCalls"));
        if (!pendingCalls.isEmpty()
                && (!AgentPhaseSupport.objectMap(context.state().channels().get("approvalDecisions")).isEmpty()
                || context.state().channels().containsKey("pendingEffectId")
                || Boolean.TRUE.equals(context.state().channels().get("continueToolBatch")))) {
            return PhaseResult.route(RuntimePhase.MODEL);
        }
        ModelInputPlan plan = services.compileModelInput(context.state());
        List<ChannelWrite> writes = List.of(ChannelWrite.set(
                "modelInputPlan", AgentPhaseSupport.planMetadata(plan)));
        if (services.shouldCompact(context.state(), plan)) {
            return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(RuntimePhase.COMPACT)));
        }
        if (!plan.tokens().fits()) {
            return new PhaseResult(writes, List.of(new RuntimeCommand.Fail("CONTEXT_WINDOW_EXCEEDED",
                    "compiled model input requires " + plan.tokens().totalTokens()
                            + " tokens for a " + plan.tokens().contextWindowTokens() + " token window")));
        }
        return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(RuntimePhase.MODEL)));
    }

    private PhaseResult compact(PhaseContext context) {
        List<Map<String, Object>> transcript = services.messages(context.state());
        int start = Math.max(AgentPhaseSupport.compactedCursor(context.state(), transcript.size()),
                AgentPhaseSupport.leadingSystemMessages(transcript));
        int keep = Math.min(8, Math.max(1, transcript.size() / 10));
        int through = Math.max(start, transcript.size() - keep);
        if (through <= start) {
            return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail("CONTEXT_WINDOW_EXCEEDED",
                    "the current interaction cannot be compacted without breaking message integrity")));
        }
        String previous = String.valueOf(context.state().channels().getOrDefault("contextSummary", ""));
        String addition = transcript.subList(start, through).stream().map(AgentPhaseSupport::summaryLine)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
        String summary = AgentPhaseSupport.trimSummary(
                (previous.isBlank() ? "" : previous + "\n") + addition,
                (int) Math.max(256L, Math.min(8_000L,
                        AgentPhaseSupport.contextWindowTokens(context.state()) / 2L)));
        int count = AgentPhaseSupport.number(context.state().channels().get("compactionCount")) + 1;
        return new PhaseResult(List.of(ChannelWrite.set("contextSummary", summary),
                        ChannelWrite.set("compactedThroughCursor", through),
                        ChannelWrite.set("transcriptCursor",
                                services.transcriptSize(context.state().spec().runId())),
                        ChannelWrite.set("compactionCount", count)),
                List.of(new RuntimeCommand.Transition(RuntimePhase.CONTEXT)));
    }
}
