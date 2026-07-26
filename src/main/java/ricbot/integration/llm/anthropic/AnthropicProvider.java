package ricbot.integration.llm.anthropic;

import com.fasterxml.jackson.core.type.TypeReference;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMFailureException;
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
 * 对应 Python: AnthropicProvider
 */
public class AnthropicProvider extends LLMProvider {

    // 定义用于生成随机 ID 的字符集（字母和数字）
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    // 初始化安全随机数生成器
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    // 存储额外的 HTTP 请求头
    private final Map<String, String> extraHeaders;
    // HTTP 客户端实例，用于发送请求
    private final HttpClient client;

    /**
     * 构造函数，初始化 AnthropicProvider
     *
     * @param apiKey        API 密钥
     * @param apiBase       API 基础 URL
     * @param defaultModel  默认模型名称
     * @param extraHeaders  额外的请求头
     */
    public AnthropicProvider(
            String apiKey,
            String apiBase,
            String defaultModel,
            Map<String, String> extraHeaders
    ) {
        // 调用父类构造函数，传入 apiKey 和 apiBase
        super(apiKey, apiBase);
        // 设置默认模型，如果未提供则使用 claude-3-5-sonnet-20240620
        this.defaultModel = defaultModel != null ? defaultModel : "claude-3-5-sonnet-20240620";
        // 初始化额外请求头，如果为 null 则创建空的 LinkedHashMap
        this.extraHeaders = extraHeaders != null ? new LinkedHashMap<>(extraHeaders) : new LinkedHashMap<>();
        // 构建 HttpClient，设置连接超时时间为 20 秒
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * 生成工具调用的唯一 ID
     *
     * @return 生成的工具 ID，格式为 toolu_ 后跟 22 位随机字符
     */
    private static String genToolId() {
        // 初始化 StringBuilder，前缀为 "toolu_"
        StringBuilder sb = new StringBuilder("toolu_");
        // 循环 22 次，每次追加一个随机字符
        for (int i = 0; i < 22; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        // 返回生成的字符串
        return sb.toString();
    }

    /**
     * 移除模型名称中的 "anthropic/" 前缀
     *
     * @param model 模型名称
     * @return 处理后的模型名称
     */
    public static String stripPrefix(String model) {
        // 如果模型名称不为空且以 "anthropic/" 开头
        if (model != null && model.startsWith("anthropic/")) {
            // 返回去除前缀后的子字符串
            return model.substring("anthropic/".length());
        }
        // 否则返回原模型名称
        return model;
    }

    /**
     * 将通用消息列表转换为 Anthropic 格式的消息
     *
     * @param messages 原始消息列表
     * @return 转换后的 Anthropic 消息对象
     */
    public ConvertedAnthropicMessages convertMessages(List<Map<String, Object>> messages) {
        // 初始化 system 消息内容
        Object system = null;
        // 初始化原始消息列表
        List<Map<String, Object>> raw = new ArrayList<>();

        // 遍历所有消息
        for (Map<String, Object> msg : messages) {
            // 获取消息角色，默认为空字符串
            String role = String.valueOf(msg.getOrDefault("role", ""));
            // 获取消息内容
            Object content = msg.get("content");

            // 如果角色是 system
            if ("system".equals(role)) {
                // 将内容赋值给 system 变量
                system = content;
                // 跳过当前循环
                continue;
            }

            // 如果角色是 tool
            if ("tool".equals(role)) {
                // 生成工具结果块
                Map<String, Object> block = toolResultBlock(msg);
                // 如果 raw 列表不为空且最后一个消息的角色是 user
                if (!raw.isEmpty() && "user".equals(raw.get(raw.size() - 1).get("role"))) {
                    // 获取最后一个消息的内容
                    Object prevContent = raw.get(raw.size() - 1).get("content");
                    // 如果前一个内容是列表
                    if (prevContent instanceof List<?> prevList) {
                        List<Object> updatedContent = new ArrayList<>(prevList);
                        // 将工具结果块添加到列表中
                        updatedContent.add(block);
                        raw.get(raw.size() - 1).put("content", updatedContent);
                    } else {
                        // 否则，将前一个内容和工具结果块组合成新的列表
                        raw.get(raw.size() - 1).put("content", new ArrayList<>(List.of(
                                Map.of("type", "text", "text", prevContent != null ? String.valueOf(prevContent) : ""),
                                block
                        )));
                    }
                } else {
                    // 否则，添加一个新的 user 消息，包含工具结果块
                    raw.add(new LinkedHashMap<>(Map.of(
                            "role", "user",
                            "content", new ArrayList<>(List.of(block))
                    )));
                }
                // 跳过当前循环
                continue;
            }

            // 如果角色是 assistant
            if ("assistant".equals(role)) {
                // 添加 assistant 消息，内容通过 assistantBlocks 方法转换
                raw.add(new LinkedHashMap<>(Map.of(
                        "role", "assistant",
                        "content", assistantBlocks(msg)
                )));
                // 跳过当前循环
                continue;
            }

            // 如果角色是 user
            if ("user".equals(role)) {
                // 添加 user 消息，内容通过 convertUserContent 方法转换
                raw.add(new LinkedHashMap<>(Map.of(
                        "role", "user",
                        "content", convertUserContent(content)
                )));
            }
        }

        // 返回转换后的 Anthropic 消息对象，合并连续相同角色的消息
        return new ConvertedAnthropicMessages(system, mergeConsecutive(raw));
    }

    /**
     * 生成工具结果块
     *
     * @param msg 工具消息
     * @return 工具结果块 Map
     */
    public static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        // 获取消息内容
        Object content = msg.get("content");
        // 初始化结果块
        Map<String, Object> block = new LinkedHashMap<>();
        // 设置类型为 tool_result
        block.put("type", "tool_result");
        // 设置工具使用 ID，默认为空字符串
        block.put("tool_use_id", msg.getOrDefault("tool_call_id", ""));
        // 设置内容，如果为 null 则设为空字符串
        block.put("content", content != null ? content : "");
        // 返回结果块
        return block;
    }

    /**
     * 将 assistant 消息转换为 Anthropic 格式的块列表
     *
     * @param msg assistant 消息
     * @return 块列表
     */
    public static List<Map<String, Object>> assistantBlocks(Map<String, Object> msg) {
        // 初始化块列表
        List<Map<String, Object>> blocks = new ArrayList<>();
        // 获取消息内容
        Object content = msg.get("content");

        // 如果内容是字符串且非空
        if (content instanceof String s && !s.isBlank()) {
            // 添加文本块
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", s)));
        }

        // 获取工具调用对象
        Object toolCallsObj = msg.get("tool_calls");
        // 如果工具调用对象是列表
        if (toolCallsObj instanceof List<?> toolCalls) {
            // 遍历每个工具调用
            for (Object tc : toolCalls) {
                // 如果不是 Map 类型，跳过
                if (!(tc instanceof Map<?, ?> rawTc)) continue;
                // 转换为 Map
                Map<String, Object> tcMap = copyObjectMap(rawTc);
                // 获取函数信息，默认为空 Map
                Map<String, Object> func = asObjectMap(tcMap.get("function"));
                if (func == null) {
                    func = Collections.emptyMap();
                }

                // 初始化工具输入参数
                Map<String, Object> input = new HashMap<>();
                // 获取参数字符串或对象
                Object args = func.get("arguments");
                // 如果参数是字符串
                if (args instanceof String s) {
                    try {
                        // 尝试解析 JSON 字符串为 Map
                        input = MAPPER.readValue(s, JSON_OBJECT_TYPE);
                    } catch (Exception ignored) {}
                } else if (args instanceof Map<?, ?> m) {
                    // 如果参数已经是 Map，直接转换
                    input = copyObjectMap(m);
                }

                // 初始化工具使用块
                Map<String, Object> block = new LinkedHashMap<>();
                // 设置类型为 tool_use
                block.put("type", "tool_use");
                // 设置 ID，如果不存在则生成
                block.put("id", tcMap.getOrDefault("id", genToolId()));
                // 设置工具名称
                block.put("name", func.getOrDefault("name", ""));
                // 设置输入参数
                block.put("input", input);
                // 添加到块列表
                blocks.add(block);
            }
        }

        // 如果块列表为空，添加一个空文本块
        if (blocks.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", ""));
        }
        // 返回块列表
        return blocks;
    }

    /**
     * 转换用户消息内容为 Anthropic 格式
     *
     * @param content 用户消息内容
     * @return 转换后的内容
     */
    public Object convertUserContent(Object content) {
        // 如果内容是字符串或 null
        if (content instanceof String || content == null) {
            // 返回内容，如果为 null 则返回空字符串
            return content != null ? content : "";
        }
        // 如果内容不是列表
        if (!(content instanceof List<?> list)) {
            // 转换为字符串返回
            return String.valueOf(content);
        }
        // 初始化转换后的列表
        List<Object> converted = new ArrayList<>();
        // 遍历列表中的每个项
        for (Object itemObj : list) {
            // 如果项不是 Map 类型，跳过
            if (!(itemObj instanceof Map<?, ?> rawItem)) {
                continue;
            }
            // 转换为 Map
            Map<String, Object> item = copyObjectMap(rawItem);
            // 获取项的类型
            String type = String.valueOf(item.get("type"));
            // 如果类型是 text
            if ("text".equals(type)) {
                // 添加文本块
                converted.add(new LinkedHashMap<>(Map.of(
                        "type", "text",
                        "text", String.valueOf(item.getOrDefault("text", ""))
                )));
            } else if ("image_url".equals(type)) {
                // 如果类型是 image_url
                // 获取 image_url 对象
                Object imageUrlObj = item.get("image_url");
                // 如果 image_url 是 Map 类型
                if (imageUrlObj instanceof Map<?, ?> rawImageUrl) {
                    Map<String, Object> imageUrl = copyObjectMap(rawImageUrl);
                    // 获取 URL 字符串
                    String url = String.valueOf(imageUrl.getOrDefault("url", ""));
                    // 尝试将 URL 转换为 Anthropic 图像块
                    Map<String, Object> imageBlock = convertImageUrlToAnthropic(url);
                    // 如果转换成功
                    if (imageBlock != null) {
                        // 添加图像块
                        converted.add(imageBlock);
                    } else if (url != null && !url.isBlank()) {
                        // 如果转换失败但 URL 非空，添加文本占位符
                        converted.add(new LinkedHashMap<>(Map.of(
                                "type", "text",
                                "text", "[image: " + url + "]"
                        )));
                    }
                }
            }
        }
        // 如果转换后的列表为空，返回包含空文本块的列表
        if (converted.isEmpty()) {
            return List.of(Map.of("type", "text", "text", ""));
        }
        // 返回转换后的列表
        return converted;
    }

    /**
     * 将图像 URL 转换为 Anthropic 格式的图像块
     *
     * @param url 图像 URL
     * @return 图像块 Map，如果无法转换则返回 null
     */
    private static Map<String, Object> convertImageUrlToAnthropic(String url) {
        // 如果 URL 为 null 或为空
        if (url == null || url.isBlank()) {
            // 返回 null
            return null;
        }
        // 去除首尾空格
        String trimmed = url.trim();
        // 如果不是 data: URI 格式
        if (!trimmed.startsWith("data:")) {
            // 返回 null
            return null;
        }

        // 查找分号和逗号的位置
        int semi = trimmed.indexOf(';');
        int comma = trimmed.indexOf(',');
        // 如果格式不正确
        if (semi < 0 || comma < 0 || comma <= semi) {
            // 返回 null
            return null;
        }

        // 提取媒体类型
        String mediaType = trimmed.substring("data:".length(), semi);
        // 提取元数据（应为 base64）
        String meta = trimmed.substring(semi + 1, comma);
        // 如果不是 base64 编码
        if (!"base64".equalsIgnoreCase(meta)) {
            // 返回 null
            return null;
        }
        // 提取数据部分
        String data = trimmed.substring(comma + 1);
        // 如果媒体类型或数据为空
        if (mediaType.isBlank() || data.isBlank()) {
            // 返回 null
            return null;
        }

        // 初始化 source Map
        Map<String, Object> source = new LinkedHashMap<>();
        // 设置 source 类型为 base64
        source.put("type", "base64");
        // 设置媒体类型
        source.put("media_type", mediaType);
        // 设置数据
        source.put("data", data);

        // 初始化图像块 Map
        Map<String, Object> block = new LinkedHashMap<>();
        // 设置块类型为 image
        block.put("type", "image");
        // 设置 source
        block.put("source", source);
        // 返回图像块
        return block;
    }

    /**
     * 合并连续相同角色的消息
     *
     * @param msgs 消息列表
     * @return 合并后的消息列表
     */
    public static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> msgs) {
        // 初始化合并后的列表
        List<Map<String, Object>> merged = new ArrayList<>();
        // 遍历所有消息
        for (Map<String, Object> msg : msgs) {
            // 如果合并列表不为空且当前消息角色与上一个消息角色相同
            if (!merged.isEmpty() && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {
                // 获取上一个消息的内容
                Object prevC = merged.get(merged.size() - 1).get("content");
                // 获取当前消息的内容
                Object curC = msg.get("content");

                // 将上一个内容转换为列表
                List<Object> prevList = (prevC instanceof List<?> l) ? new ArrayList<>(l) : new ArrayList<>(List.of(Map.of("type", "text", "text", prevC)));
                // 将当前内容转换为列表
                List<Object> curList = (curC instanceof List<?> l) ? new ArrayList<>(l) : new ArrayList<>(List.of(Map.of("type", "text", "text", curC)));
                // 将当前内容列表添加到上一个内容列表
                prevList.addAll(curList);
                // 更新上一个消息的内容
                merged.get(merged.size() - 1).put("content", prevList);
            } else {
                // 否则，添加当前消息到合并列表
                merged.add(new LinkedHashMap<>(msg));
            }
        }
        // 返回合并后的列表
        return merged;
    }

    /**
     * 转换工具列表为 Anthropic 格式
     *
     * @param tools 工具列表
     * @return 转换后的工具列表
     */
    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        // 如果工具列表为 null，返回 null
        if (tools == null) return null;
        // 初始化结果列表
        List<Map<String, Object>> result = new ArrayList<>();
        // 遍历每个工具
        for (Map<String, Object> tool : tools) {
            // 获取函数定义，如果不存在则使用工具本身
            Map<String, Object> func = asObjectMap(tool.get("function"));
            if (func == null) {
                func = tool;
            }
            // 初始化条目 Map
            Map<String, Object> entry = new LinkedHashMap<>();
            // 设置名称
            entry.put("name", func.get("name"));
            // 设置描述
            entry.put("description", func.get("description"));
            // 设置输入 schema
            entry.put("input_schema", func.get("parameters"));
            // 添加到结果列表
            result.add(entry);
        }
        // 返回结果列表
        return result;
    }

