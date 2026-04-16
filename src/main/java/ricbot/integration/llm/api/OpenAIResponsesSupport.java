package ricbot.integration.llm.api;

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
 * 1. 转换消息 (convert_messages)
 * 2. 转换工具 (convert_tools)
 * 3. 解析响应输出 (parse_response_output)
 * 4. 消费 SSE / SDK 流 (consume_sse / consume_sdk_stream)
 */
public final class OpenAIResponsesSupport {

    // 创建 ObjectMapper 实例，用于 JSON 序列化与反序列化
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 私有构造函数，防止实例化
    private OpenAIResponsesSupport() {
    }

    /**
     * 消费 OpenAI 标准 SSE 流 (基于 Stream<String>)
     *
     * @param lines   行流
     * @param onDelta 增量处理器
     * @param onEnd   结束处理器
     * @return LLMResponse
     */
    @SuppressWarnings("unchecked")
    public static LLMResponse consumeSSE(
            java.util.stream.Stream<String> lines,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) {
        // 初始化全文本构建器
        StringBuilder fullContent = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCallBuilders = new HashMap<>();
        // 初始化使用量 Map
        Map<String, Integer> usage = new LinkedHashMap<>();
        // 使用数组包裹以在 lambda 中修改
        String[] finishReasonArr = {"stop"};

        // 遍历每一行
        lines.forEach(line -> {
            String trimmed = line.trim();
            // 跳过空行或非 data 行
            if (trimmed.isEmpty() || !trimmed.startsWith("data: ")) {
                return;
            }

            // 提取数据部分
            String data = trimmed.substring(6).trim();
            // 跳过结束标记
            if ("[DONE]".equals(data)) {
                return;
            }

            try {
                // 解析 JSON 块
                Map<String, Object> chunk = MAPPER.readValue(data, new TypeReference<>() {});
                // 获取 choices 列表
                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    // 获取 delta 对象
                    Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                    if (delta != null) {
                        // 获取增量内容
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
                    // 更新结束原因
                    if (choice.get("finish_reason") != null) {
                        finishReasonArr[0] = String.valueOf(choice.get("finish_reason"));
                    }
                }

                // 处理使用量信息
                Map<String, Object> usageRaw = (Map<String, Object>) chunk.get("usage");
                if (usageRaw != null) {
                    usage.put("prompt_tokens", toInt(usageRaw.get("prompt_tokens")));
                    usage.put("completion_tokens", toInt(usageRaw.get("completion_tokens")));
                    usage.put("total_tokens", toInt(usageRaw.get("total_tokens")));
                }

            } catch (Exception ignored) {
                // 忽略解析异常
            }
        });

        List<ToolCallRequest> toolCalls = finalizeToolCalls(toolCallBuilders);
        // 构建最终响应
        LLMResponse resp = new LLMResponse()
                .setContent(fullContent.toString())
                .setFinishReason(finishReasonArr[0])
                .setToolCalls(toolCalls)
                .setUsage(usage);

        // 调用结束处理器
        if (onEnd != null) {
            try {
                onEnd.handle(resp);
            } catch (Exception ignored) {}
        }
        return resp;
    }

    /**
     * 消费基于 InputStream 的 SSE 流
     *
     * @param inputStream 输入流
     * @param onDelta     增量处理器
     * @param onEnd       结束处理器
     * @return LLMResponse
     * @throws Exception 异常
     */
    public static LLMResponse consumeSSE(
            InputStream inputStream,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) throws Exception {
        // 创建 BufferedReader 并转换为行流，委托给另一个 consumeSSE 方法
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return consumeSSE(reader.lines(), onDelta, onEnd);
        }
    }

    /**
     * 安全地将对象转换为 Integer
     *
     * @param o 对象
     * @return Integer 值，如果非数字则返回 null
     */
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
