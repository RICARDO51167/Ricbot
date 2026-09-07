package ricbot.application.runtime;

import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.event.AgentEvent;
import ricbot.domain.runtime.RunState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local cancellation, callbacks and observation state; no durable facts live here. */
final class RunExecutionRegistry {
    private final Map<String, AgentRunSpec> specs = new ConcurrentHashMap<>();
    private final Map<String, MutableObservation> observations = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();
    private final Map<String, Thread> activeToolThreads = new ConcurrentHashMap<>();
    private final Map<String, Long> toolActiveMillis = new ConcurrentHashMap<>();

    void attach(String runId, AgentRunSpec spec) {
        specs.put(Objects.requireNonNull(runId, "runId"), Objects.requireNonNull(spec, "spec"));
        observations.put(runId, new MutableObservation());
        cancellationFlags.put(runId, new AtomicBoolean());
    }

    AgentRunSpec spec(String runId) { return specs.get(runId); }

    InvocationObservation observation(String runId) {
        MutableObservation value = observations.get(runId);
        return value != null ? value.snapshot() : InvocationObservation.empty();
    }

    void detach(String runId) {
        specs.remove(runId);
        observations.remove(runId);
        cancellationFlags.remove(runId);
        activeToolThreads.remove(runId);
    }

    void cancel(String runId) {
        cancellationFlags.computeIfAbsent(runId, ignored -> new AtomicBoolean()).set(true);
        Thread active = activeToolThreads.get(runId);
        if (active != null) active.interrupt();
    }

    AtomicBoolean cancellationFlag(String runId) {
        return cancellationFlags.computeIfAbsent(runId, ignored -> new AtomicBoolean());
    }

    void beginTool(String runId, Thread thread) {
        Thread previous = activeToolThreads.putIfAbsent(runId, thread);
        if (previous != null && previous != thread) {
            throw new IllegalStateException("a tool invocation is already active for run " + runId);
        }
    }

    void finishTool(String runId, Thread thread, String reservationId, long activeMillis) {
        toolActiveMillis.put(reservationId, Math.max(0L, activeMillis));
        activeToolThreads.remove(runId, thread);
    }

    long consumeToolActiveMillis(String reservationId) {
        Long value = toolActiveMillis.remove(reservationId);
        return value != null ? value : 0L;
    }

    void recordModel(RunState state, String causationId, Instant now, String model,
                     Map<String, Integer> usage) {
        MutableObservation observation = observations.get(state.spec().runId());
        if (observation == null) return;
        observation.events.add(new AgentEvent.ModelCall(observation.meta(state, causationId, now),
                "observed", model, new LinkedHashMap<>(usage)));
    }

    void recordTool(RunState state, String callId, Instant now, String name, boolean ok, String output) {
        MutableObservation observation = observations.get(state.spec().runId());
        if (observation == null) return;
        observation.toolsUsed.add(name);
        observation.toolEvents.add(Map.of("callId", callId, "toolName", name,
                "ok", ok, "output", output != null ? output : ""));
        observation.events.add(new AgentEvent.ToolCall(observation.meta(state, callId, now),
                "finished", callId, name, ok));
    }

    record InvocationObservation(List<String> toolsUsed, List<Map<String, Object>> toolEvents,
                                 List<AgentEvent> events) {
        InvocationObservation {
            toolsUsed = List.copyOf(toolsUsed != null ? toolsUsed : List.of());
            toolEvents = List.copyOf(toolEvents != null ? toolEvents : List.of());
            events = List.copyOf(events != null ? events : List.of());
        }
        static InvocationObservation empty() {
            return new InvocationObservation(List.of(), List.of(), List.of());
        }
    }

    private static final class MutableObservation {
        private final List<String> toolsUsed = new ArrayList<>();
        private final List<Map<String, Object>> toolEvents = new ArrayList<>();
        private final List<AgentEvent> events = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong();

        private AgentEvent.EventMeta meta(RunState state, String causationId, Instant now) {
            long next = sequence.incrementAndGet();
            return new AgentEvent.EventMeta("observation-" + state.spec().runId() + "-" + next,
                    next, state.spec().runId(),
                    String.valueOf(state.spec().metadata().getOrDefault("sessionId", "")), "",
                    causationId, state.spec().runId(), now);
        }

        private InvocationObservation snapshot() {
            return new InvocationObservation(toolsUsed, toolEvents, events);
        }
    }
}
