package ricbot.domain.runtime;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure, deterministic event reducer. Unknown schema versions have already failed closed in the upcaster. */
public final class RuntimeReducer {
    /**
     * v6 superstep reducer. It is deliberately free of clocks, stores, providers, tools and random values.
     * The returned state only becomes authoritative when an AtomicCommitter persists it.
     */
    public Reduction reduce(RunState current, List<ChannelWrite> writes, List<RuntimeCommand> commands) {
        if (current == null) throw new IllegalArgumentException("current state is required");
        if (current.status() != RunStatus.RUNNING) {
            throw new IllegalStateException("only RUNNING state can be reduced");
        }
        List<ChannelWrite> safeWrites = List.copyOf(writes != null ? writes : List.of());
        List<RuntimeCommand> safeCommands = List.copyOf(commands != null ? commands : List.of());
        Map<String, Object> channels = new LinkedHashMap<>(current.channels());
        for (ChannelWrite write : safeWrites) apply(channels, write);

        RunStatus status = RunStatus.READY;
        RuntimePhase phase = defaultNext(current.phase());
        WaitReason waitReason = null;
        String failureCode = "";
        String failureMessage = "";
        List<RunSpec> children = new ArrayList<>();
        List<String> childIds = new ArrayList<>(current.childRunIds());
        List<String> joinedChildIds = new ArrayList<>();
        boolean terminalCommand = false;
        boolean routingCommand = false;
        boolean waitForChildren = false;
        RuntimeCommand.ExternalInterrupt externalInterrupt = null;
        RuntimeCommand.CancelAtBoundary cancellation = null;

        for (RuntimeCommand command : safeCommands) {
            if (command instanceof RuntimeCommand.Transition transition) {
                if (routingCommand) throw new IllegalArgumentException("a superstep has more than one route");
                phase = transition.phase();
                validateTransition(current.phase(), phase);
                routingCommand = true;
            } else if (command instanceof RuntimeCommand.Suspend suspend) {
                if (routingCommand) throw new IllegalArgumentException("a superstep has more than one route");
                status = RunStatus.WAITING;
                phase = RuntimePhase.WAIT;
                waitReason = suspend.reason();
                routingCommand = true;
            } else if (command instanceof RuntimeCommand.Complete complete) {
                if (routingCommand) throw new IllegalArgumentException("a superstep has more than one route");
                status = RunStatus.COMPLETED;
                phase = RuntimePhase.TERMINAL;
                channels.put("result", complete.result());
                routingCommand = true;
                terminalCommand = true;
            } else if (command instanceof RuntimeCommand.Fail fail) {
                if (routingCommand) throw new IllegalArgumentException("a superstep has more than one route");
                status = RunStatus.FAILED;
                phase = RuntimePhase.TERMINAL;
                failureCode = fail.code();
                failureMessage = fail.message();
                routingCommand = true;
                terminalCommand = true;
            } else if (command instanceof RuntimeCommand.SpawnChildRuns spawn) {
                for (RunSpec child : spawn.children()) {
                    if (!current.spec().runId().equals(child.parentRunId())) {
                        throw new IllegalArgumentException("child parentRunId must match current run");
                    }
                    if (childIds.contains(child.runId())) throw new IllegalArgumentException("duplicate child run: " + child.runId());
                    children.add(child);
                    childIds.add(child.runId());
                    if (spawn.waitForAll()) joinedChildIds.add(child.runId());
                }
                waitForChildren |= spawn.waitForAll() && !spawn.children().isEmpty();
            } else if (command instanceof RuntimeCommand.ExternalInterrupt interrupt) {
                externalInterrupt = interrupt;
            } else if (command instanceof RuntimeCommand.CancelAtBoundary cancel) {
                cancellation = cancel;
            }
        }
        if (terminalCommand && !children.isEmpty()) {
            throw new IllegalArgumentException("a terminal superstep cannot also spawn children");
        }
        if (waitForChildren) {
            if (routingCommand) throw new IllegalArgumentException("a child join cannot also specify another route");
            status = RunStatus.WAITING;
            phase = RuntimePhase.WAIT;
            waitReason = new WaitReason.ChildRunWait(
                    "children:" + current.spec().runId() + ":" + (current.commitSequence() + 1), joinedChildIds);
        }
        if (externalInterrupt != null && cancellation == null) {
            status = RunStatus.READY;
            phase = RuntimePhase.INGEST;
            waitReason = null;
        }
        boolean cancelRequested = current.cancelRequested() || cancellation != null;
        if (cancelRequested) {
            if (cancellation != null && !cancellation.unresolvedEffectIds().isEmpty()) {
                String effectId = cancellation.unresolvedEffectIds().get(0);
                status = RunStatus.WAITING;
                phase = RuntimePhase.WAIT;
                waitReason = new WaitReason.ExternalEventWait(effectId, "EffectConfirmation",
                        Map.of("effectIds", cancellation.unresolvedEffectIds()));
            } else {
                status = RunStatus.CANCELLED;
                phase = RuntimePhase.TERMINAL;
                waitReason = null;
            }
        }
        if (phase == RuntimePhase.TERMINAL && status == RunStatus.READY) status = RunStatus.COMPLETED;
        List<String> artifacts = references(channels.get("artifactReferences"));
        RunState reduced = new RunState(RunState.SCHEMA_VERSION, RunState.GRAPH_VERSION, current.spec(),
                status, phase, current.superstep() + 1, current.commitSequence() + 1,
                channels, waitReason, cancelRequested, failureCode, failureMessage,
                childIds, artifacts);
        return new Reduction(reduced, children);
    }

