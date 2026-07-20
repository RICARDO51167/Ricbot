package ricbot.domain.agent;

import ricbot.infra.common.JsonMapUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed, versioned snapshot of the recoverable portion of an agent run.
 *
 * <p>The snapshot deliberately stores pending tool calls without automatically
 * replaying them. A process may have exited after a side effect completed but
 * before its result was persisted, so blind replay would violate at-most-once
 * safety for write and command tools.</p>
 */
public record RunCheckpoint(
        int schemaVersion,
        String checkpointId,
        String runId,
        String journalRunId,
        String sessionKey,
        long journalSequence,
        int sessionMessageCount,
        int iteration,
        RunCheckpointPhase phase,
        AgentNodeState nodeState,
        List<Map<String, Object>> runMessages,
        Map<String, Object> assistantMessage,
        List<Map<String, Object>> completedToolResults,
        List<Map<String, Object>> pendingToolCalls,
        Map<String, Object> taskState,
        String interruptionReason,
        Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunCheckpoint {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run checkpoint schema version: " + schemaVersion);
        }
        checkpointId = requireText(checkpointId, "checkpointId");
        runId = clean(runId);
        journalRunId = clean(journalRunId);
        if (journalRunId.isBlank()) {
            journalRunId = runId;
        }
        sessionKey = requireText(sessionKey, "sessionKey");
        journalSequence = Math.max(0, journalSequence);
        sessionMessageCount = Math.max(0, sessionMessageCount);
        iteration = Math.max(0, iteration);
        phase = Objects.requireNonNullElse(phase, RunCheckpointPhase.MODEL_RESPONSE_RECEIVED);
        nodeState = nodeState != null ? nodeState : AgentNodeState.initial();
        runMessages = immutableMapList(runMessages);
        assistantMessage = immutableMap(assistantMessage);
        completedToolResults = immutableMapList(completedToolResults);
        pendingToolCalls = immutableMapList(pendingToolCalls);
        taskState = immutableMap(taskState);
        interruptionReason = clean(interruptionReason);
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static RunCheckpoint fromPayload(
            String sessionKey,
            Map<String, Object> payload,
            Map<String, Object> taskState
    ) {
        Map<String, Object> source = payload != null ? payload : Map.of();
        String runId = clean(source.get("run_id"));
        String journalRunId = clean(source.get("journal_run_id"));
        if (journalRunId.isBlank()) {
            journalRunId = runId;
        }
        int iteration = integer(source.get("iteration"), 0);
        long journalSequence = longInteger(source.get("journal_sequence"), 0);
        int sessionMessageCount = integer(source.get("session_message_count"), 0);
        RunCheckpointPhase phase = RunCheckpointPhase.from(source.get("phase"));
        String checkpointId = clean(source.get("checkpoint_id"));
        if (checkpointId.isBlank()) {
            checkpointId = !journalRunId.isBlank()
                    ? journalRunId + ":" + iteration + ":" + phase.name()
                    : UUID.randomUUID().toString();
        }
        Map<String, Object> effectiveTaskState = !map(source.get("task_state")).isEmpty()
                ? map(source.get("task_state"))
                : taskState;
        return new RunCheckpoint(
                CURRENT_SCHEMA_VERSION,
                checkpointId,
                runId,
                journalRunId,
                sessionKey,
                journalSequence,
                sessionMessageCount,
                iteration,
                phase,
                nodeState(source.get("node_state"), iteration, phase),
                mapList(source.get("run_messages")),
                map(source.get("assistant_message")),
                mapList(source.get("completed_tool_results")),
                mapList(source.get("pending_tool_calls")),
                effectiveTaskState,
                clean(source.get("interruption_reason")),
                instant(source.get("updated_at"), Instant.now())
        );
    }

    /**
     * Compatibility shape consumed by the existing session recovery path.
     */
    public Map<String, Object> toSessionPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", schemaVersion);
        payload.put("checkpoint_id", checkpointId);
        payload.put("run_id", runId);
        payload.put("journal_run_id", journalRunId);
        payload.put("session_key", sessionKey);
        payload.put("journal_sequence", journalSequence);
        payload.put("session_message_count", sessionMessageCount);
        payload.put("iteration", iteration);
        payload.put("phase", phase.name());
        payload.put("node_state", Map.of(
                "schema_version", nodeState.schemaVersion(),
                "node", nodeState.node().name(),
                "iteration", nodeState.iteration(),
                "transition", nodeState.transition(),
                "terminal", nodeState.terminal(),
                "updated_at", nodeState.updatedAt().toString()
        ));
        payload.put("run_messages", mutableMapList(runMessages));
        payload.put("assistant_message", mutableMap(assistantMessage));
        payload.put("completed_tool_results", mutableMapList(completedToolResults));
        payload.put("pending_tool_calls", mutableMapList(pendingToolCalls));
        payload.put("task_state", mutableMap(taskState));
        if (!interruptionReason.isBlank()) {
            payload.put("interruption_reason", interruptionReason);
        }
        payload.put("updated_at", updatedAt.toString());
        return payload;
    }

    public RunCheckpoint withInterruptionReason(String reason) {
        return new RunCheckpoint(
                schemaVersion,
                checkpointId,
                runId,
                journalRunId,
                sessionKey,
                journalSequence,
                sessionMessageCount,
                iteration,
                phase,
                nodeState,
                runMessages,
                assistantMessage,
                completedToolResults,
                pendingToolCalls,
                taskState,
                reason,
                Instant.now()
        );
    }

    private static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> value ? JsonMapUtils.copyObjectMap(value) : Map.of();
    }

    private static List<Map<String, Object>> mapList(Object raw) {
        return JsonMapUtils.asObjectMapList(raw);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        // Tool-call assistant messages legitimately use a null content value.
        // Map.copyOf rejects nulls, while an unmodifiable LinkedHashMap keeps
        // the JSON-compatible value and still protects the snapshot itself.
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), immutableJsonValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Map<String, Object>> immutableMapList(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            if (item != null) {
                copy.add(immutableMap(item));
            }
        }
        return List.copyOf(copy);
    }

    private static Map<String, Object> mutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : (source != null ? source : Map.<String, Object>of()).entrySet()) {
            copy.put(entry.getKey(), mutableJsonValue(entry.getValue()));
        }
        return copy;
    }

    private static List<Map<String, Object>> mutableMapList(List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : source != null ? source : List.<Map<String, Object>>of()) {
            copy.add(mutableMap(item));
        }
        return copy;
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), immutableJsonValue(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> rawList) {
            List<Object> copy = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                copy.add(immutableJsonValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static Object mutableJsonValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), mutableJsonValue(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof List<?> rawList) {
            List<Object> copy = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                copy.add(mutableJsonValue(item));
            }
            return copy;
        }
        return value;
    }

    private static int integer(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw != null ? Integer.parseInt(String.valueOf(raw)) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static AgentNodeState nodeState(Object raw, int iteration, RunCheckpointPhase phase) {
        Map<String, Object> value = map(raw);
        AgentNodeType fallback = phase == RunCheckpointPhase.TOOLS_COMPLETED
                ? AgentNodeType.TOOLS : AgentNodeType.MODEL;
        AgentNodeType node;
        try {
            node = AgentNodeType.valueOf(clean(value.get("node")).toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            node = fallback;
        }
        return new AgentNodeState(
                integer(value.get("schema_version"), 1),
                node,
                integer(value.get("iteration"), iteration),
                longInteger(value.get("transition"), 0),
                Boolean.TRUE.equals(value.get("terminal")),
                instant(value.get("updated_at"), Instant.now())
        );
    }

    private static long longInteger(Object raw, long fallback) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw != null ? Long.parseLong(String.valueOf(raw)) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Instant instant(Object raw, Instant fallback) {
        String value = clean(raw);
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String clean(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String requireText(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }
}
