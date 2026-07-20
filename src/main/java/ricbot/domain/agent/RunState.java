package ricbot.domain.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Materialized state rebuilt deterministically from {@link RunEvent}s. */
public record RunState(
        int schemaVersion,
        String runId,
        String sessionKey,
        RunStatus status,
        int iteration,
        long lastSequence,
        Map<String, ToolInvocationRecord> toolInvocations,
        String pauseReason,
        Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunState {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run state schema version: " + schemaVersion);
        }
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        status = Objects.requireNonNull(status, "status");
        iteration = Math.max(0, iteration);
        if (lastSequence <= 0) {
            throw new IllegalArgumentException("lastSequence must be positive");
        }
        toolInvocations = toolInvocations != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(toolInvocations))
                : Map.of();
        pauseReason = pauseReason != null ? pauseReason.trim() : "";
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static RunState from(RunEvent first) {
        Objects.requireNonNull(first, "first");
        if (first.sequence() != 1) {
            throw new IllegalArgumentException("first run event sequence must be 1");
        }
        return new RunState(
                CURRENT_SCHEMA_VERSION,
                first.runId(),
                first.sessionKey(),
                first.status(),
                first.iteration(),
                first.sequence(),
                invocationMap(Map.of(), first.toolInvocation()),
                pauseReason(first),
                first.occurredAt()
        );
    }

    public RunState apply(RunEvent event) {
        Objects.requireNonNull(event, "event");
        if (!runId.equals(event.runId()) || !sessionKey.equals(event.sessionKey())) {
            throw new IllegalArgumentException("run event identity does not match state");
        }
        if (event.sequence() != lastSequence + 1) {
            throw new IllegalArgumentException(
                    "run event sequence gap: expected " + (lastSequence + 1) + " but got " + event.sequence()
            );
        }
        if (!transitionAllowed(status, event.status())) {
            throw new IllegalStateException(
                    "illegal run status transition: " + status + " -> " + event.status()
            );
        }

        Map<String, ToolInvocationRecord> invocations = invocationMap(toolInvocations, event.toolInvocation());
        if (event.type() == RunEventType.RUN_PAUSED) {
            Map<String, ToolInvocationRecord> interrupted = new LinkedHashMap<>();
            invocations.forEach((id, invocation) -> interrupted.put(id, invocation.interruptedUnknown()));
            invocations = interrupted;
        }
        return new RunState(
                schemaVersion,
                runId,
                sessionKey,
                event.status(),
                event.iteration(),
                event.sequence(),
                invocations,
                pauseReason(event),
                event.occurredAt()
        );
    }

    public boolean resumable() {
        return status == RunStatus.PAUSED;
    }

    private static boolean transitionAllowed(RunStatus current, RunStatus next) {
        if (current == next) {
            return true;
        }
        if (current.terminal()) {
            return false;
        }
        if (next == RunStatus.PAUSED || next == RunStatus.FAILED || next == RunStatus.CANCELLED) {
            return true;
        }
        return switch (current) {
            case CREATED -> next == RunStatus.MODEL_RUNNING || next == RunStatus.COMPLETED;
            case MODEL_RUNNING -> next == RunStatus.WAITING_TOOL || next == RunStatus.COMPLETED;
            case WAITING_TOOL -> next == RunStatus.TOOL_RUNNING;
            case TOOL_RUNNING -> next == RunStatus.MODEL_RUNNING;
            case PAUSED -> next == RunStatus.TOOL_RUNNING;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    private static Map<String, ToolInvocationRecord> invocationMap(
            Map<String, ToolInvocationRecord> current,
            ToolInvocationRecord invocation
    ) {
        Map<String, ToolInvocationRecord> copy = new LinkedHashMap<>(current != null ? current : Map.of());
        if (invocation != null) {
            copy.put(invocation.invocationId(), invocation);
        }
        return copy;
    }

    private static String pauseReason(RunEvent event) {
        if (event.type() != RunEventType.RUN_PAUSED) {
            return "";
        }
        Object reason = event.details().get("reason");
        return reason != null ? String.valueOf(reason).trim() : "interrupted";
    }

    private static String requireText(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }
}
