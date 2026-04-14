package ricbot.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OpenAI Responses API 相关公共辅助类
 *
 * 主要目标：
 * 1. convert_messages
 * 2. convert_tools
 * 3. parse_response_output
 * 4. consume_sse / consume_sdk_stream
 */
public final class OpenAIResponsesSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAIResponsesSupport() {
    }

    /**
     * 对应 Python: convert_messages(...)
     *
     * 返回:
     * - instructions
     * - input items
     */
    public static Map<String, Object> convertMessages(List<Map<String, Object>> messages) {
        String instructions = null;
        List<Map<String, Object>> inputItems = new ArrayList<>();

        if (messages == null) {
            return Map.of(
                    "instructions", null,
                    "input", inputItems
            );
        }

        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            Object content = msg.get("content");

            if ("system".equals(role)) {
                if (content instanceof String s) {
                    instructions = s;
                } else if (content != null) {
                    instructions = String.valueOf(content);
                }
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);

            if (content instanceof String s) {
                item.put("content", List.of(Map.of(
                        "type", "input_text",
                        "text", s
                )));
            } else if (content instanceof List<?> list) {
                List<Object> blocks = new ArrayList<>();
                for (Object block : list) {
                    if (block instanceof Map<?, ?> raw) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            b.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        blocks.add(b);
                    } else {
                        blocks.add(Map.of("type", "input_text", "text", String.valueOf(block)));
                    }
                }
                item.put("content", blocks);
            } else if (content == null) {
                item.put("content", List.of());
            } else {
                item.put("content", List.of(Map.of(
                        "type", "input_text",
                        "text", String.valueOf(content)
                )));
            }

            inputItems.add(item);
        }

        return Map.of(
                "instructions", instructions,
                "input", inputItems
        );
    }

    /**
     * 对应 Python: convert_tools(...)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> function = tool.get("function") instanceof Map<?, ?> raw
                    ? (Map<String, Object>) raw
                    : tool;

            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("type", "function");
            converted.put("name", function.getOrDefault("name", ""));
            converted.put("description", function.getOrDefault("description", ""));
            converted.put("parameters", function.getOrDefault("parameters", Map.of(
                    "type", "object",
                    "properties", Map.of()
            )));
            result.add(converted);
        }
        return result;
    }

    /**
     * 对应 Python: parse_response_output(response)
     *
     * 这里按 Responses API 常见结构做解析。
     */
    @SuppressWarnings("unchecked")
    public static LLMResponse parseResponseOutput(Object response) {
        if (response == null) {
            return new LLMResponse().setContent("");
        }

        Map<String, Object> root;
        if (response instanceof Map<?, ?> map) {
            root = (Map<String, Object>) map;
        } else {
            root = MAPPER.convertValue(response, new TypeReference<>() {});
        }

        StringBuilder text = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        String finishReason = "stop";

        Object outputObj = root.get("output");
        if (outputObj instanceof List<?> outputList) {
            for (Object itemObj : outputList) {
                Map<String, Object> item = MAPPER.convertValue(itemObj, new TypeReference<Map<String, Object>>() {});
                String type = String.valueOf(item.getOrDefault("type", ""));

                if ("message".equals(type)) {
                    Object contentObj = item.get("content");
                    if (contentObj instanceof List<?> contentList) {
                        for (Object c : contentList) {
                            Map<String, Object> part = MAPPER.convertValue(c, new TypeReference<Map<String, Object>>() {});
                            String pType = String.valueOf(part.getOrDefault("type", ""));
                            if ("output_text".equals(pType) || "text".equals(pType)) {
                                Object t = part.get("text");
                                if (t != null) {
                                    text.append(String.valueOf(t));
                                }
                            }
                        }
                    }
                }

                if ("function_call".equals(type)) {
                    String id = String.valueOf(item.getOrDefault("call_id", item.getOrDefault("id", "")));
                    String name = String.valueOf(item.getOrDefault("name", ""));
                    Map<String, Object> args = new LinkedHashMap<>();

                    Object arguments = item.get("arguments");
                    if (arguments instanceof String s && !s.isBlank()) {
                        try {
                            args = MAPPER.readValue(s, new TypeReference<>() {});
                        } catch (Exception ignored) {
                        }
                    } else if (arguments instanceof Map<?, ?> mapArgs) {
                        args = new LinkedHashMap<>((Map<String, Object>) mapArgs);
                    }

                    toolCalls.add(new ToolCallRequest(id, name, args));
                }
            }
        }

        Map<String, Integer> usage = new LinkedHashMap<>();
        Object usageObj = root.get("usage");
        if (usageObj instanceof Map<?, ?> usageMap) {
            Object pt = usageMap.get("prompt_tokens");
            Object ct = usageMap.get("completion_tokens");
            Object tt = usageMap.get("total_tokens");
            usage.put("prompt_tokens", pt instanceof Number n ? n.intValue() : 0);
            usage.put("completion_tokens", ct instanceof Number n ? n.intValue() : 0);
            usage.put("total_tokens", tt instanceof Number n ? n.intValue() : 0);
        }

        return new LLMResponse()
                .setContent(text.toString())
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason)
                .setUsage(usage);
    }

    /**
     * 对应 Python: consume_sse(...)
     */
    public static LLMResponse consumeSse(
            InputStream inputStream,
            LLMProvider.StreamDeltaHandler onDelta
    ) throws Exception {
        StringBuilder content = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        String finishReason = "stop";

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isBlank() || "[DONE]".equals(payload)) {
                    continue;
                }

                Map<String, Object> event;
                try {
                    event = MAPPER.readValue(payload, new TypeReference<>() {});
                } catch (Exception e) {
                    continue;
                }

                String type = String.valueOf(event.getOrDefault("type", ""));
                if ("response.output_text.delta".equals(type)) {
                    String delta = String.valueOf(event.getOrDefault("delta", ""));
                    content.append(delta);
                    if (onDelta != null && !delta.isEmpty()) {
                        onDelta.onDelta(delta);
                    }
                } else if ("response.function_call_arguments.done".equals(type)) {
                    String id = String.valueOf(event.getOrDefault("item_id", ""));
                    String name = String.valueOf(event.getOrDefault("name", ""));
                    Map<String, Object> args = new LinkedHashMap<>();

                    Object argObj = event.get("arguments");
                    if (argObj instanceof String s && !s.isBlank()) {
                        try {
                            args = MAPPER.readValue(s, new TypeReference<>() {});
                        } catch (Exception ignored) {
                        }
                    }

                    toolCalls.add(new ToolCallRequest(id, name, args));
                }
            }
        }

        return new LLMResponse()
                .setContent(content.toString())
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason);
    }

    /**
     * 对应 Python: consume_sdk_stream(...)
     *
     * 这里先做一个兼容壳，底层如果已经是 InputStream 或 iterator 都能自己扩展。
     */
    public static LLMResponse consumeSdkStream(
            Object stream,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) throws Exception {
        if (stream instanceof InputStream is) {
            LLMResponse response = consumeSse(is, onDelta);
            if (onEnd != null) {
                onEnd.onEnd(false);
            }
            return response;
        }

        // TODO:
        // 如果后面接真正 SDK stream 对象，这里再细化。
        if (onEnd != null) {
            onEnd.onEnd(false);
        }
        return new LLMResponse().setContent("");
    }
}