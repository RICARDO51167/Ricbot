package ricbot.integration.llm.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import ricbot.integration.llm.provider.ProviderSpec;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.OpenAIResponsesSupport;
import ricbot.integration.llm.api.ToolCallRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 兼容格式的 LLM Provider 实现
 */
public class OpenAICompatProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatProvider.class);

    private final String model;
    private final Map<String, String> extraHeaders;
    private final ProviderSpec spec;
    private final HttpClient client;

    public OpenAICompatProvider(
            String apiKey,
            String apiBase,
            String model,
            Map<String, String> extraHeaders,
            ProviderSpec spec
    ) {
        super(apiKey, apiBase);
        this.model = model;
        this.extraHeaders = extraHeaders != null ? new LinkedHashMap<>(extraHeaders) : new LinkedHashMap<>();
        this.spec = spec;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
        String effectiveModel = model != null && !model.isBlank()
                ? model
                : (this.model != null && !this.model.isBlank() ? this.model : this.defaultModel);

        String base = effectiveApiBase();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("OpenAI 兼容 provider 需要配置 api_base（或 provider 的默认 api_base）。");
        }
        if (effectiveModel == null || effectiveModel.isBlank()) {
            throw new IllegalStateException("OpenAI 兼容 provider 需要配置 model 名称。");
        }

        List<Map<String, Object>> cleanMessages = sanitizeEmptyContent(messages);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", stripModelPrefixIfNeeded(effectiveModel));
        payload.put("messages", cleanMessages);

        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }
        if (toolChoice != null) {
            payload.put("tool_choice", toolChoice);
        }

        Integer finalMaxTokens = maxTokens != null ? maxTokens : generation.getMaxTokens();
        if (finalMaxTokens != null && finalMaxTokens > 0) {
            payload.put("max_tokens", finalMaxTokens);
        }

        Double finalTemp = temperature != null ? temperature : generation.getTemperature();
        payload.put("temperature", finalTemp);

        String finalReasoning = reasoningEffort != null ? reasoningEffort : generation.getReasoningEffort();
        if (finalReasoning != null && !finalReasoning.isBlank()) {
            payload.put("reasoning_effort", finalReasoning);
        }

        HttpRequest request;
        try {
            String json = MAPPER.writeValueAsString(payload);
            request = buildRequest(base, json);
        } catch (Exception e) {
            throw new RuntimeException("序列化聊天请求 payload 失败：" + e.getMessage(), e);
        }

        debugPrintRequest(request);
        return runWithRetry(() -> doRequest(request));
    }

    private LLMResponse doRequest(HttpRequest request) {
        try {
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            Map<String, Object> headerMap = headersToMap(res.headers().map());
            Double retryAfter = extractRetryAfterFromHeaders(headerMap);

            if (status < 200 || status >= 300) {
                String body = res.body() != null ? res.body() : "";
                debugPrintErrorResponse(request, status, body);

                String[] typeCode = extractErrorTypeCode(body);

                return new LLMResponse()
                        .setFinishReason("error")
                        .setContent(formatHttpError(request, status, body))
                        .setErrorStatusCode(status)
                        .setErrorType(typeCode[0])
                        .setErrorCode(typeCode[1])
                        .setRetryAfter(retryAfter)
                        .setErrorRetryAfterS(retryAfter);
            }

            LLMResponse parsed = parseChatCompletion(res.body());
            if (retryAfter != null && Objects.equals(parsed.getFinishReason(), "error")) {
                parsed.setRetryAfter(retryAfter).setErrorRetryAfterS(retryAfter);
            }
            return parsed;
        } catch (java.net.http.HttpTimeoutException e) {
            return new LLMResponse()
                    .setFinishReason("error")
                    .setErrorKind("timeout")
                    .setContent("调用 OpenAI 兼容 API 超时：" + e.getMessage());
        } catch (Exception e) {
            String kind = classifyTransportErrorKind(e);
            return new LLMResponse()
                    .setFinishReason("error")
                    .setErrorKind(kind)
                    .setContent("调用 OpenAI 兼容 API 出错：" + e.getMessage());
        }
    }

    static LLMResponse parseChatCompletion(String json) throws Exception {
        if (json == null) {
            return new LLMResponse().setFinishReason("error").setContent("OpenAI 兼容 API 返回空响应体。");
        }

        Map<String, Object> payload = MAPPER.readValue(json, new TypeReference<>() {});
        Object choicesObj = payload.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return new LLMResponse().setFinishReason("error").setContent("响应格式无效：缺少 choices。");
        }

        Object firstObj = choices.get(0);
        if (!(firstObj instanceof Map<?, ?> firstRaw)) {
            return new LLMResponse().setFinishReason("error").setContent("响应格式无效：choices[0] 不是对象。");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) firstRaw;

        String finishReason = first.get("finish_reason") != null ? String.valueOf(first.get("finish_reason")) : "stop";

        Map<String, Object> message = castMap(first.get("message"));
        String content = extractContent(message.get("content"));
        List<ToolCallRequest> toolCalls = parseToolCalls(message.get("tool_calls"));

        Map<String, Integer> usage = new LinkedHashMap<>();
        Object usageObj = payload.get("usage");
        if (usageObj instanceof Map<?, ?> rawUsage) {
            Integer prompt = toInt(rawUsage.get("prompt_tokens"));
            Integer completion = toInt(rawUsage.get("completion_tokens"));
            Integer total = toInt(rawUsage.get("total_tokens"));
            if (prompt != null) usage.put("prompt_tokens", prompt);
            if (completion != null) usage.put("completion_tokens", completion);
            if (total != null) usage.put("total_tokens", total);
        }

        return new LLMResponse()
                .setContent(content)
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason)
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
    ) throws Exception {
        String effectiveModel = model != null && !model.isBlank()
                ? model
                : (this.model != null && !this.model.isBlank() ? this.model : this.defaultModel);

        String base = effectiveApiBase();
        List<Map<String, Object>> cleanMessages = sanitizeEmptyContent(messages);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", stripModelPrefixIfNeeded(effectiveModel));
        payload.put("messages", cleanMessages);
        payload.put("stream", true);

        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }
        if (toolChoice != null) {
            payload.put("tool_choice", toolChoice);
        }

        Integer finalMaxTokens = maxTokens != null ? maxTokens : generation.getMaxTokens();
        if (finalMaxTokens != null && finalMaxTokens > 0) {
            payload.put("max_tokens", finalMaxTokens);
        }

        Double finalTemp = temperature != null ? temperature : generation.getTemperature();
        payload.put("temperature", finalTemp);

        String json = MAPPER.writeValueAsString(payload);
        HttpRequest request = buildRequest(base, json);

        HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI 兼容流式请求错误: " + response.statusCode());
        }

        return OpenAIResponsesSupport.consumeSSE(response.body(), onDelta, onEnd);
    }

    private HttpRequest buildRequest(String base, String json) {
        String url = joinUrl(base, "/chat/completions");

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ricbot/0.1");

        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        }

        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String val = entry.getValue();
            if (val == null) {
                continue;
            }
            b.header(entry.getKey(), val);
        }

        return b.POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private String effectiveApiBase() {
        String base = this.apiBase;
        if (base == null || base.isBlank()) {
            base = spec != null ? spec.getDefaultApiBase() : null;
        }
        if (base == null) {
            return null;
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            return base;
        }
        return base;
    }

    private String stripModelPrefixIfNeeded(String model) {
        if (spec != null && Boolean.TRUE.equals(spec.isStripModelPrefix())) {
            int idx = model.indexOf('/');
            if (idx > 0) {
                return model.substring(idx + 1);
            }
        }
        return model;
    }

    private static String joinUrl(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        if (b.endsWith("/v1")) {
            return b + p;
        }
        return b + "/v1" + p;
    }

    private static Map<String, Object> headersToMap(Map<String, List<String>> headers) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String key = e.getKey();
            List<String> vals = e.getValue();
            if (vals == null || vals.isEmpty()) {
                continue;
            }
            out.put(key, vals.get(0));
        }
        return out;
    }

    private static String extractContent(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String s) {
            return s;
        }
        if (contentObj instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object type = ((Map<?, ?>) m).get("type");
                    if (type != null && !"text".equals(String.valueOf(type))) {
                        continue;
                    }
                    Object text = ((Map<?, ?>) m).get("text");
                    if (text != null) {
                        parts.add(String.valueOf(text));
                    }
                }
            }
            return String.join("\n", parts);
        }
        return String.valueOf(contentObj);
    }

    private static List<ToolCallRequest> parseToolCalls(Object toolCallsObj) {
        if (!(toolCallsObj instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<ToolCallRequest> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> call = (Map<String, Object>) raw;
            String id = call.get("id") != null ? String.valueOf(call.get("id")) : null;

            Map<String, Object> fn = castMap(call.get("function"));
            String name = fn.get("name") != null ? String.valueOf(fn.get("name")) : null;

            Map<String, Object> args = new LinkedHashMap<>();
            Object argObj = fn.get("arguments");
            if (argObj instanceof String s && !s.isBlank()) {
                try {
                    args = MAPPER.readValue(s, new TypeReference<>() {});
                } catch (Exception ignored) {
                    // ignore
                }
            } else if (argObj instanceof Map<?, ?> m) {
                args = castMap(m);
            }

            if (name != null && !name.isBlank()) {
                out.add(new ToolCallRequest(id, name, args));
            }
        }

        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object obj) {
        if (obj instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return null;
    }

    private static String classifyTransportErrorKind(Exception e) {
        String name = e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        if (name.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        return "connection";
    }

    private static void debugPrintRequest(HttpRequest request) {
        if (request == null || request.uri() == null) {
            return;
        }
        String url = request.uri().toString();
        boolean isChatCompletions = url.contains("/chat/completions");
        log.debug("ricbot llm 请求: {} {}", request.method(), url);
        log.debug("ricbot llm 端点检查: /chat/completions -> {}", isChatCompletions);
    }

    private static void debugPrintErrorResponse(HttpRequest request, int status, String body) {
        String url = request != null && request.uri() != null ? request.uri().toString() : "";
        log.debug("ricbot llm 非 2xx 响应");
        log.debug("状态码: {}", status);
        log.debug("URL: {}", url);
        log.debug("端点: /chat/completions");
        log.debug("响应体:\n{}", body != null ? body : "");
    }

    private static String formatHttpError(HttpRequest request, int status, String body) {
        String url = request != null && request.uri() != null ? request.uri().toString() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("来自 OpenAI 兼容 API 的 HTTP ").append(status).append(" 错误\n");
        if (!url.isBlank()) {
            sb.append("URL: ").append(url).append("\n");
        }
        sb.append("端点: /chat/completions\n");
        sb.append("响应体:\n");
        sb.append(body != null ? body : "");
        return sb.toString();
    }
}
