package ricbot.integration.channel;

import com.fasterxml.jackson.core.type.TypeReference; // 导入 Jackson 的类型引用类，用于反序列化泛型对象
import com.fasterxml.jackson.databind.ObjectMapper; // 导入 Jackson 的 ObjectMapper，用于 JSON 序列化和反序列化
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import ricbot.infra.common.CircuitBreaker; // 导入自定义的熔断器类
import ricbot.infra.common.RetryUtils; // 导入自定义的重试工具类
import ricbot.domain.message.MessageBus; // 导入消息总线接口
import ricbot.domain.message.OutboundMessage; // 导入出站消息类

import java.net.URI; // 导入 URI 类，用于构建请求地址
import java.net.http.HttpClient; // 导入 Java 11+ 的 HTTP 客户端
import java.net.http.HttpRequest; // 导入 HTTP 请求类
import java.net.http.HttpResponse; // 导入 HTTP 响应类
import java.time.Duration; // 导入 Duration 类，用于设置超时时间
import java.util.*; // 导入常用集合类
import java.util.concurrent.ConcurrentHashMap; // 导入线程安全的 HashMap
import java.util.regex.Pattern; // 导入正则表达式 Pattern 类

/**
 * 飞书 / Lark 渠道实现。
 */
@Slf4j
public class FeishuChannel extends BaseChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 创建静态的 ObjectMapper 实例，用于 JSON 处理
    private static final long STREAM_EDIT_INTERVAL_MS = 500; // 流式编辑的最小间隔时间（毫秒），当前未使用但保留
    private static final int TEXT_MAX_LEN = 200; // 纯文本消息的最大长度阈值
    private static final int POST_MAX_LEN = 2000; // Post 消息的最大长度阈值

    // 正则表达式：匹配复杂的 Markdown 语法（代码块、标题、表格）
    private static final Pattern COMPLEX_MD_RE = Pattern.compile("```|^#{1,6}\\s+|^\\|.+\\|.*\\n\\s*\\|[-:\\s|]+\\|", Pattern.MULTILINE);
    // 正则表达式：匹配简单的 Markdown 语法（粗体、斜体、删除线）
    private static final Pattern SIMPLE_MD_RE = Pattern.compile("\\*\\*.+?\\*\\*|__.+?__|~~.+?~~", Pattern.DOTALL);
    // 正则表达式：匹配 Markdown 链接
    private static final Pattern MD_LINK_RE = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)");
    // 正则表达式：匹配无序列表
    private static final Pattern LIST_RE = Pattern.compile("^[\\s]*[-*+]\\s+", Pattern.MULTILINE);
    // 正则表达式：匹配有序列表
    private static final Pattern OLIST_RE = Pattern.compile("^[\\s]*\\d+\\.\\s+", Pattern.MULTILINE);

    private final FeishuConfig config; // 飞书渠道配置对象
    private final HttpClient httpClient; // HTTP 客户端实例
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000); // 熔断器：失败5次或30秒后重置
    private String accessToken; // 缓存的访问令牌
    private long accessTokenExpiry; // 访问令牌的过期时间戳

    private final Map<String, FeishuStreamBuf> streamBufs = new ConcurrentHashMap<>(); // 存储流式消息缓冲区的映射表

    /**
     * 构造函数
     * @param config 原始配置对象
     * @param bus 消息总线
     */
    public FeishuChannel(Object config, MessageBus bus) {
        super(config, bus); // 调用父类构造函数
        this.name = "feishu"; // 设置渠道名称
        this.displayName = "飞书"; // 设置显示名称
        this.config = convertConfig(config); // 转换并初始化配置
        this.httpClient = HttpClient.newBuilder() // 构建 HTTP 客户端
                .connectTimeout(Duration.ofSeconds(15)) // 设置连接超时时间为 15 秒
                .build(); // 构建实例
    }

    /**
     * 将原始配置对象转换为 FeishuConfig
     * @param raw 原始配置对象
     * @return FeishuConfig 实例
     */
    private FeishuConfig convertConfig(Object raw) {
        if (raw instanceof FeishuConfig c) return c; // 如果已经是 FeishuConfig，直接返回
        if (raw instanceof Map<?, ?> m) { // 如果是 Map 类型，进行字段映射
            FeishuConfig c = new FeishuConfig();
            c.setEnabled(Boolean.TRUE.equals(m.get("enabled"))); // 设置启用状态
            c.setAppId((String) m.get("app_id")); // 设置 App ID
            c.setAppSecret((String) m.get("app_secret")); // 设置 App Secret
            c.setAllowFrom(toStringList(m.get("allow_from"))); // 设置允许的来源列表
            return c;
        }
        return new FeishuConfig(); // 默认返回空配置
    }

    @Override
    public void start() throws Exception {
        if (config.getAppId() == null || config.getAppId().isBlank()) { // 检查 App ID 是否有效
            return; // 如果无效则不启动
        }
        running = true; // 标记为运行状态
        log.info("飞书渠道已启动（仅 HTTP 模式）");
    }

    @Override
    public void stop() throws Exception {
        running = false; // 标记为停止状态
        streamBufs.clear(); // 清空流式缓冲区
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom(); // 返回允许的来源列表
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        String token = getAccessToken(); // 获取有效的访问令牌
        String chatId = msg.getChatId(); // 获取聊天 ID
        String content = msg.getContent() != null ? msg.getContent() : ""; // 获取消息内容，默认为空字符串
        String format = detectMsgFormat(content); // 检测消息格式

        Map<String, Object> body = new HashMap<>(); // 构建请求体
        body.put("receive_id", chatId); // 设置接收者 ID
        // 根据格式设置消息类型
        body.put("msg_type", format.equals("interactive") ? "interactive" : (format.equals("post") ? "post" : "text"));

        if (format.equals("interactive")) { // 如果是交互式卡片
            body.put("content", MAPPER.writeValueAsString(buildCard(content))); // 构建卡片内容并序列化
        } else if (format.equals("post")) { // 如果是 Post 消息
            body.put("content", MAPPER.writeValueAsString(buildPost(content))); // 构建 Post 内容并序列化
        } else { // 普通文本
            body.put("content", MAPPER.writeValueAsString(Map.of("text", content))); // 构建文本内容并序列化
        }

        // 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id")) // 设置 API 地址
                .header("Authorization", "Bearer " + token) // 设置认证头
                .header("Content-Type", "application/json") // 设置内容类型
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))) // 设置 POST  body
                .build(); // 构建请求

        // 发送请求并获取响应
        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) { // 如果状态码不是 200
            log.warn("发送飞书消息失败: {}", response.body());
        }
    }

    public void handleWebhookEvent(Map<String, Object> payload) throws Exception {
        if (payload == null || payload.isEmpty()) {
            return;
        }

        Map<String, Object> header = asMap(payload.get("header"));
        Map<String, Object> event = asMap(payload.get("event"));
        Map<String, Object> message = asMap(event.get("message"));
        Map<String, Object> sender = asMap(event.get("sender"));
        Map<String, Object> senderId = asMap(sender.get("sender_id"));

        String userId = firstText(
                senderId.get("user_id"),
                senderId.get("open_id"),
                senderId.get("union_id"),
                event.get("open_id"),
                event.get("user_id")
        );
        if (userId.isBlank() || !isAllowed(userId)) {
            return;
        }

        String chatId = firstText(message.get("chat_id"), event.get("chat_id"), userId);
        String messageType = firstText(message.get("message_type"), event.get("message_type"), "text");
        String content = extractContentText(message.get("content"));
        if (content.isBlank()) {
            content = "[" + messageType + "]";
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("feishu_event_id", firstText(header.get("event_id"), message.get("message_id")));
        metadata.put("feishu_event_type", firstText(header.get("event_type"), payload.get("type")));
        metadata.put("feishu_message_id", firstText(message.get("message_id")));
        metadata.put("feishu_message_type", messageType);

        handleMessage(userId, chatId, content, List.of(), metadata);
    }

    /**
     * 获取访问令牌，支持缓存和自动刷新
     * @return 访问令牌
     * @throws Exception 异常
     */
    private String getAccessToken() throws Exception {
        // 如果令牌存在且未过期，直接返回
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
            return accessToken;
        }

        // 构建获取令牌的请求体
        Map<String, String> body = new HashMap<>();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());

        // 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")) // 令牌 API 地址
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        // 发送请求
        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        // 解析响应 JSON
        Map<String, Object> map = MAPPER.readValue(response.body(), new TypeReference<>() {});
        
        // 检查返回码
        if ((Integer) map.get("code") == 0) {
            accessToken = (String) map.get("tenant_access_token"); // 更新令牌
            // 计算过期时间，提前 60 秒刷新
            accessTokenExpiry = System.currentTimeMillis() + ((Integer) map.get("expire") - 60) * 1000L;
            return accessToken;
        }
        throw new RuntimeException("获取飞书访问令牌失败：" + response.body()); // 抛出异常
    }

    /**
     * 发送 HTTP 请求，包含重试和熔断机制
     * @param request HTTP 请求
     * @param handler 响应处理器
     * @return HTTP 响应
     * @throws Exception 异常
     */
    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        // 使用重试工具执行，内部包裹熔断器逻辑
        return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
    }

    private boolean isAllowed(String userId) {
        List<String> allow = config.getAllowFrom();
        return allow == null || allow.contains("*") || allow.contains(userId);
    }

    private static Map<String, Object> asMap(Object value) {
        return ricbot.infra.common.JsonMapUtils.asObjectMap(value);
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String extractContentText(Object rawContent) {
        if (rawContent == null) {
            return "";
        }
        if (rawContent instanceof Map<?, ?> map) {
            return firstText(map.get("text"), map.get("content"));
        }
        String content = String.valueOf(rawContent);
        if (content.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(content, new TypeReference<>() {});
            return firstText(parsed.get("text"), parsed.get("content"));
        } catch (Exception ignored) {
            return content;
        }
    }

    /**
     * 构建交互式卡片消息结构
     * @param content 消息内容
     * @return 卡片结构 Map
     */
    private Map<String, Object> buildCard(String content) {
        Map<String, Object> card = new HashMap<>();
        Map<String, Object> config = new HashMap<>();
        config.put("wide_screen_mode", true); // 开启宽屏模式
        card.put("config", config);

        Map<String, Object> header = new HashMap<>();
        header.put("template", "blue"); // 设置头部模板颜色
        header.put("title", Map.of("tag", "plain_text", "content", "Ricbot 回复")); // 设置头部标题
        card.put("header", header);

        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", content)); // 添加 Markdown 元素
        card.put("elements", elements);

        return card;
    }

    /**
     * 构建 Post 消息结构
     * @param content 消息内容
     * @return Post 结构 Map
     */
    private Map<String, Object> buildPost(String content) {
        Map<String, Object> post = new HashMap<>();
        Map<String, Object> zhCn = new HashMap<>();
        zhCn.put("title", "Ricbot 回复"); // 设置标题
        
        List<List<Map<String, Object>>> contentList = new ArrayList<>();
        List<Map<String, Object>> line = new ArrayList<>();
        line.add(Map.of("tag", "text", "text", content)); // 添加文本元素
        contentList.add(line);
        
        zhCn.put("content", contentList);
        post.put("zh_cn", zhCn); // 设置中文内容
        return post;
    }

    /**
     * 检测消息格式，决定使用 text, post 还是 interactive
     * @param content 消息内容
     * @return 格式字符串
     */
    public String detectMsgFormat(String content) {
        String stripped = content == null ? "" : content.strip(); // 去除首尾空白
        if (COMPLEX_MD_RE.matcher(stripped).find()) return "interactive"; // 包含复杂 Markdown，使用卡片
        if (stripped.length() > POST_MAX_LEN) return "interactive"; // 超过 Post 长度限制，使用卡片
        if (SIMPLE_MD_RE.matcher(stripped).find()) return "interactive"; // 包含简单 Markdown，使用卡片以支持渲染
        if (LIST_RE.matcher(stripped).find() || OLIST_RE.matcher(stripped).find()) return "interactive"; // 包含列表，使用卡片
        if (MD_LINK_RE.matcher(stripped).find()) return "post"; // 包含链接，使用 Post
        if (stripped.length() <= TEXT_MAX_LEN) return "text"; // 短文本，使用纯文本
        return "post"; // 长文本，使用 Post
    }

    /**
     * 飞书渠道配置类
     */
    @Getter
    @Setter
    public static class FeishuConfig {
        private boolean enabled = false; // 是否启用
        private String appId = ""; // App ID
        private String appSecret = ""; // App Secret
        private List<String> allowFrom = new ArrayList<>(); // 允许的来源列表
    }

    /**
     * 飞书流式消息缓冲区类
     */
    @Getter
    @Setter
    public static class FeishuStreamBuf {
        private String text = ""; // 累积的文本内容
        private String cardId; // 卡片消息 ID
        private long lastEditMillis = 0; // 上次编辑时间戳
    }
}
