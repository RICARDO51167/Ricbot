package ricbot.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.tool.api.ToolRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves pending tool calls against the durable invocation ledger.
 *
 * <p>Automatic execution is intentionally restricted to calls that were
 * persisted as read-only, are still classified as read-only by the current
 * registry, and whose checkpoint arguments match the persisted digest.</p>
 */
public final class RunRecoveryCoordinator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunJournalStore journalStore;
    private final ToolRegistry tools;

    public RunRecoveryCoordinator(RunJournalStore journalStore, ToolRegistry tools) {
        this.journalStore = journalStore != null ? journalStore : RunJournalStore.disabled();
        this.tools = tools;
    }

    public Map<String, ToolRecoveryResolution> resolve(
            Map<String, Object> checkpoint,
            Optional<RunState> interruptedRun,
            List<?> pendingToolCalls
    ) {
        if (checkpoint == null || interruptedRun == null || interruptedRun.isEmpty()
                || pendingToolCalls == null || pendingToolCalls.isEmpty()) {
            return Map.of();
        }
        RunState state = interruptedRun.orElseThrow();
        String checkpointRunId = text(checkpoint.get("journal_run_id"));
        if (checkpointRunId.isBlank()) {
            checkpointRunId = text(checkpoint.get("run_id"));
        }
        if (checkpointRunId.isBlank() || !checkpointRunId.equals(state.runId())) {
            return Map.of();
        }

        Map<String, ToolInvocationRecord> invocations = latestByToolCallId(state);
        Map<String, ToolRecoveryResolution> resolutions = new LinkedHashMap<>();
        boolean retried = false;
        for (Object item : pendingToolCalls) {
            if (!(item instanceof Map<?, ?> rawToolCall)) {
                continue;
            }
            Map<String, Object> toolCall = copy(rawToolCall);
            String toolCallId = text(toolCall.get("id"));
            if (toolCallId.isBlank()) {
                continue;
            }
            ToolInvocationRecord invocation = invocations.get(toolCallId);
            if (invocation == null) {
                resolutions.put(toolCallId, unresolved(toolCallId, ToolRecoveryAction.UNTRACKED,
                        "tool invocation is absent from the durable ledger"));
                continue;
            }
            if (invocation.status().finished() && !invocation.resultMessage().isEmpty()) {
                resolutions.put(toolCallId, new ToolRecoveryResolution(
                        toolCallId,
                        ToolRecoveryAction.REUSE_COMPLETED,
                        invocation.resultMessage(),
                        "durably completed result reused"
                ));
                continue;
            }

            ParsedToolCall parsed = parse(toolCall);
            String unsafeReason = automaticRetryBlockReason(invocation, parsed);
            if (!unsafeReason.isBlank()) {
                resolutions.put(toolCallId, unresolved(
                        toolCallId,
                        ToolRecoveryAction.REQUIRE_CONFIRMATION,
                        unsafeReason
                ));
                continue;
            }

            ToolRecoveryResolution resolution = retryReadOnly(state.sessionKey(), invocation, parsed);
            resolutions.put(toolCallId, resolution);
            state = journalStore.load(state.sessionKey(), state.runId()).orElseThrow();
            invocations.put(toolCallId, state.toolInvocations().get(invocation.invocationId()));
            retried = true;
        }

        if (retried) {
            RunState current = journalStore.load(state.sessionKey(), state.runId()).orElseThrow();
            journalStore.append(RunEvent.create(
                    current.lastSequence() + 1,
                    current.runId(),
                    current.sessionKey(),
                    current.iteration(),
                    RunEventType.RUN_PAUSED,
                    RunStatus.PAUSED,
                    null,
                    Map.of("reason", "recovered_read_only_tools")
            ));
        }
        return Map.copyOf(resolutions);
    }

    private ToolRecoveryResolution retryReadOnly(
            String sessionKey,
            ToolInvocationRecord invocation,
            ParsedToolCall parsed
    ) {
        RunState current = journalStore.load(sessionKey, invocation.runId()).orElseThrow();
        ToolInvocationRecord retrying = invocation.retrying();
        journalStore.append(RunEvent.create(
                current.lastSequence() + 1,
                current.runId(),
                current.sessionKey(),
                invocation.iteration(),
                RunEventType.TOOL_RETRY_STARTED,
                RunStatus.TOOL_RUNNING,
                retrying,
                Map.of("reason", "automatic_read_only_recovery")
        ));

        Object result;
        boolean succeeded;
        String failure = "";
        try {
            result = tools.execute(parsed.name(), parsed.arguments());
            succeeded = isSuccess(result);
            if (!succeeded) {
                failure = detail(result);
            }
        } catch (Exception e) {
            succeeded = false;
            failure = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            result = Map.of("error", failure, "exception", e.getClass().getName());
        }

        Map<String, Object> resultMessage = toolMessage(
                invocation.toolCallId(),
                invocation.toolName(),
                result
        );
        ToolInvocationRecord completed = retrying.completed(resultMessage, succeeded, failure);
        RunState afterStart = journalStore.load(sessionKey, invocation.runId()).orElseThrow();
        journalStore.append(RunEvent.create(
                afterStart.lastSequence() + 1,
                afterStart.runId(),
                afterStart.sessionKey(),
                invocation.iteration(),
                succeeded ? RunEventType.TOOL_RETRY_COMPLETED : RunEventType.TOOL_RETRY_FAILED,
                RunStatus.TOOL_RUNNING,
                completed,
                Map.of("recovered_at", Instant.now().toString())
        ));
        return new ToolRecoveryResolution(
                invocation.toolCallId(),
                ToolRecoveryAction.RETRY_READ_ONLY,
                resultMessage,
                succeeded ? "read-only tool retried safely" : "read-only retry returned an error"
        );
    }

    private String automaticRetryBlockReason(ToolInvocationRecord invocation, ParsedToolCall parsed) {
        if (invocation.status() != ToolInvocationStatus.UNKNOWN) {
            return "tool outcome is not eligible for automatic retry: " + invocation.status();
        }
        if (!invocation.readOnly()) {
            return "side-effect tool outcome is unknown and requires confirmation";
        }
        if (tools == null || parsed.name().isBlank()) {
            return "tool registry or tool name is unavailable";
        }
        ToolRegistry.ToolPolicy currentPolicy = tools.policyFor(parsed.name());
        if (!currentPolicy.readOnly()) {
            return "tool is no longer classified as read-only";
        }
        if (!invocation.toolName().equals(parsed.name())) {
            return "checkpoint tool name does not match the durable ledger";
        }
        String digest = ToolInvocationRecord.argumentsDigest(parsed.arguments());
        if (!invocation.argumentsDigest().equals(digest)) {
            return "checkpoint arguments do not match the durable ledger";
        }
        return "";
    }

    private static Map<String, ToolInvocationRecord> latestByToolCallId(RunState state) {
        Map<String, ToolInvocationRecord> result = new LinkedHashMap<>();
        state.toolInvocations().values().stream()
                .sorted(java.util.Comparator.comparingInt(ToolInvocationRecord::iteration))
                .forEach(invocation -> result.put(invocation.toolCallId(), invocation));
        return result;
    }

    private static ParsedToolCall parse(Map<String, Object> toolCall) {
        Map<String, Object> function = toolCall.get("function") instanceof Map<?, ?> raw
                ? copy(raw)
                : Map.of();
        String name = text(function.get("name"));
        Object argumentsValue = function.get("arguments");
        if (argumentsValue instanceof Map<?, ?> rawArguments) {
            return new ParsedToolCall(name, copy(rawArguments));
        }
        if (argumentsValue instanceof String json && !json.isBlank()) {
            try {
                Map<String, Object> arguments = MAPPER.readValue(json, new TypeReference<>() { });
                return new ParsedToolCall(name, arguments != null ? arguments : Map.of());
            } catch (Exception ignored) {
                return new ParsedToolCall(name, Map.of("_invalid_json", json));
            }
        }
        return new ParsedToolCall(name, Map.of());
    }

    private static boolean isSuccess(Object result) {
        if (result instanceof Map<?, ?> map) {
            if (map.get("ok") instanceof Boolean ok) {
                return ok;
            }
            return map.get("error") == null;
        }
        if (result instanceof String value) {
            String lower = value.trim().toLowerCase(Locale.ROOT);
            return !(lower.startsWith("error") || lower.startsWith("err") || lower.startsWith("错误"));
        }
        return true;
    }

    private static String detail(Object result) {
        if (result instanceof Map<?, ?> map && map.get("error") != null) {
            return String.valueOf(map.get("error"));
        }
        return result != null ? String.valueOf(result) : "tool returned an error";
    }

    private static Map<String, Object> toolMessage(String toolCallId, String name, Object result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("name", name);
        message.put("content", toolContent(result));
        message.put("timestamp", Instant.now().toString());
        return message;
    }

    private static String toolContent(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception ignored) {
            return String.valueOf(result);
        }
    }

    private static ToolRecoveryResolution unresolved(
            String toolCallId,
            ToolRecoveryAction action,
            String reason
    ) {
        return new ToolRecoveryResolution(toolCallId, action, Map.of(), reason);
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null) {
                    copy.put(String.valueOf(key), value);
                }
            });
        }
        return copy;
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record ParsedToolCall(String name, Map<String, Object> arguments) {
    }
}