    /**
     * 执行聊天请求
     *
     * @param messages        消息列表
     * @param tools           工具列表
     * @param model           模型名称
     * @param maxTokens       最大 token 数
     * @param temperature     温度参数
     * @param reasoningEffort 推理努力程度
     * @param toolChoice      工具选择策略
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
        // 转换消息格式
        ConvertedAnthropicMessages converted = convertMessages(messages);
        // 处理模型名称，去除前缀并使用默认值
        String finalModel = stripPrefix(model != null ? model : defaultModel);

        // 初始化请求体
        Map<String, Object> body = new LinkedHashMap<>();
        // 设置模型
        body.put("model", finalModel);
        // 如果存在 system 消息，设置 system
        if (converted.system() != null) {
            body.put("system", converted.system());
        }
        // 设置消息列表
        body.put("messages", converted.messages());
        // 设置最大 token 数，如果未提供则使用配置中的默认值
        body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());

        // 如果存在工具
        if (tools != null && !tools.isEmpty()) {
            // 设置工具列表
            body.put("tools", convertTools(tools));
        }

        // 将请求体序列化为 JSON 字符串
        String json = MAPPER.writeValueAsString(body);
        // 确定 API URL
        String url = (apiBase != null && !apiBase.isBlank()) ? apiBase : "https://api.anthropic.com/v1/messages";
        
        // 构建 HTTP 请求
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        // 添加额外的请求头
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }

        // 发送请求并获取响应
        HttpResponse<String> response = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        
        // 如果状态码不是 200
        if (response.statusCode() != 200) {
            // 返回错误响应
            return LLMFailureException.requireSuccess(new LLMResponse()
                    .setFinishReason("error")
                    .setErrorStatusCode(response.statusCode())
                    .setContent("Anthropic API 错误: " + response.statusCode() + " " + response.body()));
        }

        // 解析并返回 Anthropic 响应
        return LLMFailureException.requireSuccess(parseAnthropicResponse(response.body()));
    }

    /**
     * 解析 Anthropic API 响应
     *
     * @param json 响应 JSON 字符串
     * @return LLM 响应对象
     * @throws Exception 异常
     */
    private LLMResponse parseAnthropicResponse(String json) throws Exception {
        // 将 JSON 字符串解析为 Map
        Map<String, Object> map = MAPPER.readValue(json, JSON_OBJECT_TYPE);
        
        // 初始化内容 StringBuilder
        StringBuilder content = new StringBuilder();
        // 初始化工具调用列表
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        
        // 获取内容列表
        List<Map<String, Object>> contentList = asObjectMapList(map.get("content"));
        // 如果内容列表不为 null
        if (contentList != null) {
            // 遍历每个内容块
            for (Map<String, Object> block : contentList) {
                // 获取块类型
                String type = (String) block.get("type");
                // 如果是文本块
                if ("text".equals(type)) {
                    // 追加文本内容
                    content.append((String) block.get("text"));
                } else if ("tool_use".equals(type)) {
                    // 如果是工具使用块
                    // 获取 ID
                    String id = (String) block.get("id");
                    // 获取名称
                    String name = (String) block.get("name");
                    // 获取输入参数
                    Map<String, Object> input = asObjectMap(block.get("input"));
                    // 添加工具调用请求
                    toolCalls.add(new ToolCallRequest(id, name, input));
                }
            }
        }

        // 获取停止原因
        String stopReason = (String) map.get("stop_reason");
        // 获取原始使用情况数据
        Map<String, Object> usageRaw = asObjectMap(map.get("usage"));
        // 初始化使用情况 Map
        Map<String, Integer> usage = new HashMap<>();
        // 如果使用情况数据不为 null
        if (usageRaw != null) {
            // 设置 prompt tokens
            usage.put("prompt_tokens", asInt(usageRaw.get("input_tokens")));
            // 设置 completion tokens
            usage.put("completion_tokens", asInt(usageRaw.get("output_tokens")));
        }

        // 构建并返回 LLM 响应对象
        return new LLMResponse()
                .setContent(content.toString())
                .setToolCalls(toolCalls)
                .setFinishReason(stopReason)
                .setUsage(usage);
    }

