package ricbot.domain.eval;

import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.integration.llm.api.LLMFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvalRecordingProvider extends LLMProvider {
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "api_key",
            "apikey",
            "authorization",
            "bearer",
            "token",
            "secret",
            "password",
            "credential"
    );

    private final LLMProvider delegate;
    private final List<Map<String, Object>> calls = new ArrayList<>();

    public EvalRecordingProvider(LLMProvider delegate) {
        super(delegate != null ? delegate.getApiKey() : null, delegate != null ? delegate.getApiBase() : null);
        if (delegate == null) {
            throw new IllegalArgumentException("delegate provider is required");
        }
        this.delegate = delegate;
        setDefaultModel(delegate.getDefaultModel());
    }

    @Override
    public LLMResponse chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice
    ) throws Exception {
        Instant started = Instant.now();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chat");
        event.put("started_at", started.toString());
        event.put("model", model);
        event.put("max_tokens", maxTokens);
        event.put("temperature", temperature);
        event.put("reasoning_effort", reasoningEffort);
        event.put("tool_choice", toolChoice);
        event.put("messages", messages != null ? deepCopyList(messages) : List.of());
        event.put("tools", tools != null ? deepCopyList(tools) : List.of());

        try {
            LLMResponse response = delegate.chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice);
            event.put("status", "ok");
            event.put("response", responseToMap(response));
            return response;
        } catch (Exception e) {
            event.put("status", "error");
            event.put("error", recordedError(e));
            throw e;
        } finally {
            Instant ended = Instant.now();
            event.put("ended_at", ended.toString());
            event.put("duration_ms", Duration.between(started, ended).toMillis());
            synchronized (calls) {
                calls.add(event);
            }
        }
    }

    @Override
    public LLMResponse chatStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice,
            StreamDeltaHandler onDelta,
            StreamEndHandler onEnd
    ) throws Exception {
        Instant started = Instant.now();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chat_stream");
        event.put("started_at", started.toString());
        event.put("model", model);
        event.put("max_tokens", maxTokens);
        event.put("temperature", temperature);
        event.put("reasoning_effort", reasoningEffort);
        event.put("tool_choice", toolChoice);
        event.put("messages", messages != null ? deepCopyList(messages) : List.of());
        event.put("tools", tools != null ? deepCopyList(tools) : List.of());

        try {
            LLMResponse response = delegate.chatStream(
                    messages,
                    tools,
                    model,
                    maxTokens,
                    temperature,
                    reasoningEffort,
                    toolChoice,
                    onDelta,
                    onEnd
            );
            event.put("status", "ok");
            event.put("response", responseToMap(response));
            return response;
        } catch (Exception e) {
            event.put("status", "error");
            event.put("error", recordedError(e));
            throw e;
        } finally {
            Instant ended = Instant.now();
            event.put("ended_at", ended.toString());
            event.put("duration_ms", Duration.between(started, ended).toMillis());
            synchronized (calls) {
                calls.add(event);
            }
        }
    }

    public void reset() {
        synchronized (calls) {
            calls.clear();
        }
    }

    public List<Map<String, Object>> drainCalls() {
        synchronized (calls) {
            List<Map<String, Object>> out = new ArrayList<>(calls);
            calls.clear();
            return out;
        }
    }

    public boolean isRecordingSmokeProvider() {
        return delegate instanceof EvalSmokeProvider;
    }

    private static Map<String, Object> recordedError(Exception failure) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("class", failure.getClass().getName());
        error.put("message", failure.getMessage() != null ? failure.getMessage() : "");
        if (failure instanceof LLMFailureException typed) {
            error.put("kind", typed.kind().name());
            error.put("status_code", typed.statusCode());
            error.put("retry_after_seconds", typed.retryAfterSeconds());
        }
        return error;
    }

    private static Map<String, Object> responseToMap(LLMResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response == null) {
            return out;
        }
        out.put("content", response.getContent());
        out.put("finish_reason", response.getFinishReason());
        out.put("usage", response.getUsage());
        out.put("retry_after", response.getRetryAfter());
        out.put("reasoning_content", response.getReasoningContent());
        out.put("thinking_blocks", response.getThinkingBlocks());
        out.put("tool_calls", toolCallsToList(response.getToolCalls()));
        out.put("error_status_code", response.getErrorStatusCode());
        out.put("error_kind", response.getErrorKind());
        out.put("error_type", response.getErrorType());
        out.put("error_code", response.getErrorCode());
        out.put("error_retry_after_s", response.getErrorRetryAfterS());
        out.put("error_should_retry", response.getErrorShouldRetry());
        return out;
    }

    private static List<Map<String, Object>> toolCallsToList(List<ToolCallRequest> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolCallRequest call : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.getId());
            item.put("name", call.getName());
            item.put("arguments", call.getArguments() != null ? new LinkedHashMap<>(call.getArguments()) : Map.of());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> deepCopyList(List<Map<String, Object>> input) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : input) {
            out.add(deepCopyMap(item));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            out.put(entry.getKey(), shouldRedact(entry.getKey()) ? "[REDACTED]" : deepCopyValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    String key = String.valueOf(entry.getKey());
                    out.put(key, shouldRedact(key) ? "[REDACTED]" : deepCopyValue(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(deepCopyValue(item));
            }
            return out;
        }
        return value;
    }

    private static boolean shouldRedact(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        for (String part : SENSITIVE_KEY_PARTS) {
            if (normalized.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
