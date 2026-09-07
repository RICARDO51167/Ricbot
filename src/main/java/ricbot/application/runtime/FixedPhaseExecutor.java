package ricbot.application.runtime;

import ricbot.domain.runtime.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Dispatches only the fixed v6 phases; callers may replace behavior, never graph topology. */
public final class FixedPhaseExecutor implements PhaseExecutor {
    private final Map<RuntimePhase, PhaseExecutor> handlers;

    public FixedPhaseExecutor(Map<RuntimePhase, PhaseExecutor> handlers) {
        EnumMap<RuntimePhase, PhaseExecutor> copy = new EnumMap<>(RuntimePhase.class);
        if (handlers != null) copy.putAll(handlers);
        if (copy.containsKey(RuntimePhase.WAIT) || copy.containsKey(RuntimePhase.TERMINAL)) {
            throw new IllegalArgumentException("WAIT and TERMINAL are not executable phases");
        }
        this.handlers = Map.copyOf(copy);
    }

    @Override public PhaseResult execute(PhaseContext context) {
        PhaseExecutor handler = handlers.get(context.state().phase());
        if (handler != null) return handler.execute(context);
        return switch (context.state().phase()) {
            case INGEST -> new PhaseResult(context.inbox().isEmpty() ? List.of()
                    : List.of(ChannelWrite.set("lastExternalEvents", context.inbox())),
                    List.of(new RuntimeCommand.Transition(RuntimePhase.CONTEXT)));
            case CONTEXT -> PhaseResult.route(RuntimePhase.MODEL);
            case MODEL -> new PhaseResult(List.of(), List.of(new RuntimeCommand.Suspend(
                    new WaitReason.UserInputWait("input:" + context.state().spec().runId(), "Awaiting user input"))));
            case TOOLS, COMPACT, DELEGATE -> PhaseResult.route(RuntimePhase.CONTEXT);
            case WAIT, TERMINAL -> throw new IllegalStateException("non-executable phase: " + context.state().phase());
        };
    }
}