    /** Pure state transition performed when an external event is durably accepted. */
    public RunState accept(RunState current, ExternalEvent event) {
        return accept(current, event, List.of());
    }

    /** Pure event transition with durable commands produced from transaction facts. */
    public RunState accept(RunState current, ExternalEvent event, List<RuntimeCommand> commands) {
        if (current == null || event == null) throw new IllegalArgumentException("state and event are required");
        if (current.status().terminal()) throw new IllegalStateException("terminal runs cannot accept events");
        boolean cancel = current.cancelRequested() || event instanceof ExternalEvent.CancelRequested;
        RunStatus status;
        RuntimePhase phase;
        WaitReason waitReason = null;
        String failureCode = current.failureCode();
        String failureMessage = current.failureMessage();
        if (current.status() == RunStatus.RUNNING) {
            status = RunStatus.RUNNING;
            phase = current.phase();
        } else if (cancel) {
            status = RunStatus.CANCELLED;
            phase = RuntimePhase.TERMINAL;
        } else if (isRejectedForkConfirmation(current, event)) {
            status = RunStatus.CANCELLED;
            phase = RuntimePhase.TERMINAL;
        } else if (current.waitReason() instanceof WaitReason.ChildRunWait wait
                && event instanceof ExternalEvent.ChildRunCompleted child) {
            String completedId = String.valueOf(child.payload().getOrDefault("childRunId", child.correlationId()));
            if (!wait.childRunIds().contains(completedId)) {
                throw new IllegalArgumentException("child completion does not match wait reason: " + completedId);
            }
            List<String> remaining = wait.childRunIds().stream()
                    .filter(childId -> !childId.equals(completedId)).toList();
            if (remaining.isEmpty()) {
                status = RunStatus.READY;
                phase = RuntimePhase.INGEST;
            } else {
                status = RunStatus.WAITING;
                phase = RuntimePhase.WAIT;
                waitReason = new WaitReason.ChildRunWait(wait.correlationId(), remaining);
            }
        } else {
            status = RunStatus.READY;
            phase = RuntimePhase.INGEST;
        }
        for (RuntimeCommand command : List.copyOf(commands != null ? commands : List.of())) {
            if (command instanceof RuntimeCommand.CancelAtBoundary boundary) {
                cancel = true;
                if (boundary.unresolvedEffectIds().isEmpty()) {
                    status = RunStatus.CANCELLED;
                    phase = RuntimePhase.TERMINAL;
                    waitReason = null;
                } else {
                    status = RunStatus.WAITING;
                    phase = RuntimePhase.WAIT;
                    waitReason = new WaitReason.ExternalEventWait(boundary.unresolvedEffectIds().get(0),
                            "EffectConfirmation", Map.of("effectIds", boundary.unresolvedEffectIds()));
                }
            } else if (command instanceof RuntimeCommand.Suspend suspend) {
                status = RunStatus.WAITING;
                phase = RuntimePhase.WAIT;
                waitReason = suspend.reason();
            } else if (command instanceof RuntimeCommand.Transition transition) {
                status = RunStatus.READY;
                phase = transition.phase();
                waitReason = null;
            } else if (command instanceof RuntimeCommand.Fail fail
                    && event instanceof ExternalEvent.RuntimeLimitReached) {
                status = RunStatus.FAILED;
                phase = RuntimePhase.TERMINAL;
                waitReason = null;
                failureCode = fail.code();
                failureMessage = fail.message();
            } else {
                throw new IllegalArgumentException("unsupported external-event command: " + command);
            }
        }
        return new RunState(RunState.SCHEMA_VERSION, RunState.GRAPH_VERSION, current.spec(),
                status, phase, current.superstep(), current.commitSequence() + 1,
                current.channels(), waitReason, cancel, failureCode, failureMessage,
                current.childRunIds(), current.artifactReferences());
    }

