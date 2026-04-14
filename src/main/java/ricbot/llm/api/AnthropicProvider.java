package ricbot.llm.api;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应 Python: AnthropicProvider
 *
 * 主要目标：
 * 1. 使用 Anthropic Messages API
 * 2. 把 OpenAI 风格 message/tool schema 转成 Anthropic 风格
 * 3. 支持 thinking blocks / tool_use / tool_result / image block
 */
public class AnthropicProvider extends LLMProvider {

    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> extraHeaders;
    private Object client;

    public AnthropicProvider(
            String apiKey,
            String apiBase,
            String defaultModel,
            Map<String, String> extraHeaders
    ) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel != null ? defaultModel : "claude-sonnet-4-20250514";
        this.extraHeaders = extraHeaders != null ? extraHeaders : new LinkedHashMap<>();

        // TODO:
        // 这里后面替换成真正 Anthropic Java SDK client
        this.client = new Object();
    }

    private static String genToolId() {
        StringBuilder sb = new StringBuilder("toolu_");
        for (int i = 0; i < 22; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    /**
     * 对应 Python: _handle_error(...)
     */
    public static LLMResponse handleError(Exception e) {
        String msg = "Error calling LLM: " + e.getMessage();
        Double retryAfter = extractRetryAfter(msg);

        String errorName = e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String errorKind = null;
        if (errorName.contains("timeout")) {
            errorKind = "timeout";
        } else if (errorName.contains("connection")) {
            errorKind = "connection";
        }

        return new LLMResponse()
                .setContent(msg)
                .setFinishReason("error")
                .setRetryAfter(retryAfter)
                .setErrorKind(errorKind)
                .setErrorRetryAfterS(retryAfter);
    }

    /**
     * 对应 Python: _strip_prefix(model)
     */
    public static String stripPrefix(String model) {
        if (model != null && model.startsWith("anthropic/")) {
            return model.substring("anthropic/".length());
        }
        return model;
    }

    /**
     * 对应 Python: _convert_messages(...)
     *
     * 返回:
     * - system
     * - anthropic_messages
     */
    @SuppressWarnings("unchecked")
    public ConvertedAnthropicMessages convertMessages(List<Map<String, Object>> messages) {
        Object system = "";
        List<Map<String, Object>> raw = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            Object content = msg.get("content");

            if ("system".equals(role)) {
                if (content instanceof String || content instanceof List<?>) {
                    system = content;
                } else {
                    system = content != null ? String.valueOf(content) : "";
                }
                continue;
            }

            if ("tool".equals(role)) {
                Map<String, Object> block = toolResultBlock(msg);

                if (!raw.isEmpty() && "user".equals(raw.get(raw.size() - 1).get("role"))) {
                    Object prevContent = raw.get(raw.size() - 1).get("content");
                    if (prevContent instanceof List<?> prevList) {
                        ((List<Object>) prevList).add(block);
                    } else {
                        raw.get(raw.size() - 1).put("content", new ArrayList<>(List.of(
                                Map.of("type", "text", "text", prevContent != null ? String.valueOf(prevContent) : ""),
                                block
                        )));
                    }
                } else {
                    raw.add(new LinkedHashMap<>(Map.of(
                            "role", "user",
                            "content", new ArrayList<>(List.of(block))
                    )));
                }
                continue;
            }

            if ("assistant".equals(role)) {
                raw.add(new LinkedHashMap<>(Map.of(
                        "role", "assistant",
                        "content", assistantBlocks(msg)
                )));
                continue;
            }

            if ("user".equals(role)) {
                raw.add(new LinkedHashMap<>(Map.of(
                        "role", "user",
                        "content", convertUserContent(content)
                )));
            }
        }

        return new ConvertedAnthropicMessages(system, mergeConsecutive(raw));
    }

    /**
     * 对应 Python: _tool_result_block(msg)
     */
    public static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        Object content = msg.get("content");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", msg.getOrDefault("tool_call_id", ""));

        if (content instanceof String || content instanceof List<?>) {
            block.put("content", content);
        } else {
            block.put("content", content != null ? String.valueOf(content) : "");
        }

        return block;
    }

    /**
     * 对应 Python: _assistant_blocks(msg)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> assistantBlocks(Map<String, Object> msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Object content = msg.get("content");

        Object thinkingBlocksObj = msg.get("thinking_blocks");
        if (thinkingBlocksObj instanceof List<?> tbList) {
            for (Object tb : tbList) {
                if (tb instanceof Map<?, ?> rawTb) {
                    Map<String, Object> map = (Map<String, Object>) rawTb;
                    if ("thinking".equals(map.get("type"))) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "thinking");
                        block.put("thinking", map.getOrDefault("thinking", ""));
                        block.put("signature", map.getOrDefault("signature", ""));
                        blocks.add(block);
                    }
                }
            }
        }

        if (content instanceof String s && !s.isBlank()) {
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", s)));
        } else if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> rawItem) {
                    blocks.add(new LinkedHashMap<>((Map<String, Object>) rawItem));
                } else {
                    blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", String.valueOf(item))));
                }
            }
        }

        Object toolCallsObj = msg.get("tool_calls");
        if (toolCallsObj instanceof List<?> toolCalls) {
            for (Object tc : toolCalls) {
                if (!(tc instanceof Map<?, ?> rawTc)) continue;
                Map<String, Object> tcMap = (Map<String, Object>) rawTc;
                Map<String, Object> func = tcMap.get("function") instanceof Map<?, ?> fm
                        ? (Map<String, Object>) fm
                        : Collections.emptyMap();

                Object args = func.getOrDefault("arguments", "{}");
                Map<String, Object> input = new LinkedHashMap<>();
                if (args instanceof String s) {
                    try {
                        input = MAPPER.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    } catch (Exception ignored) {
                    }
                } else if (args instanceof Map<?, ?> m) {
                    input = new LinkedHashMap<>((Map<String, Object>) m);
                }

                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", tcMap.getOrDefault("id", genToolId()));
                block.put("name", func.getOrDefault("name", ""));
                block.put("input", input);
                blocks.add(block);
            }
        }

        if (blocks.isEmpty()) {
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", "")));
        }

        return blocks;
    }

    /**
     * 对应 Python: _convert_user_content(content)
     */
    @SuppressWarnings("unchecked")
    public Object convertUserContent(Object content) {
        if (content instanceof String || content == null) {
            return content != null ? content : "(empty)";
        }
        if (!(content instanceof List<?> list)) {
            return String.valueOf(content);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                result.add(new LinkedHashMap<>(Map.of("type", "text", "text", String.valueOf(item))));
                continue;
            }

            Map<String, Object> dict = (Map<String, Object>) raw;
            if ("image_url".equals(dict.get("type"))) {
                Map<String, Object> converted = convertImageBlock(dict);
                if (converted != null) {
                    result.add(converted);
                }
                continue;
            }
            result.add(new LinkedHashMap<>(dict));
        }

        return result.isEmpty() ? "(empty)" : result;
    }

    /**
     * 对应 Python: _convert_image_block(block)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> convertImageBlock(Map<String, Object> block) {
        Object imageUrlObj = block.get("image_url");
        if (!(imageUrlObj instanceof Map<?, ?> raw)) {
            return null;
        }

        String url = String.valueOf(((Map<String, Object>) raw).getOrDefault("url", ""));
        if (url.isBlank()) {
            return null;
        }

        Matcher m = Pattern.compile("^data:(image/\\w+);base64,(.+)$", Pattern.DOTALL).matcher(url);
        if (m.find()) {
            return new LinkedHashMap<>(Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", m.group(1),
                            "data", m.group(2)
                    )
            ));
        }

        return new LinkedHashMap<>(Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "url",
                        "url", url
                )
        ));
    }

    /**
     * 对应 Python: _merge_consecutive(msgs)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> merged = new ArrayList<>();

        for (Map<String, Object> msg : msgs) {
            if (!merged.isEmpty() && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {
                Object prevC = merged.get(merged.size() - 1).get("content");
                Object curC = msg.get("content");

                List<Object> prevList;
                if (prevC instanceof String s) {
                    prevList = new ArrayList<>(List.of(Map.of("type", "text", "text", s)));
                } else if (prevC instanceof List<?> list) {
                    prevList = new ArrayList<>((List<Object>) list);
                } else {
                    prevList = new ArrayList<>();
                }

                List<Object> curList;
                if (curC instanceof String s) {
                    curList = new ArrayList<>(List.of(Map.of("type", "text", "text", s)));
                } else if (curC instanceof List<?> list) {
                    curList = new ArrayList<>((List<Object>) list);
                } else {
                    curList = new ArrayList<>();
                }

                prevList.addAll(curList);
                merged.get(merged.size() - 1).put("content", prevList);
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        return merged;
    }

    /**
     * 对应 Python: _convert_tools(tools)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = tool.get("function") instanceof Map<?, ?> fm
                    ? (Map<String, Object>) fm
                    : tool;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", func.getOrDefault("name", ""));
            entry.put("input_schema", func.getOrDefault("parameters", Map.of("type", "object", "properties", Map.of())));

            Object desc = func.get("description");
            if (desc != null && !String.valueOf(desc).isBlank()) {
                entry.put("description", desc);
            }
            if (tool.containsKey("cache_control")) {
                entry.put("cache_control", tool.get("cache_control"));
            }
            result.add(entry);
        }
        return result;
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
        try {
            ConvertedAnthropicMessages converted = convertMessages(messages);
            String finalModel = stripPrefix(model != null ? model : defaultModel);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", finalModel);
            body.put("system", converted.system());
            body.put("messages", converted.messages());
            body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());

            if (tools != null && !tools.isEmpty()) {
                body.put("tools", convertTools(tools));
            }
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                body.put("thinking", Map.of("type", "enabled", "budget_tokens", 2048));
            }

            // TODO:
            // 替换成真正 Anthropic SDK messages.create(...)
            Object raw = AnthropicSdkShim.messagesCreate(client, body);

            // TODO:
            // 这里后续你可以单独抽一个 AnthropicResponseParser
            return AnthropicResponseParser.parse(raw);
        } catch (Exception e) {
            return handleError(e);
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
    ) {
        try {
            ConvertedAnthropicMessages converted = convertMessages(messages);
            String finalModel = stripPrefix(model != null ? model : defaultModel);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", finalModel);
            body.put("system", converted.system());
            body.put("messages", converted.messages());
            body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
            body.put("stream", true);

            if (tools != null && !tools.isEmpty()) {
                body.put("tools", convertTools(tools));
            }

            Object stream = AnthropicSdkShim.messagesStream(client, body);
            return AnthropicResponseParser.consumeStream(stream, onDelta, onEnd);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    public record ConvertedAnthropicMessages(Object system, List<Map<String, Object>> messages) {
    }
}