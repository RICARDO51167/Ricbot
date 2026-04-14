package ricbot.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ricbot.llm.openai.ResponsesParsing;

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

    public static LLMResponse consumeSse(
            InputStream inputStream,
            LLMProvider.StreamDeltaHandler onDelta
    ) throws Exception {
        ResponsesParsing.SseConsumeResult result = ResponsesParsing.consumeSse(
                inputStream,
                onDelta
        );

        return new LLMResponse()
                .setContent(result.content())
                .setToolCalls(result.toolCalls())
                .setFinishReason(result.finishReason());
    }

    /**
     * 对应 Python: convert_messages(...)
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
     * 消费 OpenAI 标准 SSE 流 (基于 Stream<String>)
     */
    @SuppressWarnings("unchecked")
    public static LLMResponse consumeSSE(
            java.util.stream.Stream<String> lines,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) {
        StringBuilder fullContent = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
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
                        String content = (String) delta.get("content");
                        if (content != null && !content.isEmpty()) {
                            fullContent.append(content);
                            if (onDelta != null) {
                                try {
                                    onDelta.handle(content);
                                } catch (Exception ignored) {}
                            }
                        }

                        // OpenAI SSE 的 tool_calls 是增量的，这里暂不进行深度拼装，
                        // 通常在 StreamEndHandler 中处理最终状态，或者在这里累加。
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

        LLMResponse resp = new LLMResponse()
                .setContent(fullContent.toString())
                .setFinishReason(finishReasonArr[0])
                .setUsage(usage);

        if (onEnd != null) {
            try {
                onEnd.handle(resp);
            } catch (Exception ignored) {}
        }
        return resp;
    }

    /**
     * 消费基于 InputStream 的 SSE 流
     */
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
}
