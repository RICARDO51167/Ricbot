package ricbot.integration.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OpenAI Responses API 辅助类
 */
public final class OpenAIResponsesSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAIResponsesSupport() {
    }

    @SuppressWarnings("unchecked")
    public static LLMResponse consumeSSE(
            java.util.stream.Stream<String> lines,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) {
        StringBuilder fullContent = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCallBuilders = new HashMap<>();
        Map<String, Integer> usage = new LinkedHashMap<>();
        String[] finishReasonArr = {"stop"};

        lines.forEach(line -> {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("data: ")) {
                return;
            }

            String data = trimmed.substring(6).trim();
            if ("[DONE]".equals(data)) {
                return;
            }

            try {
                Map<String, Object> chunk = MAPPER.readValue(data, new TypeReference<>() {});
                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                    if (delta != null) {
                        Object contentObj = delta.get("content");
                        String content = extractDeltaText(contentObj);
                        if (content != null && !content.isEmpty()) {
                            fullContent.append(content);
                            if (onDelta != null) {
                                try {
                                    onDelta.handle(content);
                                } catch (Exception ignored) {}
                            }
                        }

                        Object toolCallsObj = delta.get("tool_calls");
                        if (toolCallsObj instanceof List<?> list) {
                            for (Object item : list) {
                                if (item instanceof Map<?, ?> rawToolCall) {
                                    Object indexObj = rawToolCall.get("index");
                                    Integer idx = indexObj instanceof Number n ? n.intValue() : null;
                                    if (idx == null) {
                                        continue;
                                    }
                                    ToolCallBuilder b = toolCallBuilders.computeIfAbsent(idx, k -> new ToolCallBuilder());
                                    Object idObj = rawToolCall.get("id");
                                    if (idObj != null && !String.valueOf(idObj).isBlank()) {
                                        b.id = String.valueOf(idObj);
                                    }
                                    Object fnObj = rawToolCall.get("function");
                                    if (fnObj instanceof Map<?, ?> fn) {
                                        Object nameObj = fn.get("name");
                                        if (nameObj != null && !String.valueOf(nameObj).isBlank()) {
                                            b.name = String.valueOf(nameObj);
                                        }
                                        Object argsObj = fn.get("arguments");
                                        if (argsObj != null) {
                                            b.arguments.append(String.valueOf(argsObj));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (choice.get("finish_reason") != null) {
                        finishReasonArr[0] = String.valueOf(choice.get("finish_reason"));
                    }
                }

                Map<String, Object> usageRaw = (Map<String, Object>) chunk.get("usage");
                if (usageRaw != null) {
                    usage.put("prompt_tokens", toInt(usageRaw.get("prompt_tokens")));
                    usage.put("completion_tokens", toInt(usageRaw.get("completion_tokens")));
                    usage.put("total_tokens", toInt(usageRaw.get("total_tokens")));
                }

            } catch (Exception ignored) {
            }
        });

        List<ToolCallRequest> toolCalls = finalizeToolCalls(toolCallBuilders);
        LLMResponse resp = new LLMResponse()
                .setContent(fullContent.toString())
                .setFinishReason(finishReasonArr[0])
                .setToolCalls(toolCalls)
                .setUsage(usage);

        if (onEnd != null) {
            try {
                onEnd.handle(resp);
            } catch (Exception ignored) {}
        }
        return resp;
    }

    public static LLMResponse consumeSSE(
            InputStream inputStream,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return consumeSSE(reader.lines(), onDelta, onEnd);
        }
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    private static String extractDeltaText(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String s) {
            return s;
        }
        if (contentObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object type = m.get("type");
                    if (type != null && !"text".equals(String.valueOf(type))) {
                        continue;
                    }
                    Object text = m.get("text");
                    if (text != null) {
                        sb.append(String.valueOf(text));
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static List<ToolCallRequest> finalizeToolCalls(Map<Integer, ToolCallBuilder> builders) {
        if (builders == null || builders.isEmpty()) {
            return List.of();
        }
        List<Integer> keys = new ArrayList<>(builders.keySet());
        keys.sort(Integer::compareTo);

        List<ToolCallRequest> out = new ArrayList<>();
        for (Integer idx : keys) {
            ToolCallBuilder b = builders.get(idx);
            if (b == null || b.name == null || b.name.isBlank()) {
                continue;
            }
            String id = b.id != null && !b.id.isBlank() ? b.id : "call_" + idx;
            Map<String, Object> args = new LinkedHashMap<>();
            String rawArgs = b.arguments.toString();
            if (!rawArgs.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = MAPPER.readValue(rawArgs, Map.class);
                    if (parsed != null) {
                        args = parsed;
                    }
                } catch (Exception ignored) {
                }
            }
            out.add(new ToolCallRequest(id, b.name, args));
        }
        return out;
    }

    private static class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }
}
