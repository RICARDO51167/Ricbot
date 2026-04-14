package ricbot.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 对应 Python: converters.py
 *
 * 主要目标：
 * 1. 把 Chat Completions messages 转成 Responses API input
 * 2. 把 tools 转成 Responses API flat tool schema
 * 3. 处理 tool_call_id 的 call_id / item_id 拆分
 */
public final class ResponsesConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponsesConverters() {
    }

    /**
     * 对应 Python:
     * convert_messages(messages) -> (system_prompt, input_items)
     *
     * 返回:
     * - systemPrompt: 从 system role 消息中抽出的 system prompt
     * - inputItems: Responses API 的 input 数组
     */
    @SuppressWarnings("unchecked")
    public static ConvertedMessages convertMessages(List<Map<String, Object>> messages) {
        String systemPrompt = "";
        List<Map<String, Object>> inputItems = new ArrayList<>();

        if (messages == null) {
            return new ConvertedMessages(systemPrompt, inputItems);
        }

        for (int idx = 0; idx < messages.size(); idx++) {
            Map<String, Object> msg = messages.get(idx);
            String role = string(msg.get("role"));
            Object content = msg.get("content");

            if ("system".equals(role)) {
                systemPrompt = content instanceof String ? (String) content : "";
                continue;
            }

            if ("user".equals(role)) {
                inputItems.add(convertUserMessage(content));
                continue;
            }

            if ("assistant".equals(role)) {
                if (content instanceof String s && !s.isEmpty()) {
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("type", "message");
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", List.of(Map.of(
                            "type", "output_text",
                            "text", s
                    )));
                    assistantMsg.put("status", "completed");
                    assistantMsg.put("id", "msg_" + idx);
                    inputItems.add(assistantMsg);
                }

                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object toolCallObj : toolCalls) {
                        if (!(toolCallObj instanceof Map<?, ?> rawToolCall)) {
                            continue;
                        }

                        Map<String, Object> toolCall = (Map<String, Object>) rawToolCall;
                        Map<String, Object> fn = toolCall.get("function") instanceof Map<?, ?> rawFn
                                ? (Map<String, Object>) rawFn
                                : Collections.emptyMap();

                        ToolCallIdParts idParts = splitToolCallId(toolCall.get("id"));

                        Map<String, Object> functionCall = new LinkedHashMap<>();
                        functionCall.put("type", "function_call");
                        functionCall.put("id", idParts.itemId() != null ? idParts.itemId() : "fc_" + idx);
                        functionCall.put("call_id", idParts.callId() != null ? idParts.callId() : "call_" + idx);
                        functionCall.put("name", fn.get("name"));
                        functionCall.put("arguments", fn.get("arguments") != null ? fn.get("arguments") : "{}");
                        inputItems.add(functionCall);
                    }
                }
                continue;
            }

            if ("tool".equals(role)) {
                ToolCallIdParts idParts = splitToolCallId(msg.get("tool_call_id"));

                String outputText;
                if (content instanceof String s) {
                    outputText = s;
                } else {
                    try {
                        outputText = MAPPER.writeValueAsString(content);
                    } catch (JsonProcessingException e) {
                        outputText = String.valueOf(content);
                    }
                }

                Map<String, Object> functionOutput = new LinkedHashMap<>();
                functionOutput.put("type", "function_call_output");
                functionOutput.put("call_id", idParts.callId());
                functionOutput.put("output", outputText);
                inputItems.add(functionOutput);
            }
        }

        return new ConvertedMessages(systemPrompt, inputItems);
    }

    /**
     * 对应 Python: convert_user_message(content)
     *
     * 支持：
     * - 纯字符串
     * - text block -> input_text
     * - image_url block -> input_image
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> convertUserMessage(Object content) {
        if (content instanceof String s) {
            return new LinkedHashMap<>(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                            "type", "input_text",
                            "text", s
                    ))
            ));
        }

        if (content instanceof List<?> list) {
            List<Map<String, Object>> converted = new ArrayList<>();

            for (Object itemObj : list) {
                if (!(itemObj instanceof Map<?, ?> rawItem)) {
                    continue;
                }

                Map<String, Object> item = (Map<String, Object>) rawItem;
                String type = string(item.get("type"));

                if ("text".equals(type)) {
                    converted.add(new LinkedHashMap<>(Map.of(
                            "type", "input_text",
                            "text", string(item.get("text"))
                    )));
                } else if ("image_url".equals(type)) {
                    Object imageUrlObj = item.get("image_url");
                    if (imageUrlObj instanceof Map<?, ?> rawImageUrl) {
                        String url = string(((Map<String, Object>) rawImageUrl).get("url"));
                        if (url != null && !url.isBlank()) {
                            Map<String, Object> imageBlock = new LinkedHashMap<>();
                            imageBlock.put("type", "input_image");
                            imageBlock.put("image_url", url);
                            imageBlock.put("detail", "auto");
                            converted.add(imageBlock);
                        }
                    }
                }
            }

            if (!converted.isEmpty()) {
                return new LinkedHashMap<>(Map.of(
                        "role", "user",
                        "content", converted
                ));
            }
        }

        return new LinkedHashMap<>(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_text",
                        "text", ""
                ))
        ));
    }

    /**
     * 对应 Python: convert_tools(tools)
     *
     * 把 OpenAI function-calling tool schema 转成 Responses API flat function tool 格式
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        if (tools == null) {
            return converted;
        }

        for (Map<String, Object> tool : tools) {
            Map<String, Object> fn;
            if ("function".equals(string(tool.get("type")))) {
                Object fnObj = tool.get("function");
                fn = fnObj instanceof Map<?, ?> rawFn
                        ? (Map<String, Object>) rawFn
                        : Collections.emptyMap();
            } else {
                fn = tool;
            }

            String name = string(fn.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }

            Object params = fn.get("parameters");
            Map<String, Object> parameters =
                    params instanceof Map<?, ?> rawParams
                            ? new LinkedHashMap<>((Map<String, Object>) rawParams)
                            : new LinkedHashMap<>();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("name", name);
            entry.put("description", fn.get("description") != null ? fn.get("description") : "");
            entry.put("parameters", parameters);

            converted.add(entry);
        }

        return converted;
    }

    /**
     * 对应 Python: split_tool_call_id(tool_call_id)
     *
     * 输入:
     * - "callId|itemId" -> (callId, itemId)
     * - "callId" -> (callId, null)
     * - None/非法 -> ("call_0", null)
     */
    public static ToolCallIdParts splitToolCallId(Object toolCallId) {
        if (toolCallId instanceof String s && !s.isBlank()) {
            int idx = s.indexOf('|');
            if (idx >= 0) {
                String callId = s.substring(0, idx);
                String itemId = s.substring(idx + 1);
                return new ToolCallIdParts(callId, itemId.isBlank() ? null : itemId);
            }
            return new ToolCallIdParts(s, null);
        }
        return new ToolCallIdParts("call_0", null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * convert_messages 的返回值封装
     */
    public record ConvertedMessages(
            String systemPrompt,
            List<Map<String, Object>> inputItems
    ) {
    }

    /**
     * split_tool_call_id 的返回值封装
     */
    public record ToolCallIdParts(
            String callId,
            String itemId
    ) {
    }
}