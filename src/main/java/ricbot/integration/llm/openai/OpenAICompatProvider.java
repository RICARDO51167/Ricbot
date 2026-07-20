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

// 定义 OpenAICompatProvider 类，继承自 LLMProvider，用于处理兼容 OpenAI 格式的 API 调用
public class OpenAICompatProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatProvider.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    // 模型名称
    private final String model;
    // 额外的请求头映射
    private final Map<String, String> extraHeaders;
    // Provider 规格配置
    private final ProviderSpec spec;
    // HTTP 客户端实例
    private final HttpClient client;

    /**
     * 构造函数
     *
     * @param apiKey        API 密钥
     * @param apiBase       API 基础 URL
     * @param model         默认模型名称
     * @param extraHeaders  额外的请求头
     * @param spec          Provider 规格配置
     */
    public OpenAICompatProvider(
            String apiKey,
            String apiBase,
            String model,
            Map<String, String> extraHeaders,
            ProviderSpec spec
    ) {
        // 调用父类构造函数初始化 apiKey 和 apiBase
        super(apiKey, apiBase);
        this.model = model;
        // 初始化 extraHeaders，如果为 null 则创建新的 LinkedHashMap
        this.extraHeaders = extraHeaders != null ? new LinkedHashMap<>(extraHeaders) : new LinkedHashMap<>();
        this.spec = spec;
        // 构建 HttpClient 实例，设置连接超时为 20 秒，允许正常重定向
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 执行聊天请求（非流式）
     *
     * @param messages        消息列表
     * @param tools           工具列表
     * @param model           指定的模型名称
     * @param maxTokens       最大生成 token 数
     * @param temperature     温度参数
     * @param reasoningEffort 推理努力程度
     * @param toolChoice      工具选择策略
     * @return LLMResponse 响应对象
     * @throws Exception 异常
     */
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
        // 确定最终使用的模型名称：优先使用传入的 model，其次使用实例变量 model，最后使用默认模型
        String effectiveModel = model != null && !model.isBlank()
                ? model
                : (this.model != null && !this.model.isBlank() ? this.model : this.defaultModel);

        // 获取有效的 API 基础 URL
        String base = effectiveApiBase();
        // 检查 API 基础 URL 是否有效
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("OpenAI 兼容 provider 需要配置 api_base（或 provider 的默认 api_base）。");
        }
        // 检查模型名称是否有效
        if (effectiveModel == null || effectiveModel.isBlank()) {
            throw new IllegalStateException("OpenAI 兼容 provider 需要配置 model 名称。");
        }

        // 清理消息中的空内容
        List<Map<String, Object>> cleanMessages = sanitizeEmptyContent(messages);

        // 构建请求 payload
        Map<String, Object> payload = new LinkedHashMap<>();
        // 如果需要，去除模型前缀
        payload.put("model", stripModelPrefixIfNeeded(effectiveModel));
        payload.put("messages", cleanMessages);

        // 如果提供了工具，添加到 payload
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }
        // 如果提供了工具选择策略，添加到 payload
        if (toolChoice != null) {
            payload.put("tool_choice", toolChoice);
        }

        // 确定最终的 max_tokens 值
        Integer finalMaxTokens = maxTokens != null ? maxTokens : generation.getMaxTokens();
        if (finalMaxTokens != null && finalMaxTokens > 0) {
            payload.put("max_tokens", finalMaxTokens);
        }

        // 确定最终的 temperature 值
        Double finalTemp = temperature != null ? temperature : generation.getTemperature();
        payload.put("temperature", finalTemp);

        // 确定最终的 reasoning_effort 值
        String finalReasoning = reasoningEffort != null ? reasoningEffort : generation.getReasoningEffort();
        if (finalReasoning != null && !finalReasoning.isBlank()) {
            payload.put("reasoning_effort", finalReasoning);
        }

        HttpRequest request;
        try {
            // 将 payload 序列化为 JSON 字符串
            String json = MAPPER.writeValueAsString(payload);
            // 构建 HTTP 请求
            request = buildRequest(base, json);
        } catch (Exception e) {
            // 如果序列化失败，抛出运行时异常
            throw new RuntimeException("序列化聊天请求 payload 失败：" + e.getMessage(), e);
        }

        // 调试打印请求信息
        debugPrintRequest(request);
        // 执行请求并支持重试
        return runWithRetry(() -> doRequest(request));
    }

    /**
     * 执行实际的 HTTP 请求
     *
     * @param request HTTP 请求对象
     * @return LLMResponse 响应对象
     */
    private LLMResponse doRequest(HttpRequest request) {
        try {
            // 发送 HTTP 请求并获取响应
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            // 将响应头转换为 Map
            Map<String, Object> headerMap = headersToMap(res.headers().map());
            // 从响应头中提取 Retry-After 值
            Double retryAfter = extractRetryAfterFromHeaders(headerMap);

            // 如果状态码不在 2xx 范围内，处理错误
            if (status < 200 || status >= 300) {
                String body = res.body() != null ? res.body() : "";
                // 调试打印错误响应
                debugPrintErrorResponse(request, status, body);

                // 提取错误类型和代码
                String[] typeCode = extractErrorTypeCode(body);

                // 构建错误响应对象
                return new LLMResponse()
                        .setFinishReason("error")
                        .setContent(formatHttpError(request, status, body))
                        .setErrorStatusCode(status)
                        .setErrorType(typeCode[0])
                        .setErrorCode(typeCode[1])
                        .setRetryAfter(retryAfter)
                        .setErrorRetryAfterS(retryAfter);
            }

            // 解析成功的聊天完成响应
            LLMResponse parsed = parseChatCompletion(res.body());
            // 如果解析结果为错误且存在 Retry-After，设置重试时间
            if (retryAfter != null && Objects.equals(parsed.getFinishReason(), "error")) {
                parsed.setRetryAfter(retryAfter).setErrorRetryAfterS(retryAfter);
            }
            return parsed;
        } catch (java.net.http.HttpTimeoutException e) {
            // 处理超时异常
            return new LLMResponse()
                    .setFinishReason("error")
                    .setErrorKind("timeout")
                    .setContent("调用 OpenAI 兼容 API 超时：" + e.getMessage());
        } catch (Exception e) {
            // 处理其他异常
            String kind = classifyTransportErrorKind(e);
            return new LLMResponse()
                    .setFinishReason("error")
                    .setErrorKind(kind)
                    .setContent("调用 OpenAI 兼容 API 出错：" + e.getMessage());
        }
    }

    /**
     * 解析聊天完成响应
     *
     * @param json JSON 响应字符串
     * @return LLMResponse 响应对象
     * @throws Exception 异常
     */
    static LLMResponse parseChatCompletion(String json) throws Exception {
        // 检查响应体是否为空
        if (json == null) {
            return new LLMResponse().setFinishReason("error").setContent("OpenAI 兼容 API 返回空响应体。");
        }

        // 将 JSON 解析为 Map
        Map<String, Object> payload = MAPPER.readValue(json, JSON_OBJECT_TYPE);
        // 获取 choices 字段
        Object choicesObj = payload.get("choices");
        // 检查 choices 是否存在且非空
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return new LLMResponse().setFinishReason("error").setContent("响应格式无效：缺少 choices。");
        }

        // 获取第一个 choice
        Object firstObj = choices.get(0);
        // 检查第一个 choice 是否为 Map 类型
        if (!(firstObj instanceof Map<?, ?> firstRaw)) {
            return new LLMResponse().setFinishReason("error").setContent("响应格式无效：choices[0] 不是对象。");
        }

        Map<String, Object> first = castMap(firstRaw);

        // 获取 finish_reason，默认为 "stop"
        String finishReason = first.get("finish_reason") != null ? String.valueOf(first.get("finish_reason")) : "stop";

        // 获取 message 字段
        Map<String, Object> message = castMap(first.get("message"));
        // 提取内容
        String content = extractContent(message.get("content"));
        // 解析工具调用
        List<ToolCallRequest> toolCalls = parseToolCalls(message.get("tool_calls"));

        // 初始化 usage 映射
        Map<String, Integer> usage = new LinkedHashMap<>();
        // 获取 usage 字段
        Object usageObj = payload.get("usage");
        Map<String, Object> rawUsage = castMap(usageObj);
        if (!rawUsage.isEmpty()) {
            // 提取 prompt_tokens
            Integer prompt = toInt(rawUsage.get("prompt_tokens"));
            // 提取 completion_tokens
            Integer completion = toInt(rawUsage.get("completion_tokens"));
            // 提取 total_tokens
            Integer total = toInt(rawUsage.get("total_tokens"));
            if (prompt != null) usage.put("prompt_tokens", prompt);
            if (completion != null) usage.put("completion_tokens", completion);
            if (total != null) usage.put("total_tokens", total);
        }

        // 构建并返回响应对象
        return new LLMResponse()
                .setContent(content)
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason)
                .setUsage(usage);
    }

    /**
     * 执行流式聊天请求
     *
     * @param messages        消息列表
     * @param tools           工具列表
     * @param model           指定的模型名称
     * @param maxTokens       最大生成 token 数
     * @param temperature     温度参数
     * @param reasoningEffort 推理努力程度
     * @param toolChoice      工具选择策略
     * @param onDelta         增量数据处理器
     * @param onEnd           结束处理器
     * @return LLMResponse 响应对象
     * @throws Exception 异常
     */
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
        // 确定最终使用的模型名称
        String effectiveModel = model != null && !model.isBlank()
                ? model
                : (this.model != null && !this.model.isBlank() ? this.model : this.defaultModel);

        // 获取有效的 API 基础 URL
        String base = effectiveApiBase();
        // 清理消息中的空内容
        List<Map<String, Object>> cleanMessages = sanitizeEmptyContent(messages);

        // 构建请求 payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", stripModelPrefixIfNeeded(effectiveModel));
        payload.put("messages", cleanMessages);
        // 启用流式传输
        payload.put("stream", true);

        // 如果提供了工具，添加到 payload
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }
        // 如果提供了工具选择策略，添加到 payload
        if (toolChoice != null) {
            payload.put("tool_choice", toolChoice);
        }

        // 确定最终的 max_tokens 值
        Integer finalMaxTokens = maxTokens != null ? maxTokens : generation.getMaxTokens();
        if (finalMaxTokens != null && finalMaxTokens > 0) {
            payload.put("max_tokens", finalMaxTokens);
        }

        // 确定最终的 temperature 值
        Double finalTemp = temperature != null ? temperature : generation.getTemperature();
        payload.put("temperature", finalTemp);

        // 将 payload 序列化为 JSON 字符串
        String json = MAPPER.writeValueAsString(payload);
        // 构建 HTTP 请求
        HttpRequest request = buildRequest(base, json);

        // 发送 HTTP 请求并获取流式响应
        HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

        // 检查响应状态码
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI 兼容流式请求错误: " + response.statusCode());
        }

        // 消费 SSE 流并处理数据
        return OpenAIResponsesSupport.consumeSSE(response.body(), onDelta, onEnd);
    }

    /**
     * 构建 HTTP 请求
     *
     * @param base API 基础 URL
     * @param json 请求体 JSON 字符串
     * @return HttpRequest 请求对象
     */
    private HttpRequest buildRequest(String base, String json) {
        // 拼接完整的 URL
        String url = joinUrl(base, "/chat/completions");

        // 构建请求 builder
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ricbot/0.1");

        // 如果存在 API Key，添加 Authorization 头
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        }

        // 添加额外的请求头
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

        // 构建 POST 请求
        return b.POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    /**
     * 获取有效的 API 基础 URL
     *
     * @return 有效的 API 基础 URL
     */
    private String effectiveApiBase() {
        String base = this.apiBase;
        // 如果实例变量 apiBase 为空，尝试从 spec 中获取默认值
        if (base == null || base.isBlank()) {
            base = spec != null ? spec.getDefaultApiBase() : null;
        }
        if (base == null) {
            return null;
        }
        // 去除首尾空格
        base = base.trim();
        // 去除末尾的斜杠
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 如果以 /v1 结尾，直接返回
        if (base.endsWith("/v1")) {
            return base;
        }
        // 否则返回原值（后续拼接时会处理 /v1）
        return base;
    }

    /**
     * 根据需要去除模型名称前缀
     *
     * @param model 模型名称
     * @return 处理后的模型名称
     */
    private String stripModelPrefixIfNeeded(String model) {
        // 如果 spec 配置了需要去除前缀
        if (spec != null && Boolean.TRUE.equals(spec.isStripModelPrefix())) {
            int idx = model.indexOf('/');
            // 如果存在 '/' 且不在开头，截取后面的部分
            if (idx > 0) {
                return model.substring(idx + 1);
            }
        }
        return model;
    }

    /**
     * 拼接 URL
     *
     * @param base 基础 URL
     * @param path 路径
     * @return 完整 URL
     */
    private static String joinUrl(String base, String path) {
        // 去除 base 末尾的斜杠
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        // 确保 path 以斜杠开头
        String p = path.startsWith("/") ? path : "/" + path;
        // 如果 base 以 /v1 结尾，直接拼接 path
        if (b.endsWith("/v1")) {
            return b + p;
        }
        // 否则拼接 /v1 和 path
        return b + "/v1" + p;
    }

    /**
     * 将响应头 Map 转换为简化 Map
     *
     * @param headers 原始响应头
     * @return 简化后的响应头 Map
     */
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
            // 只取第一个值
            out.put(key, vals.get(0));
        }
        return out;
    }

    /**
     * 提取内容字符串
     *
     * @param contentObj 内容对象
     * @return 内容字符串
     */
    private static String extractContent(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        // 如果是字符串，直接返回
        if (contentObj instanceof String s) {
            return s;
        }
        // 如果是列表，处理多部分内容
        if (contentObj instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object type = ((Map<?, ?>) m).get("type");
                    // 只处理 text 类型的内容
                    if (type != null && !"text".equals(String.valueOf(type))) {
                        continue;
                    }
                    Object text = ((Map<?, ?>) m).get("text");
                    if (text != null) {
                        parts.add(String.valueOf(text));
                    }
                }
            }
            // 用换行符连接各部分
            return String.join("\n", parts);
        }
        // 其他情况转为字符串
        return String.valueOf(contentObj);
    }

    /**
     * 解析工具调用列表
     *
     * @param toolCallsObj 工具调用对象
     * @return 工具调用请求列表
     */
    private static List<ToolCallRequest> parseToolCalls(Object toolCallsObj) {
        // 如果不是列表或为空，返回空列表
        if (!(toolCallsObj instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<ToolCallRequest> out = new ArrayList<>();
        for (Object item : list) {
            // 如果元素不是 Map，跳过
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> call = castMap(raw);
            // 获取工具调用 ID
            String id = call.get("id") != null ? String.valueOf(call.get("id")) : null;

            // 获取 function 字段
            Map<String, Object> fn = castMap(call.get("function"));
            // 获取函数名称
            String name = fn.get("name") != null ? String.valueOf(fn.get("name")) : null;

            // 初始化参数字典
            Map<String, Object> args = new LinkedHashMap<>();
            Object argObj = fn.get("arguments");
            // 如果参数是字符串，尝试解析为 JSON
            if (argObj instanceof String s && !s.isBlank()) {
                try {
                    args = MAPPER.readValue(s, JSON_OBJECT_TYPE);
                } catch (Exception ignored) {
                    // 忽略解析异常
                }
            } else if (argObj instanceof Map<?, ?> m) {
                // 如果参数已经是 Map，直接转换
                args = castMap(m);
            }

            // 如果名称有效，添加工具调用请求
            if (name != null && !name.isBlank()) {
                out.add(new ToolCallRequest(id, name, args));
            }
        }

        return out;
    }

    /**
     * 将对象转换为 Map<String, Object>
     *
     * @param obj 输入对象
     * @return 转换后的 Map
     */
    private static Map<String, Object> castMap(Object obj) {
        return ricbot.infra.common.JsonMapUtils.asObjectMap(obj);
    }

    /**
     * 将对象转换为 Integer
     *
     * @param v 输入对象
     * @return 整数值，如果无法转换则返回 null
     */
    private static Integer toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (Exception ignored) {
                // 忽略解析异常
            }
        }
        return null;
    }

    /**
     * 分类传输错误类型
     *
     * @param e 异常对象
     * @return 错误类型字符串
     */
    private static String classifyTransportErrorKind(Exception e) {
        String name = e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        // 判断是否为超时错误
        if (name.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        // 默认为连接错误
        return "connection";
    }

    /**
     * 调试打印请求信息
     *
     * @param request HTTP 请求对象
     */
    private static void debugPrintRequest(HttpRequest request) {
        if (request == null || request.uri() == null) {
            return;
        }
        String url = request.uri().toString();
        boolean isChatCompletions = url.contains("/chat/completions");
        log.debug("ricbot llm 请求: {} {}", request.method(), url);
        log.debug("ricbot llm 端点检查: /chat/completions -> {}", isChatCompletions);
    }

    /**
     * 调试打印错误响应信息
     *
     * @param request HTTP 请求对象
     * @param status  状态码
     * @param body    响应体
     */
    private static void debugPrintErrorResponse(HttpRequest request, int status, String body) {
        String url = request != null && request.uri() != null ? request.uri().toString() : "";
        log.debug("ricbot llm 非 2xx 响应");
        log.debug("状态码: {}", status);
        log.debug("URL: {}", url);
        log.debug("端点: /chat/completions");
        log.debug("响应体:\n{}", body != null ? body : "");
    }

    /**
     * 格式化 HTTP 错误信息
     *
     * @param request HTTP 请求对象
     * @param status  状态码
     * @param body    响应体
     * @return 格式化的错误信息字符串
     */
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
