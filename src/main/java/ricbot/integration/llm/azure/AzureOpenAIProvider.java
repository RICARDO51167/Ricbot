package ricbot.integration.llm.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.OpenAIResponsesSupport;
import ricbot.integration.llm.api.ToolCallRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 对应 Python: AzureOpenAIProvider
 */
public class AzureOpenAIProvider extends LLMProvider {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    // HTTP 客户端实例，用于发送请求
    private final HttpClient client;
    // Azure OpenAI API 版本
    private final String apiVersion;

    /**
     * 构造函数，使用默认的 API 版本
     * @param apiKey API 密钥
     * @param apiBase API 基础 URL
     * @param defaultModel 默认模型名称
     */
    public AzureOpenAIProvider(String apiKey, String apiBase, String defaultModel) {
        // 调用另一个构造函数，指定默认 API 版本
        this(apiKey, apiBase, defaultModel, "2024-02-15-preview");
    }

    /**
     * 构造函数
     * @param apiKey API 密钥
     * @param apiBase API 基础 URL
     * @param defaultModel 默认模型名称
     * @param apiVersion API 版本
     */
    public AzureOpenAIProvider(String apiKey, String apiBase, String defaultModel, String apiVersion) {
        // 调用父类构造函数
        super(apiKey, apiBase);
        // 设置默认模型，如果未提供则使用 "gpt-4"
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-4";
        // 设置 API 版本
        this.apiVersion = apiVersion;
        // 构建 HTTP 客户端，设置连接超时时间为 20 秒
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * 发送聊天请求
     * @param messages 消息列表
     * @param tools 工具列表
     * @param model 模型名称
     * @param maxTokens 最大令牌数
     * @param temperature 温度参数
     * @param reasoningEffort 推理努力程度（当前未使用）
     * @param toolChoice 工具选择策略
     * @return LLM 响应
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
        // 确定使用的部署模型，如果未提供则使用默认模型
        String deployment = (model != null && !model.isBlank()) ? model : defaultModel;
        
        // 创建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        // 添加消息，并清理空内容
        body.put("messages", sanitizeEmptyContent(messages));
        // 如果提供了工具，则添加工具和工具选择策略
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }
        // 添加最大令牌数，如果未提供则使用生成配置中的默认值
        body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
        // 添加温度参数，如果未提供则使用生成配置中的默认值
        body.put("temperature", temperature != null ? temperature : generation.getTemperature());

        // 将请求体转换为 JSON 字符串
        String json = MAPPER.writeValueAsString(body);
        
        // 构建 Azure OpenAI API URL
        // Azure URL format: {apiBase}/openai/deployments/{deployment}/chat/completions?api-version={apiVersion}
        String baseUrl = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        String url = baseUrl + "openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;

        // 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // 发送请求并获取响应
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // 如果响应状态码不是 200，则返回错误响应
        if (response.statusCode() != 200) {
            return new LLMResponse()
                    .setFinishReason("error")
                    .setContent("Azure OpenAI 错误: " + response.statusCode() + " " + response.body());
        }

