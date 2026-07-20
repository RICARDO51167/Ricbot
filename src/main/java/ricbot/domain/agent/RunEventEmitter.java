package ricbot.domain.agent;

import java.util.Map;

/** Serializes event publication, including concurrent tool callbacks. */
final class RunEventEmitter {
    private final RunEventSink sink;
    private final String runId;
    private final String sessionKey;
    private long sequence;
    private RunState state;

    RunEventEmitter(RunEventSink sink, String runId, String sessionKey) {
        this.sink = sink != null ? sink : RunEventSink.disabled();
        this.runId = runId;
        this.sessionKey = sessionKey;
    }

    RunEventEmitter(RunEventSink sink, RunState initialState) {
        if (initialState == null) {
            throw new IllegalArgumentException("initialState is required");
        }
        this.sink = sink != null ? sink : RunEventSink.disabled();
        this.runId = initialState.runId();
        this.sessionKey = initialState.sessionKey();
        this.sequence = initialState.lastSequence();
        this.state = initialState;
    }

    String runId() {
        return runId;
    }

    synchronized long currentSequence() {
        return sequence;
    }

    synchronized long nextSequence() {
        return sequence + 1;
    }

    synchronized RunState currentState() {
        return state;
    }

    synchronized RunEvent emit(
            int iteration,
            RunEventType type,
            RunStatus status,
            ToolInvocationRecord toolInvocation,
            Map<String, Object> details
    ) {
        RunEvent event = RunEvent.create(
                ++sequence,
                runId,
                sessionKey,
                iteration,
                type,
                status,
                toolInvocation,
                details
        );
        RunState nextState = state == null ? RunState.from(event) : state.apply(event);
        try {
            sink.append(event);
        } catch (RuntimeException e) {
            throw new RunJournalException(
                    "failed to persist run event " + type + " for run " + runId,
                    e
            );
        }
        state = nextState;
        return event;
    }
}