    private static boolean isRejectedForkConfirmation(RunState current, ExternalEvent event) {
        if (!(current.waitReason() instanceof WaitReason.ApprovalWait wait)
                || !wait.approvalRequestId().startsWith("execute-fork:")
                || !(event instanceof ExternalEvent.ApprovalDecision)) return false;
        return !Boolean.parseBoolean(String.valueOf(event.payload().getOrDefault("approved", false)));
    }

    private static RuntimePhase defaultNext(RuntimePhase phase) {
        return switch (phase) {
            case INGEST -> RuntimePhase.CONTEXT;
            case CONTEXT, COMPACT -> RuntimePhase.MODEL;
            case MODEL -> RuntimePhase.TERMINAL;
            case TOOLS, DELEGATE -> RuntimePhase.CONTEXT;
            case WAIT -> RuntimePhase.INGEST;
            case TERMINAL -> throw new IllegalStateException("terminal state cannot execute");
        };
    }

    private static void validateTransition(RuntimePhase from, RuntimePhase to) {
        boolean valid = switch (from) {
            case INGEST -> to == RuntimePhase.CONTEXT;
            case CONTEXT -> to == RuntimePhase.MODEL || to == RuntimePhase.COMPACT;
            case MODEL -> to == RuntimePhase.TOOLS || to == RuntimePhase.COMPACT
                    || to == RuntimePhase.DELEGATE || to == RuntimePhase.TERMINAL;
            case TOOLS -> to == RuntimePhase.CONTEXT || to == RuntimePhase.MODEL;
            case COMPACT -> to == RuntimePhase.CONTEXT || to == RuntimePhase.MODEL;
            case DELEGATE -> to == RuntimePhase.CONTEXT || to == RuntimePhase.MODEL;
            case WAIT -> to == RuntimePhase.INGEST;
            case TERMINAL -> false;
        };
        if (!valid) throw new IllegalArgumentException("invalid fixed-graph transition: " + from + " -> " + to);
    }

    private static void apply(Map<String, Object> channels, ChannelWrite write) {
        switch (write.operation()) {
            case SET -> channels.put(write.channel(), write.value());
            case REMOVE -> channels.remove(write.channel());
            case APPEND -> {
                List<Object> values = new ArrayList<>();
                Object previous = channels.get(write.channel());
                if (previous instanceof List<?> list) values.addAll(list);
                else if (previous != null) throw new IllegalArgumentException("APPEND target is not a list: " + write.channel());
                if (write.value() instanceof List<?> list) values.addAll(list); else values.add(write.value());
                channels.put(write.channel(), List.copyOf(values));
            }
            case MERGE -> {
                Map<String, Object> values = new LinkedHashMap<>();
                Object previous = channels.get(write.channel());
                if (previous instanceof Map<?, ?> map) map.forEach((key, value) -> values.put(String.valueOf(key), value));
                else if (previous != null) throw new IllegalArgumentException("MERGE target is not a map: " + write.channel());
                if (!(write.value() instanceof Map<?, ?> map)) throw new IllegalArgumentException("MERGE value is not a map: " + write.channel());
                map.forEach((key, value) -> values.put(String.valueOf(key), value));
                channels.put(write.channel(), Map.copyOf(values));
            }
        }
    }

    private static List<String> references(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> refs = new ArrayList<>();
        for (Object item : values) {
            String ref = String.valueOf(item).trim();
            if (!ref.isBlank()) refs.add(ref);
        }
        return List.copyOf(refs);
    }

}
