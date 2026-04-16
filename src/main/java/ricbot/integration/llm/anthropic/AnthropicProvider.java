package ricbot.integration.llm.anthropic;

import com.fasterxml.jackson.core.type.TypeReference;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * Anthropic Provider 实现
 */
public class AnthropicProvider extends LLMProvider {

    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> extraHeaders;
    private final HttpClient client;

    public AnthropicProvider(
            String apiKey,
            String apiBase,
            String defaultModel,
            Map<String, String> extraHeaders
    ) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel != null ? defaultModel : "claude-3-5-sonnet-20240620";
        this.extraHeaders = extraHeaders != null ? new LinkedHashMap<>(extraHeaders) : new LinkedHashMap<>();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static String genToolId() {
        StringBuilder sb = new StringBuilder("toolu_");
        for (int i = 0; i < 22; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    public static String stripPrefix(String model) {
        if (model != null && model.startsWith("anthropic/")) {
            return model.substring("anthropic/".length());
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    public ConvertedAnthropicMessages convertMessages(List<Map<String, Object>> messages) {
        Object system = null;
        List<Map<String, Object>> raw = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            Object content = msg.get("content");

            if ("system".equals(role)) {
                system = content;
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

    public static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        Object content = msg.get("content");
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", msg.getOrDefault("tool_call_id", ""));
        block.put("content", content != null ? content : "");
        return block;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> assistantBlocks(Map<String, Object> msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Object content = msg.get("content");

        if (content instanceof String s && !s.isBlank()) {
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", s)));
        }

        Object toolCallsObj = msg.get("tool_calls");
        if (toolCallsObj instanceof List<?> toolCalls) {
            for (Object tc : toolCalls) {
                if (!(tc instanceof Map<?, ?> rawTc)) continue;
                Map<String, Object> tcMap = (Map<String, Object>) rawTc;
                Map<String, Object> func = (Map<String, Object>) tcMap.getOrDefault("function", Collections.emptyMap());

                Map<String, Object> input = new HashMap<>();
                Object args = func.get("arguments");
                if (args instanceof String s) {
                    try {
                        input = MAPPER.readValue(s, new TypeReference<>() {});
                    } catch (Exception ignored) {}
                } else if (args instanceof Map<?, ?> m) {
                    input = (Map<String, Object>) m;
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
            blocks.add(Map.of("type", "text", "text", ""));
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    public Object convertUserContent(Object content) {
        if (content instanceof String || content == null) {
            return content != null ? content : "";
        }
        if (!(content instanceof List<?> list)) {
            return String.valueOf(content);
        }
        List<Object> converted = new ArrayList<>();
        for (Object itemObj : list) {
            if (!(itemObj instanceof Map<?, ?> rawItem)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) rawItem;
            String type = String.valueOf(item.get("type"));
            if ("text".equals(type)) {
                converted.add(new LinkedHashMap<>(Map.of(
                        "type", "text",
                        "text", String.valueOf(item.getOrDefault("text", ""))
                )));
            } else if ("image_url".equals(type)) {
                Object imageUrlObj = item.get("image_url");
                if (imageUrlObj instanceof Map<?, ?> rawImageUrl) {
                    String url = String.valueOf(((Map<String, Object>) rawImageUrl).getOrDefault("url", ""));
                    Map<String, Object> imageBlock = convertImageUrlToAnthropic(url);
                    if (imageBlock != null) {
                        converted.add(imageBlock);
                    } else if (url != null && !url.isBlank()) {
                        converted.add(new LinkedHashMap<>(Map.of(
                                "type", "text",
                                "text", "[image: " + url + "]"
                        )));
                    }
                }
            }
        }
        if (converted.isEmpty()) {
            return List.of(Map.of("type", "text", "text", ""));
        }
        return converted;
    }

    private static Map<String, Object> convertImageUrlToAnthropic(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("data:")) {
            return null;
        }

        int semi = trimmed.indexOf(';');
        int comma = trimmed.indexOf(',');
        if (semi < 0 || comma < 0 || comma <= semi) {
            return null;
        }

        String mediaType = trimmed.substring("data:".length(), semi);
        String meta = trimmed.substring(semi + 1, comma);
        if (!"base64".equalsIgnoreCase(meta)) {
            return null;
        }
        String data = trimmed.substring(comma + 1);
        if (mediaType.isBlank() || data.isBlank()) {
            return null;
        }

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", data);

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : msgs) {
            if (!merged.isEmpty() && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {
                Object prevC = merged.get(merged.size() - 1).get("content");
                Object curC = msg.get("content");

                List<Object> prevList = (prevC instanceof List<?> l) ? new ArrayList<>(l) : new ArrayList<>(List.of(Map.of("type", "text", "text", prevC)));
                List<Object> curList = (curC instanceof List<?> l) ? new ArrayList<>(l) : new ArrayList<>(List.of(Map.of("type", "text", "text", curC)));
                prevList.addAll(curList);
                merged.get(merged.size() - 1).put("content", prevList);
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        if (tools == null) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = (Map<String, Object>) tool.getOrDefault("function", tool);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", func.get("name"));
            entry.put("description", func.get("description"));
            entry.put("input_schema", func.get("parameters"));
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
    ) throws Exception {
        ConvertedAnthropicMessages converted = convertMessages(messages);
        String finalModel = stripPrefix(model != null ? model : defaultModel);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", finalModel);
        if (converted.system() != null) {
            body.put("system", converted.system());
        }
        body.put("messages", converted.messages());
        body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }

        String json = MAPPER.writeValueAsString(body);
        String url = (apiBase != null && !apiBase.isBlank()) ? apiBase : "https://api.anthropic.com/v1/messages";
        
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }

        HttpResponse<String> response = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            return new LLMResponse()
                    .setFinishReason("error")
                    .setContent("Anthropic API 错误: " + response.statusCode() + " " + response.body());
        }

        return parseAnthropicResponse(response.body());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseAnthropicResponse(String json) throws Exception {
        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
        
        StringBuilder content = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        
        List<Map<String, Object>> contentList = (List<Map<String, Object>>) map.get("content");
        if (contentList != null) {
            for (Map<String, Object> block : contentList) {
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    content.append((String) block.get("text"));
                } else if ("tool_use".equals(type)) {
                    String id = (String) block.get("id");
                    String name = (String) block.get("name");
                    Map<String, Object> input = (Map<String, Object>) block.get("input");
                    toolCalls.add(new ToolCallRequest(id, name, input));
                }
            }
        }

        String stopReason = (String) map.get("stop_reason");
        Map<String, Object> usageRaw = (Map<String, Object>) map.get("usage");
        Map<String, Integer> usage = new HashMap<>();
        if (usageRaw != null) {
            usage.put("prompt_tokens", (Integer) usageRaw.get("input_tokens"));
            usage.put("completion_tokens", (Integer) usageRaw.get("output_tokens"));
        }

        return new LLMResponse()
                .setContent(content.toString())
                .setToolCalls(toolCalls)
                .setFinishReason(stopReason)
                .setUsage(usage);
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
            if (converted.system() != null) {
                body.put("system", converted.system());
            }
            body.put("messages", converted.messages());
            body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
            body.put("stream", true);

            if (tools != null && !tools.isEmpty()) {
                body.put("tools", convertTools(tools));
            }

            String json = MAPPER.writeValueAsString(body);
            String url = (apiBase != null && !apiBase.isBlank()) ? apiBase : "https://api.anthropic.com/v1/messages";

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }

            HttpResponse<java.util.stream.Stream<String>> response = client.send(rb.build(), HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic 流式响应错误: " + response.statusCode());
            }

            StringBuilder fullContent = new StringBuilder();
            List<ToolCallRequest> toolCalls = new ArrayList<>();
            Map<String, Integer> usage = new HashMap<>();

            response.body().forEach(line -> {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) return;
                    try {
                        Map<String, Object> event = MAPPER.readValue(data, new TypeReference<>() {});
                        String type = (String) event.get("type");

                        if ("content_block_delta".equals(type)) {
                            Map<String, Object> delta = (Map<String, Object>) event.get("delta");
                            if ("text_delta".equals(delta.get("type"))) {
                                String text = (String) delta.get("text");
                                fullContent.append(text);
                                if (onDelta != null) onDelta.handle(text);
                            }
                        } else if ("message_delta".equals(type)) {
                            Map<String, Object> usageRaw = (Map<String, Object>) event.get("usage");
                            if (usageRaw != null) {
                                usage.put("completion_tokens", (Integer) usageRaw.get("output_tokens"));
                            }
                        } else if ("message_start".equals(type)) {
                            Map<String, Object> msg = (Map<String, Object>) event.get("message");
                            Map<String, Object> usageRaw = (Map<String, Object>) msg.get("usage");
                            if (usageRaw != null) {
                                usage.put("prompt_tokens", (Integer) usageRaw.get("input_tokens"));
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

            LLMResponse finalResp = new LLMResponse()
                    .setContent(fullContent.toString())
                    .setUsage(usage);
            
            if (onEnd != null) onEnd.handle(finalResp);
            return finalResp;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record ConvertedAnthropicMessages(Object system, List<Map<String, Object>> messages) {}
}
