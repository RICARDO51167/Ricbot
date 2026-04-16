// 定义包路径，用于组织类文件
package ricbot.integration.channel;

// 导入 Jackson 库中的 TypeReference，用于泛型反序列化
import com.fasterxml.jackson.core.type.TypeReference;
// 导入 Jackson 库中的 ObjectMapper，用于 JSON 序列化和反序列化
import com.fasterxml.jackson.databind.ObjectMapper;
// 导入自定义的熔断器类，用于保护外部调用
import ricbot.infra.common.CircuitBreaker;
// 导入自定义的重试工具类，用于处理临时性故障
import ricbot.infra.common.RetryUtils;
// 导入消息总线接口，用于消息通信
import ricbot.domain.message.MessageBus;
// 导入出站消息类，封装待发送的消息内容
import ricbot.domain.message.OutboundMessage;

// 导入 URI 类，用于构建网络资源标识符
import java.net.URI;
// 导入 HttpClient 类，Java 11+ 提供的 HTTP 客户端
import java.net.http.HttpClient;
// 导入 HttpRequest 类，用于构建 HTTP 请求
import java.net.http.HttpRequest;
// 导入 HttpResponse 类，用于接收 HTTP 响应
import java.net.http.HttpResponse;
// 导入 Duration 类，用于表示时间间隔
import java.time.Duration;
// 导入常用的集合类，如 List, Map, HashMap, ArrayList 等
import java.util.*;
// 导入 ConcurrentHashMap，虽然当前代码未直接使用，但可能用于线程安全的缓存扩展


/**
 * 钉钉渠道实现类。
 * 负责通过钉钉 API 发送消息和处理配置。
 */
public class DingTalkChannel extends BaseChannel {