    /**
     * 执行流式聊天请求
     *
     * @param messages        消息列表
     * @param tools           工具列表
     * @param model           模型名称
     * @param maxTokens       最大 token 数
     * @param temperature     温度参数
     * @param reasoningEffort 推理努力程度
     * @param toolChoice      工具选择策略
     * @param onDelta         增量数据处理器
     * @param onEnd           结束处理器
     * @return LLM 响应
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
    ) {
        try {
            // 转换消息格式
            ConvertedAnthropicMessages converted = convertMessages(messages);
            // 处理模型名称
            String finalModel = stripPrefix(model != null ? model : defaultModel);

            // 初始化请求体
            Map<String, Object> body = new LinkedHashMap<>();
            // 设置模型
            body.put("model", finalModel);
            // 如果存在 system 消息，设置 system
            if (converted.system() != null) {
                body.put("system", converted.system());
            }
            // 设置消息列表
            body.put("messages", converted.messages());
            // 设置最大 token 数
            body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
            // 启用流式传输
            body.put("stream", true);

            // 如果存在工具
            if (tools != null && !tools.isEmpty()) {
                // 设置工具列表
                body.put("tools", convertTools(tools));
            }

            // 将请求体序列化为 JSON 字符串
            String json = MAPPER.writeValueAsString(body);
            // 确定 API URL
            String url = (apiBase != null && !apiBase.isBlank()) ? apiBase : "https://api.anthropic.com/v1/messages";

            // 构建 HTTP 请求
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            // 添加额外的请求头
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }

            // 发送请求并获取流式响应
            HttpResponse<java.util.stream.Stream<String>> response = client.send(rb.build(), HttpResponse.BodyHandlers.ofLines());

            // 如果状态码不是 200
            if (response.statusCode() != 200) {
                // 抛出运行时异常
                throw new RuntimeException("Anthropic 流式响应错误: " + response.statusCode());
            }

            // 初始化完整内容 StringBuilder
            StringBuilder fullContent = new StringBuilder();
            // 初始化工具调用列表
            List<ToolCallRequest> toolCalls = new ArrayList<>();
            // 初始化使用情况 Map
            Map<String, Integer> usage = new HashMap<>();

            // 处理每一行响应
            response.body().forEach(line -> {
                // 如果行以 "data: " 开头
                if (line.startsWith("data: ")) {
                    // 提取数据部分
                    String data = line.substring(6).trim();
                    // 如果是结束标记，返回
                    if ("[DONE]".equals(data)) return;
                    try {
                        // 解析事件 JSON
                        Map<String, Object> event = MAPPER.readValue(data, JSON_OBJECT_TYPE);
                        // 获取事件类型
                        String type = (String) event.get("type");

                        // 如果是内容块增量事件
                        if ("content_block_delta".equals(type)) {
                            // 获取 delta 对象
                            Map<String, Object> delta = asObjectMap(event.get("delta"));
                            if (delta == null) {
                                return;
                            }
                            // 如果是文本增量
                            if ("text_delta".equals(delta.get("type"))) {
                                // 获取文本
                                String text = (String) delta.get("text");
                                // 追加到完整内容
                                fullContent.append(text);
                                // 如果提供了增量处理器，调用它
                                if (onDelta != null) onDelta.handle(text);
                            }
                        } else if ("message_delta".equals(type)) {
                            // 如果是消息增量事件
                            // 获取使用情况数据
                            Map<String, Object> usageRaw = asObjectMap(event.get("usage"));
                            // 如果存在
                            if (usageRaw != null) {
                                // 设置 completion tokens
                                usage.put("completion_tokens", asInt(usageRaw.get("output_tokens")));
                            }
                        } else if ("message_start".equals(type)) {
                            // 如果是消息开始事件
                            // 获取消息对象
                            Map<String, Object> msg = asObjectMap(event.get("message"));
                            if (msg == null) {
                                return;
                            }
                            // 获取使用情况数据
                            Map<String, Object> usageRaw = asObjectMap(msg.get("usage"));
                            // 如果存在
                            if (usageRaw != null) {
                                // 设置 prompt tokens
                                usage.put("prompt_tokens", asInt(usageRaw.get("input_tokens")));
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

            // 构建最终响应对象
            LLMResponse finalResp = new LLMResponse()
                    .setContent(fullContent.toString())
                    .setUsage(usage);
            
            // 如果提供了结束处理器，调用它
            if (onEnd != null) onEnd.handle(finalResp);
            // 返回最终响应
            return finalResp;

        } catch (Exception e) {
            // 捕获异常并抛出运行时异常
            throw new RuntimeException(e);
        }
    }

    /**
     * 记录转换后的 Anthropic 消息
     *
     * @param system   system 消息内容
     * @param messages 消息列表
     */
    public record ConvertedAnthropicMessages(Object system, List<Map<String, Object>> messages) {}

    private static Map<String, Object> asObjectMap(Object value) {
        return ricbot.infra.common.JsonMapUtils.asNullableObjectMap(value);
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        return ricbot.infra.common.JsonMapUtils.copyObjectMap(raw);
    }

    private static List<Map<String, Object>> asObjectMapList(Object value) {
        return ricbot.infra.common.JsonMapUtils.asNullableObjectMapList(value);
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
