package ricbot.domain.eval;

import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class EvalReplayProvider extends LLMProvider {
    private final List<Map<String, Object>> modelCalls;
    private final AtomicInteger index = new AtomicInteger();
    private final List<String> requestMismatches = new ArrayList<>();

    public EvalReplayProvider(List<Map<String, Object>> modelCalls) {
        super("replay", "replay://local");
        this.modelCalls = modelCalls != null ? new ArrayList<>(modelCalls) : List.of();
        setDefaultModel("replay-model");
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
    ) {
        int current = index.getAndIncrement();
        if (current >= modelCalls.size()) {
            throw new IllegalStateException("replay_exhausted: no recorded model response for call " + (current + 1));
        }

        Map<String, Object> call = modelCalls.get(current);
        validateRecordedRequest(current, call, messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice);
        String status = String.valueOf(call.getOrDefault("status", "ok"));
        if ("error".equals(status)) {
            Object rawError = call.get("error");
            throw new IllegalStateException("replay_recorded_error: " + String.valueOf(rawError));
        }

        Object response = call.get("response");
        if (!(response instanceof Map<?, ?> responseMap)) {
            throw new IllegalStateException("replay_artifact_invalid: model call is missing response object");
        }
        return responseFromMap(responseMap);
    }

    public int consumedCalls() {
        return index.get();
    }

    public int totalCalls() {
        return modelCalls.size();
    }

    public List<String> requestMismatches() {
        return new ArrayList<>(requestMismatches);
    }

    private void validateRecordedRequest(
            int callIndex,
            Map<String, Object> recorded,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice
    ) {
        compare(callIndex, "model", recorded.get("model"), model);
        compare(callIndex, "max_tokens", recorded.get("max_tokens"), maxTokens);
        compare(callIndex, "temperature", recorded.get("temperature"), temperature);
        compare(callIndex, "reasoning_effort", recorded.get("reasoning_effort"), reasoningEffort);
        compare(callIndex, "tool_choice", recorded.get("tool_choice"), toolChoice);
        String recordedWorkspace = workspacePathFromMessages(recorded.get("messages"));
        String actualWorkspace = workspacePathFromMessages(messages);
        compare(callIndex, "messages",
                messageSignature(recorded.get("messages"), recordedWorkspace),
                messageSignature(messages, actualWorkspace));
        compare(callIndex, "tools", toolNames(recorded.get("tools")), toolNames(tools));
    }

    private void compare(int callIndex, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            requestMismatches.add("call " + (callIndex + 1) + " request " + field + " mismatch");
        }
    }

    private Map<String, Object> messageSignature(Object raw, String workspacePath) {
        Map<String, Object> signature = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list)) {
            signature.put("roles", List.of());
            signature.put("last_non_system", Map.of());
            return signature;
        }
        List<String> roles = new ArrayList<>();
        Map<String, Object> lastNonSystem = Map.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> normalized = objectMap(map);
            String role = String.valueOf(normalized.get("role"));
            roles.add(role);
            if (!"system".equals(role)) {
                Map<String, Object> compact = new LinkedHashMap<>();
                compact.put("role", role);
                compact.put("name", normalized.get("name"));
                compact.put("content", normalizeDynamicText(normalized.get("content"), workspacePath));
                compact.put("tool_call_id", normalized.get("tool_call_id"));
                lastNonSystem = compact;
            }
        }
        signature.put("roles", roles);
        signature.put("last_non_system", lastNonSystem);
        return signature;
    }

    private String workspacePathFromMessages(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object content = map.get("content");
            if (content == null) {
                continue;
            }
            String found = workspacePathFromText(String.valueOf(content));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String workspacePathFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            String marker = "你的工作区路径：";
            int markerIndex = trimmed.indexOf(marker);
            if (markerIndex >= 0) {
                return trimmed.substring(markerIndex + marker.length()).trim();
            }
            String dirMarker = "目录: ";
            if (trimmed.startsWith(dirMarker)) {
                return trimmed.substring(dirMarker.length()).trim();
            }
        }
        return null;
    }

    private Object normalizeDynamicText(Object raw, String workspacePath) {
        if (!(raw instanceof String text)) {
            return raw;
        }
        if (workspacePath == null || workspacePath.isBlank()) {
            return normalizeDynamicToolText(text);
        }
        return normalizeDynamicToolText(text.replace(workspacePath, "<workspace>"));
    }

    private String normalizeDynamicToolText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text.replaceAll("启用=(true|false)", "启用=<bool>");
    }

    private List<String> toolNames(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object function = map.get("function");
                if (function instanceof Map<?, ?> functionMap && functionMap.get("name") != null) {
                    out.add(String.valueOf(functionMap.get("name")));
                } else if (map.get("name") != null) {
                    out.add(String.valueOf(map.get("name")));
                }
            }
        }
        return out;
    }

    private static LLMResponse responseFromMap(Map<?, ?> raw) {
        LLMResponse response = new LLMResponse();
        response.setContent(stringOrNull(raw.get("content")));
        response.setFinishReason(stringOrDefault(raw.get("finish_reason"), "stop"));
        response.setUsage(usageMap(raw.get("usage")));
        response.setRetryAfter(doubleOrNull(raw.get("retry_after")));
        response.setReasoningContent(stringOrNull(raw.get("reasoning_content")));
        response.setThinkingBlocks(thinkingBlocks(raw.get("thinking_blocks")));
        response.setToolCalls(toolCalls(raw.get("tool_calls")));
        response.setErrorStatusCode(integerOrNull(raw.get("error_status_code")));
        response.setErrorKind(stringOrNull(raw.get("error_kind")));
        response.setErrorType(stringOrNull(raw.get("error_type")));
        response.setErrorCode(stringOrNull(raw.get("error_code")));
        response.setErrorRetryAfterS(doubleOrNull(raw.get("error_retry_after_s")));
        response.setErrorShouldRetry(booleanOrNull(raw.get("error_should_retry")));
        return response;
    }

    private static List<ToolCallRequest> toolCalls(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ToolCallRequest> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            out.add(new ToolCallRequest(
                    stringOrNull(map.get("id")),
                    stringOrNull(map.get("name")),
                    objectMap(map.get("arguments"))
            ));
        }
        return out;
    }

    private static Map<String, Object> objectMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Integer> usageMap(Object raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Number n) {
                out.put(String.valueOf(entry.getKey()), n.intValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> thinkingBlocks(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(objectMap(map));
            }
        }
        return out;
    }

    private static String stringOrNull(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private static String stringOrDefault(Object raw, String fallback) {
        String value = stringOrNull(raw);
        return value != null && !"null".equals(value) ? value : fallback;
    }

    private static Integer integerOrNull(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }

    private static Double doubleOrNull(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : null;
    }

    private static Boolean booleanOrNull(Object raw) {
        return raw instanceof Boolean b ? b : null;
    }
}