        // 解析响应并返回
        return parseAzureResponse(response.body());
    }

    /**
     * 解析 Azure OpenAI 响应
     * @param json 响应 JSON 字符串
     * @return LLM 响应
     * @throws Exception 异常
     */
    private LLMResponse parseAzureResponse(String json) throws Exception {
        // 将 JSON 字符串解析为 Map
        Map<String, Object> map = MAPPER.readValue(json, JSON_OBJECT_TYPE);
        // 获取 choices 列表
        List<Map<String, Object>> choices = asObjectMapList(map.get("choices"));
        // 如果 choices 为空，则返回错误响应
        if (choices == null || choices.isEmpty()) {
            return new LLMResponse().setFinishReason("error").setContent("Azure OpenAI 返回的选择为空");
        }

        // 获取第一个 choice
        Map<String, Object> first = choices.get(0);
        // 获取 message 对象
        Map<String, Object> message = asObjectMap(first.get("message"));
        if (message == null) {
            return new LLMResponse().setFinishReason("error").setContent("Azure OpenAI 返回的消息为空");
        }
        // 获取内容
        Object contentObj = message.get("content");
        String content = contentObj != null ? String.valueOf(contentObj) : "";
        // 获取结束原因
        Object finishReasonObj = first.get("finish_reason");
        String finishReason = finishReasonObj != null ? String.valueOf(finishReasonObj) : null;

        // 初始化工具调用列表
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        // 获取原始工具调用数据
        List<Map<String, Object>> tcRaw = asObjectMapList(message.get("tool_calls"));
        // 如果存在工具调用，则解析
        if (tcRaw != null) {
            for (Map<String, Object> tc : tcRaw) {
                // 获取工具调用 ID
                String id = stringValue(tc.get("id"));
                // 获取函数对象
                Map<String, Object> func = asObjectMap(tc.get("function"));
                if (func == null) {
                    continue;
                }
                // 获取函数名称
                String name = stringValue(func.get("name"));
                // 获取参数字符串
                String argsJson = stringValue(func.get("arguments"));
                // 将参数字符串解析为 Map
                Map<String, Object> args = parseToolArguments(argsJson);
                // 添加工具调用请求到列表
                toolCalls.add(new ToolCallRequest(id, name, args));
            }
        }

        // 获取使用情况数据
        Map<String, Object> usageRaw = asObjectMap(map.get("usage"));
        Map<String, Integer> usage = new HashMap<>();
        // 如果存在使用情况数据，则提取提示令牌和完成令牌数量
        if (usageRaw != null) {
            Integer promptTokens = toInt(usageRaw.get("prompt_tokens"));
            Integer completionTokens = toInt(usageRaw.get("completion_tokens"));
            if (promptTokens != null) {
                usage.put("prompt_tokens", promptTokens);
            }
            if (completionTokens != null) {
                usage.put("completion_tokens", completionTokens);
            }
        }

        // 构建并返回 LLM 响应
        return new LLMResponse()
                .setContent(content)
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason)
                .setUsage(usage);
    }

    /**
     * 发送流式聊天请求
     * @param messages 消息列表
     * @param tools 工具列表
     * @param model 模型名称
     * @param maxTokens 最大令牌数
     * @param temperature 温度参数
     * @param reasoningEffort 推理努力程度（当前未使用）
     * @param toolChoice 工具选择策略
     * @param onDelta 增量回调
     * @param onEnd 结束回调
     * @return LLM 响应
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
        // 确定使用的部署模型，如果未提供则使用默认模型
        String deployment = (model != null && !model.isBlank()) ? model : defaultModel;

        // 创建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        // 添加消息，并清理空内容
        body.put("messages", sanitizeEmptyContent(messages));
        // 启用流式传输
        body.put("stream", true);
        // 如果提供了工具，则添加工具和工具选择策略
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }
        // 添加最大令牌数，如果未提供则使用生成配置中的默认值
        body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
        // 添加温度参数，如果未提供则使用生成配置中的默认值
        body.put("temperature", temperature != null ? temperature : generation.getTemperature());

        // 将请求体转换为 JSON 字符串
        String json = MAPPER.writeValueAsString(body);

        // 构建 Azure OpenAI API URL
        // Azure URL format: {apiBase}/openai/deployments/{deployment}/chat/completions?api-version={apiVersion}
        String baseUrl = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        String url = baseUrl + "openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;

        // 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // 发送请求并获取流式响应
        HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

        // 如果响应状态码不是 200，则抛出异常
        if (response.statusCode() != 200) {
            throw new RuntimeException("Azure OpenAI 流式请求错误: " + response.statusCode());
        }

        // 消费 SSE 流并返回结果
        return OpenAIResponsesSupport.consumeSSE(response.body(), onDelta, onEnd);
    }

    private static Map<String, Object> parseToolArguments(String argsJson) throws Exception {
        if (argsJson == null || argsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(argsJson, JSON_OBJECT_TYPE);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> asObjectMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = asObjectMap(item);
            if (map != null) {
                out.add(map);
            }
        }
        return out;
    }
}