    // 创建静态的 ObjectMapper 实例，用于 JSON 处理，线程安全且可复用
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 存储钉钉渠道的配置信息
    private final DingTalkConfig config;
    // HTTP 客户端实例，用于发起网络请求
    private final HttpClient httpClient;
    // 熔断器实例，防止因钉钉服务不可用导致系统资源耗尽，设置失败阈值5次，恢复时间30秒
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);
    // 缓存的访问令牌
    private String accessToken;
    // 访问令牌的过期时间戳
    private long accessTokenExpiry;

    /**
     * 构造函数，初始化钉钉渠道。
     *
     * @param config 原始配置对象，可能是 Map 或 DingTalkConfig 类型
     * @param bus    消息总线实例
     */
    public DingTalkChannel(Object config, MessageBus bus) {
        // 调用父类构造函数
        super(config, bus);
        // 设置渠道内部名称
        this.name = "dingtalk";
        // 设置渠道显示名称
        this.displayName = "钉钉";
        // 转换并保存配置信息
        this.config = convertConfig(config);
        // 构建 HTTP 客户端，设置连接超时时间为 15 秒
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 将原始配置对象转换为 DingTalkConfig 类型。
     *
     * @param raw 原始配置对象
     * @return 转换后的 DingTalkConfig 实例
     */
    private DingTalkConfig convertConfig(Object raw) {
        // 如果已经是 DingTalkConfig 类型，直接返回
        if (raw instanceof DingTalkConfig c) return c;
        // 如果是 Map 类型，则从中提取字段构建配置对象
        if (raw instanceof Map<?, ?> m) {
            DingTalkConfig c = new DingTalkConfig();
            // 设置启用状态，默认 false，确保类型安全
            c.setEnabled(Boolean.TRUE.equals(m.get("enabled")));
            // 设置 AppKey
            c.setAppKey((String) m.get("app_key"));
            // 设置 AppSecret
            c.setAppSecret((String) m.get("app_secret"));
            // 设置允许的来源列表
            c.setAllowFrom((List<String>) m.get("allow_from"));
            return c;
        }
        // 如果无法识别配置类型，返回默认的空配置
        return new DingTalkConfig();
    }

    /**
     * 启动钉钉渠道。
     * 检查配置有效性并标记渠道为运行状态。
     *
     * @throws Exception 启动过程中可能抛出的异常
     */
    @Override
    public void start() throws Exception {
        // 如果 AppKey 为空或空白，则不启动
        if (config.getAppKey() == null || config.getAppKey().isBlank()) {
            return;
        }
        // 标记渠道为运行中
        running = true;
        // 打印启动日志
        System.out.println("钉钉渠道已启动（仅 HTTP 模式）");
    }

    /**
     * 停止钉钉渠道。
     * 标记渠道为非运行状态。
     *
     * @throws Exception 停止过程中可能抛出的异常
     */
    @Override
    public void stop() throws Exception {
        // 标记渠道为停止
        running = false;
    }

    /**
     * 获取允许发送消息的来源列表。
     *
     * @return 允许的来源列表
     */
    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    /**
     * 发送出站消息到钉钉。
     *
     * @param msg 待发送的出站消息
     * @throws Exception 发送过程中可能抛出的异常
     */
    @Override
    public void send(OutboundMessage msg) throws Exception {
        // 获取有效的访问令牌
        String token = getAccessToken();
        // 获取聊天 ID（当前实现未使用，保留以备扩展）
        String chatId = msg.getChatId();
        // 获取消息内容，如果为空则设为空字符串
        String content = msg.getContent() != null ? msg.getContent() : "";

        // 构建请求体 Map
        Map<String, Object> body = new HashMap<>();
        // 设置消息类型为 markdown
        body.put("msgtype", "markdown");
        // 设置 markdown 内容，包含标题和文本
        body.put("markdown", Map.of(
                "title", "Ricbot 回复",
                "text", content
        ));

        // 构建钉钉机器人发送消息的 URL，使用 access_token 进行认证
        // 注意：此处使用的是自定义机器人 Webhook 接口，而非企业应用接口
        String url = "https://oapi.dingtalk.com/robot/send?access_token=" + token;
        
        // 构建 HTTP POST 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url)) // 设置请求 URI
                .header("Content-Type", "application/json") // 设置内容类型为 JSON
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))) // 设置请求体为 JSON 字符串
                .build();

        // 发送 HTTP 请求并获取响应，使用重试和熔断机制
        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        // 如果响应状态码不是 200，打印错误信息
        if (response.statusCode() != 200) {
            System.err.println("发送钉钉消息失败：" + response.body());
        }
    }

    /**
     * 获取钉钉访问令牌，支持缓存和自动刷新。
     *
     * @return 有效的访问令牌
     * @throws Exception 获取令牌失败时抛出异常
     */
    private String getAccessToken() throws Exception {
        // 如果令牌存在且未过期，直接返回缓存的令牌
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
            return accessToken;
        }

        // 构建获取 access_token 的 URL
        String url = "https://oapi.dingtalk.com/gettoken?appkey=" + config.getAppKey() + "&appsecret=" + config.getAppSecret();
        // 构建 HTTP GET 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        // 发送请求并获取响应
        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        // 将响应 JSON 解析为 Map
        Map<String, Object> map = MAPPER.readValue(response.body(), new TypeReference<>() {});
        
        // 检查错误码，0 表示成功
        if ((Integer) map.get("errcode") == 0) {
            // 更新缓存的 access_token
            accessToken = (String) map.get("access_token");
            // 计算过期时间，提前 60 秒刷新以避免边界问题
            accessTokenExpiry = System.currentTimeMillis() + ((Integer) map.get("expires_in") - 60) * 1000L;
            return accessToken;
        }
        // 如果获取失败，抛出运行时异常
        throw new RuntimeException("获取钉钉 access token 失败：" + response.body());
    }

    /**
     * 发送 HTTP 请求，包装了重试和熔断逻辑。
     *
     * @param request HTTP 请求对象
     * @param handler 响应体处理器
     * @param <T>     响应体类型
     * @return HTTP 响应对象
     * @throws Exception 请求失败时抛出异常
     */
    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        // 使用 RetryUtils 执行带重试的操作，内部通过 CircuitBreaker 保护 httpClient.send 调用
        return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
    }

    /**
     * 钉钉渠道配置内部类。
     * 封装了钉钉应用所需的配置项。
     */
    public static class DingTalkConfig {
        // 是否启用该渠道
        private boolean enabled = false;
        // 钉钉应用的 AppKey
        private String appKey = "";
        // 钉钉应用的 AppSecret
        private String appSecret = "";
        // 允许发送消息的来源列表
        private List<String> allowFrom = new ArrayList<>();

        // 获取启用状态
        public boolean isEnabled() { return enabled; }
        // 设置启用状态
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        // 获取 AppKey
        public String getAppKey() { return appKey; }
        // 设置 AppKey
        public void setAppKey(String appKey) { this.appKey = appKey; }
        // 获取 AppSecret
        public String getAppSecret() { return appSecret; }
        // 设置 AppSecret
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        // 获取允许的来源列表
        public List<String> getAllowFrom() { return allowFrom; }
        // 设置允许的来源列表
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }
}
