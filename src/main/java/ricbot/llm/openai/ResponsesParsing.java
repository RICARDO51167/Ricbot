package ricbot.llm.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.ToolCallRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对应 Python: parsing.py
 *
 * 主要目标：
 * 1. 解析 Responses API SSE 流
 * 2. 解析 SDK Response 对象
 * 3. 统一转换为 LLMResponse + ToolCallRequest
 */
public final class ResponsesParsing {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 对应 Python: FINISH_REASON_MAP
     */
    public static final Map<String, String> FINISH_REASON_MAP = Map.of(
            "completed", "stop",
            "incomplete", "length",
            "failed", "error",
            "cancelled", "error"
    );

    private ResponsesParsing() {
    }

    /**
     * 对应 Python: map_finish_reason(status)
     */
    public static String mapFinishReason(String status) {
        return FINISH_REASON_MAP.getOrDefault(
                status != null ? status : "completed",
                "stop"
        );
    }

    /**
     * 对应 Python: iter_sse(response)
     *
     * Java 这里从 InputStream 中读取 SSE 事件，
     * 按 event block 解析出 JSON payload。
     */
    public static List<Map<String, Object>> iterSse(InputStream inputStream) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        if (inputStream == null) {
            return events;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            List<String> buffer = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    Map<String, Object> event = flushSseBuffer(buffer);
                    if (event != null) {
                        events.add(event);
                    }
                    continue;
                }
                buffer.add(line);
            }

            // EOF 时补一次 flush
            Map<String, Object> event = flushSseBuffer(buffer);
            if (event != null) {
                events.add(event);
            }
        }

        return events;
    }

    /**
     * 对应 Python consume_sse(...)
     *
     * 返回:
     * - content
     * - toolCalls
     * - finishReason
     */
    public static SseConsumeResult consumeSse(
            InputStream inputStream,
            ContentDeltaHandler onContentDelta
    ) throws Exception {
        StringBuilder content = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        Map<String, ToolCallBuffer> toolCallBuffers = new LinkedHashMap<>();
        String finishReason = "stop";

        List<Map<String, Object>> events = iterSse(inputStream);

        for (Map<String, Object> event : events) {
            String eventType = string(event.get("type"));

            if ("response.output_item.added".equals(eventType)) {
                Map<String, Object> item = asMap(event.get("item"));
                if ("function_call".equals(string(item.get("type")))) {
                    String callId = string(item.get("call_id"));
                    if (callId == null || callId.isBlank()) {
                        continue;
                    }

                    toolCallBuffers.put(callId, new ToolCallBuffer(
                            string(item.get("id")) != null ? string(item.get("id")) : "fc_0",
                            string(item.get("name")),
                            string(item.get("arguments")) != null ? string(item.get("arguments")) : ""
                    ));
                }
            }

            else if ("response.output_text.delta".equals(eventType)) {
                String deltaText = string(event.get("delta"));
                if (deltaText == null) {
                    deltaText = "";
                }
                content.append(deltaText);
                if (onContentDelta != null && !deltaText.isEmpty()) {
                    onContentDelta.onDelta(deltaText);
                }
            }

            else if ("response.function_call_arguments.delta".equals(eventType)) {
                String callId = string(event.get("call_id"));
                if (callId != null && toolCallBuffers.containsKey(callId)) {
                    ToolCallBuffer buf = toolCallBuffers.get(callId);
                    buf.arguments += string(event.get("delta")) != null ? string(event.get("delta")) : "";
                }
            }

            else if ("response.function_call_arguments.done".equals(eventType)) {
                String callId = string(event.get("call_id"));
                if (callId != null && toolCallBuffers.containsKey(callId)) {
                    ToolCallBuffer buf = toolCallBuffers.get(callId);
                    buf.arguments = string(event.get("arguments")) != null ? string(event.get("arguments")) : "";
                }
            }

            else if ("response.output_item.done".equals(eventType)) {
                Map<String, Object> item = asMap(event.get("item"));
                if ("function_call".equals(string(item.get("type")))) {
                    String callId = string(item.get("call_id"));
                    if (callId == null || callId.isBlank()) {
                        continue;
                    }

                    ToolCallBuffer buf = toolCallBuffers.get(callId);
                    if (buf == null) {
                        buf = new ToolCallBuffer(
                                string(item.get("id")) != null ? string(item.get("id")) : "fc_0",
                                string(item.get("name")),
                                string(item.get("arguments")) != null ? string(item.get("arguments")) : "{}"
                        );
                    }

                    String argsRaw = buf.arguments != null && !buf.arguments.isBlank()
                            ? buf.arguments
                            : (string(item.get("arguments")) != null ? string(item.get("arguments")) : "{}");

                    Map<String, Object> args = parseJsonObjectSafely(argsRaw);

                    toolCalls.add(new ToolCallRequest(
                            callId + "|" + (buf.id != null ? buf.id : (string(item.get("id")) != null ? string(item.get("id")) : "fc_0")),
                            buf.name != null ? buf.name : (string(item.get("name")) != null ? string(item.get("name")) : ""),
                            args
                    ));
                }
            }

            else if ("response.completed".equals(eventType)) {
                Map<String, Object> response = asMap(event.get("response"));
                String status = string(response.get("status"));
                finishReason = mapFinishReason(status);
            }

            else if ("error".equals(eventType) || "response.failed".equals(eventType)) {
                Object detail = event.get("error") != null ? event.get("error")
                        : (event.get("message") != null ? event.get("message") : event);
                throw new RuntimeException("Response failed: " + String.valueOf(detail));
            }
        }

        return new SseConsumeResult(content.toString(), toolCalls, finishReason);
    }

    /**
     * 对应 Python: parse_response_output(response)
     *
     * 解析 SDK Response 对象 / dump 后 Map
     */
    @SuppressWarnings("unchecked")
    public static LLMResponse parseResponseOutput(Object response) {
        Map<String, Object> root = coerceToMap(response);
        if (root == null) {
            return new LLMResponse()
                    .setContent(null)
                    .setToolCalls(new ArrayList<>())
                    .setFinishReason("stop")
                    .setUsage(new LinkedHashMap<>());
        }

        Object outputObj = root.get("output");
        List<?> output = outputObj instanceof List<?> list ? list : Collections.emptyList();

        List<String> contentParts = new ArrayList<>();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        String reasoningContent = null;

        for (Object itemObj : output) {
            Map<String, Object> item = coerceToMap(itemObj);
            if (item == null) {
                continue;
            }

            String itemType = string(item.get("type"));

            if ("message".equals(itemType)) {
                Object contentObj = item.get("content");
                List<?> blocks = contentObj instanceof List<?> list ? list : Collections.emptyList();

                for (Object blockObj : blocks) {
                    Map<String, Object> block = coerceToMap(blockObj);
                    if (block == null) {
                        continue;
                    }
                    if ("output_text".equals(string(block.get("type")))) {
                        String text = string(block.get("text"));
                        if (text != null) {
                            contentParts.add(text);
                        }
                    }
                }
            }

            else if ("reasoning".equals(itemType)) {
                Object summaryObj = item.get("summary");
                List<?> summary = summaryObj instanceof List<?> list ? list : Collections.emptyList();

                for (Object sObj : summary) {
                    Map<String, Object> s = coerceToMap(sObj);
                    if (s == null) {
                        continue;
                    }
                    if ("summary_text".equals(string(s.get("type"))) && s.get("text") != null) {
                        reasoningContent = (reasoningContent == null ? "" : reasoningContent) + s.get("text");
                    }
                }
            }

            else if ("function_call".equals(itemType)) {
                String callId = string(item.get("call_id")) != null ? string(item.get("call_id")) : "";
                String itemId = string(item.get("id")) != null ? string(item.get("id")) : "fc_0";
                Object argsRawObj = item.get("arguments");
                Object parsed;

                if (argsRawObj instanceof String s) {
                    parsed = parseJsonObjectSafelyLoose(s);
                } else if (argsRawObj instanceof Map<?, ?>) {
                    parsed = argsRawObj;
                } else {
                    parsed = new LinkedHashMap<String, Object>();
                }

                Map<String, Object> args =
                        parsed instanceof Map<?, ?> rawMap
                                ? new LinkedHashMap<>((Map<String, Object>) rawMap)
                                : new LinkedHashMap<>();

                toolCalls.add(new ToolCallRequest(
                        callId + "|" + itemId,
                        string(item.get("name")) != null ? string(item.get("name")) : "",
                        args
                ));
            }
        }

        Map<String, Integer> usage = new LinkedHashMap<>();
        Map<String, Object> usageRaw = asMap(root.get("usage"));
        if (!usageRaw.isEmpty()) {
            usage.put("prompt_tokens", intValue(usageRaw.get("input_tokens")));
            usage.put("completion_tokens", intValue(usageRaw.get("output_tokens")));
            usage.put("total_tokens", intValue(usageRaw.get("total_tokens")));
        }

        String status = string(root.get("status"));
        String finishReason = mapFinishReason(status);

        return new LLMResponse()
                .setContent(contentParts.isEmpty() ? null : String.join("", contentParts))
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason)
                .setUsage(usage)
                .setReasoningContent(reasoningContent);
    }

    /**
     * 对应 Python: consume_sdk_stream(...)
     *
     * Java 这里用 Iterable/Iterator 兼容“SDK stream 对象”
     */
    public static SdkStreamConsumeResult consumeSdkStream(
            Iterable<?> stream,
            ContentDeltaHandler onContentDelta
    ) throws Exception {
        StringBuilder content = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        Map<String, ToolCallBuffer> toolCallBuffers = new LinkedHashMap<>();
        String finishReason = "stop";
        Map<String, Integer> usage = new LinkedHashMap<>();
        String reasoningContent = null;

        if (stream == null) {
            return new SdkStreamConsumeResult("", toolCalls, finishReason, usage, reasoningContent);
        }

        for (Object eventObj : stream) {
            Map<String, Object> event = coerceToMap(eventObj);
            if (event == null || event.isEmpty()) {
                continue;
            }

            String eventType = string(event.get("type"));

            if ("response.output_item.added".equals(eventType)) {
                Map<String, Object> item = asMap(event.get("item"));
                if ("function_call".equals(string(item.get("type")))) {
                    String callId = string(item.get("call_id"));
                    if (callId == null || callId.isBlank()) {
                        continue;
                    }

                    toolCallBuffers.put(callId, new ToolCallBuffer(
                            string(item.get("id")) != null ? string(item.get("id")) : "fc_0",
                            string(item.get("name")),
                            string(item.get("arguments")) != null ? string(item.get("arguments")) : ""
                    ));
                }
            }

            else if ("response.output_text.delta".equals(eventType)) {
                String deltaText = string(event.get("delta"));
                if (deltaText == null) {
                    deltaText = "";
                }

                content.append(deltaText);
                if (onContentDelta != null && !deltaText.isEmpty()) {
                    onContentDelta.onDelta(deltaText);
                }
            }

            else if ("response.function_call_arguments.delta".equals(eventType)) {
                String callId = string(event.get("call_id"));
                if (callId != null && toolCallBuffers.containsKey(callId)) {
                    ToolCallBuffer buf = toolCallBuffers.get(callId);
                    buf.arguments += string(event.get("delta")) != null ? string(event.get("delta")) : "";
                }
            }

            else if ("response.function_call_arguments.done".equals(eventType)) {
                String callId = string(event.get("call_id"));
                if (callId != null && toolCallBuffers.containsKey(callId)) {
                    ToolCallBuffer buf = toolCallBuffers.get(callId);
                    buf.arguments = string(event.get("arguments")) != null ? string(event.get("arguments")) : "";
                }
            }

            else if ("response.output_item.done".equals(eventType)) {
                Map<String, Object> item = asMap(event.get("item"));
                if ("function_call".equals(string(item.get("type")))) {
                    String callId = string(item.get("call_id"));
                    if (callId == null || callId.isBlank()) {
                        continue;
                    }

                    ToolCallBuffer buf = toolCallBuffers.get(callId);
                    if (buf == null) {
                        buf = new ToolCallBuffer(
                                string(item.get("id")) != null ? string(item.get("id")) : "fc_0",
                                string(item.get("name")),
                                string(item.get("arguments")) != null ? string(item.get("arguments")) : "{}"
                        );
                    }

                    String argsRaw = buf.arguments != null && !buf.arguments.isBlank()
                            ? buf.arguments
                            : (string(item.get("arguments")) != null ? string(item.get("arguments")) : "{}");

                    Map<String, Object> args = parseJsonObjectSafelyLoose(argsRaw);

                    toolCalls.add(new ToolCallRequest(
                            callId + "|" + (buf.id != null ? buf.id : (string(item.get("id")) != null ? string(item.get("id")) : "fc_0")),
                            buf.name != null ? buf.name : (string(item.get("name")) != null ? string(item.get("name")) : ""),
                            args
                    ));
                }
            }

            else if ("response.completed".equals(eventType)) {
                Map<String, Object> resp = asMap(event.get("response"));
                String status = string(resp.get("status"));
                finishReason = mapFinishReason(status);

                Map<String, Object> usageObj = asMap(resp.get("usage"));
                if (!usageObj.isEmpty()) {
                    usage.put("prompt_tokens", intValue(usageObj.get("input_tokens")));
                    usage.put("completion_tokens", intValue(usageObj.get("output_tokens")));
                    usage.put("total_tokens", intValue(usageObj.get("total_tokens")));
                }

                Object outObj = resp.get("output");
                List<?> outItems = outObj instanceof List<?> list ? list : Collections.emptyList();

                for (Object outItemObj : outItems) {
                    Map<String, Object> outItem = coerceToMap(outItemObj);
                    if (outItem == null) {
                        continue;
                    }

                    if ("reasoning".equals(string(outItem.get("type")))) {
                        Object summaryObj = outItem.get("summary");
                        List<?> summary = summaryObj instanceof List<?> list ? list : Collections.emptyList();

                        for (Object sObj : summary) {
                            Map<String, Object> s = coerceToMap(sObj);
                            if (s == null) {
                                continue;
                            }
                            if ("summary_text".equals(string(s.get("type")))) {
                                String text = string(s.get("text"));
                                if (text != null) {
                                    reasoningContent = (reasoningContent == null ? "" : reasoningContent) + text;
                                }
                            }
                        }
                    }
                }
            }

            else if ("error".equals(eventType) || "response.failed".equals(eventType)) {
                Object detail = event.get("error") != null ? event.get("error")
                        : (event.get("message") != null ? event.get("message") : event);
                throw new RuntimeException("Response failed: " + String.valueOf(detail));
            }
        }

        return new SdkStreamConsumeResult(
                content.toString(),
                toolCalls,
                finishReason,
                usage,
                reasoningContent
        );
    }

    // =========================================================
    // helper methods
    // =========================================================

    private static Map<String, Object> flushSseBuffer(List<String> buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }

        List<String> dataLines = new ArrayList<>();
        for (String line : buffer) {
            if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).trim());
            }
        }
        buffer.clear();

        if (dataLines.isEmpty()) {
            return null;
        }

        String data = String.join("\n", dataLines).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return null;
        }

        try {
            return MAPPER.readValue(data, new TypeReference<>() {});
        } catch (Exception e) {
            System.err.println("Failed to parse SSE event JSON: " + truncate(data, 200));
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceToMap(Object obj) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }

        try {
            return MAPPER.convertValue(obj, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> parseJsonObjectSafely(String raw) {
        try {
            Object parsed = MAPPER.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }
            return new LinkedHashMap<>(Map.of("raw", raw));
        } catch (Exception e) {
            System.err.println("Failed to parse tool call arguments: " + truncate(raw, 200));
            return new LinkedHashMap<>(Map.of("raw", raw));
        }
    }

    /**
     * Python 里用到了 json_repair。
     * Java 这里先做一个宽松 fallback：
     * - 先正常 parse
     * - 失败则直接塞 raw
     */
    private static Map<String, Object> parseJsonObjectSafelyLoose(String raw) {
        try {
            Object parsed = MAPPER.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }
            return new LinkedHashMap<>(Map.of("raw", raw));
        } catch (Exception e) {
            System.err.println("Failed to parse tool call arguments loosely: " + truncate(raw, 200));
            return new LinkedHashMap<>(Map.of("raw", raw));
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // =========================================================
    // DTOs / interfaces
    // =========================================================

    public interface ContentDeltaHandler {
        void onDelta(String delta) throws Exception;
    }

    public record SseConsumeResult(
            String content,
            List<ToolCallRequest> toolCalls,
            String finishReason
    ) {
    }

    public record SdkStreamConsumeResult(
            String content,
            List<ToolCallRequest> toolCalls,
            String finishReason,
            Map<String, Integer> usage,
            String reasoningContent
    ) {
    }

    private static class ToolCallBuffer {
        String id;
        String name;
        String arguments;

        ToolCallBuffer(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }
}