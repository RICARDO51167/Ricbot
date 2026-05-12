package ricbot.integration.channel;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import ricbot.infra.common.CircuitBreaker;
import ricbot.infra.common.RetryUtils;
import ricbot.infra.config.RuntimePaths;
import ricbot.infra.security.NetworkSecurity;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * QQ 渠道实现。
 *
 * 主要目标：
 * 1. 处理 QQ C2C / Group 入站消息
 * 2. 附件分块下载到本地
 * 3. 把消息转成统一 InboundMessage 发给 MessageBus
 * 4. 出站先上传媒体，再发文本
 *
 * 说明：
 * 这里把 botpy 的网络 / SDK 部分抽成 QQBotClient 接口，
 * 这样你后面换成任何 Java QQ Bot SDK 都能接。
 */
@Slf4j
public class QQChannel extends BaseChannel {

    /** QQ 文件类型常量：图片 */
    public static final int QQ_FILE_TYPE_IMAGE = 1;
    /** QQ 文件类型常量：普通文件 */
    public static final int QQ_FILE_TYPE_FILE = 4;

    /** 支持的图片文件扩展名集合，用于判断文件是否为图片 */
    private static final Set<String> IMAGE_EXTS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff", ".ico", ".svg"
    );

    /** 文件名安全正则表达式，用于过滤非法字符，保留字母、数字、下划线、点、连括号及中文字符 */
    private static final Pattern SAFE_NAME_RE = Pattern.compile("[^\\w.\\-()\\[\\]（）【】\\u4e00-\\u9fff]+");
    /** HTTP 请求超时，统一用于附件下载和媒体读取 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    /** 连接超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /** 已处理消息 ID 的最大保留数 */
    private static final int MAX_PROCESSED_IDS = 1000;

    /**
     * QQ 渠道配置类。
     * 包含连接 QQ 开放平台所需的 appId、secret 以及消息处理、媒体下载等相关配置。
     */
    @Data
    public static class QQConfig {
        /** 是否启用 QQ 渠道 */
        private boolean enabled = false;
        /** QQ 应用 ID (AppID) */
        private String appId = "";
        /** QQ 应用密钥 (Secret) */
        private String secret = "";
        /** 允许接收消息的用户或群组 ID 列表 */
        private List<String> allowFrom = new ArrayList<>();
        /** 消息格式，如 plain, markdown 等 */
        private String msgFormat = "plain";
        /** 收到消息后的自动回复确认消息 */
        private String ackMessage = "⏳ 处理中…";
        /** 媒体文件保存目录路径 */
        private String mediaDir = "";
        /** 下载文件时的分块大小（字节），默认 256KB */
        private int downloadChunkSize = 1024 * 256;
        /** 单个文件下载的最大限制大小（字节），默认 200MB */
        private long downloadMaxBytes = 1024L * 1024 * 200;
    }

    /**
     * 统一的入站消息对象接口，屏蔽具体 SDK 差异。
     * 用于将不同来源的 QQ 消息标准化。
     */
    public interface QQInboundMessage {
        /** 获取消息唯一 ID */
        String id();
        /** 获取消息文本内容 */
        String content();
        /** 判断是否为群消息 */
        boolean isGroup();
        /** 获取聊天 ID（群 ID 或用户 ID） */
        String chatId();
        /** 发送者用户 ID */
        String userId();
        /** 获取附件列表 */
        List<QQAttachment> attachments();
    }

    /**
         * QQ 消息附件数据类。
         * 包含附件的 URL、文件名和内容类型。
         */
        public record QQAttachment(String url, String filename, String contentType) {
    }

    /**
     * QQ 机器人客户端接口。
     * 定义了与 QQ 开放平台交互的核心方法，支持依赖注入以替换具体实现。
     */
    public interface QQBotClient {
        /**
         * 启动机器人客户端。
         * @param appId QQ 应用 ID
         * @param secret QQ 应用密钥
         * @param listener 消息事件监听器
         */
        void start(String appId, String secret, QQEventListener listener) throws Exception;
        /** 关闭客户端连接 */
        void close() throws Exception;
        /**
         * 上传文件到 QQ 服务器。
         * @param chatId 聊天 ID
         * @param isGroup 是否为群聊
         * @param fileType 文件类型
         * @param base64Data 文件的 Base64 编码数据
         * @param fileName 文件名
         * @return 上传后的媒体 payload 对象
         */
        Object uploadFile(String chatId, boolean isGroup, int fileType, String base64Data, String fileName) throws Exception;
        /**
         * 发送纯文本消息。
         * @param chatId 聊天 ID
         * @param isGroup 是否为群聊
         * @param msgId 回复的消息 ID
         * @param content 消息内容
         * @param format 消息格式
         */
        void sendText(String chatId, boolean isGroup, String msgId, String content, String format) throws Exception;
        /**
         * 发送包含媒体的消息。
         * @param chatId 聊天 ID
         * @param isGroup 是否为群聊
         * @param msgId 回复的消息 ID
         * @param mediaPayload 媒体 payload 对象
         */
        void sendMediaText(String chatId, boolean isGroup, String msgId, Object mediaPayload) throws Exception;
    }

    /**
     * QQ 消息事件监听器接口。
     */
    public interface QQEventListener {
        /** 当接收到新消息时调用 */
        void onMessage(QQInboundMessage message);
    }

    /** QQ 渠道配置实例 */
    private final QQConfig config;
    /** QQ 机器人客户端实例 */
    @Setter
    private QQBotClient client;
    /** HTTP 客户端，用于发送 REST API 请求和下载媒体 */
    private final HttpClient httpClient;
    /** 熔断器，用于保护网络请求 */
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);

    /** 已处理的消息 ID 队列，用于去重，最大保留 1000 条 */
    private final Deque<String> processedIds = new ArrayDeque<>(1000);
    /** 已处理的消息 ID 集合，用于快速查找去重 */
    private final Set<String> processedIdSet = ConcurrentHashMap.newKeySet();
    /** 出站消息序列号计数器 */
    private int msgSeq = 1;

    /** 缓存聊天 ID 对应的类型（group 或 c2c） */
    private final Map<String, String> chatTypeCache = new ConcurrentHashMap<>();
    /** 缓存每个聊天 ID 最后收到的入站消息 ID，用于回复 */
    private final Map<String, String> lastInboundMsgIdByChat = new ConcurrentHashMap<>();
    /** 媒体文件根目录路径 */
    private final Path mediaRoot;
    /** 允许访问的本地媒体根目录列表，用于安全校验 */
    private final List<Path> allowedLocalMediaRoots;

    /**
     * 构造函数。
     * @param config 配置对象，期望为 QQConfig 类型
     * @param bus 消息总线，用于转发处理后的消息
     */
    public QQChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "qq";
        this.displayName = "QQ";
        // 如果传入的配置不是 QQConfig 类型，则创建默认配置
        this.config = (config instanceof QQConfig c) ? c : new QQConfig();
        // 初始化 HTTP 客户端，设置连接超时和重定向策略
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // 初始化媒体根目录
        this.mediaRoot = initMediaRoot();
        // 初始化允许的本地媒体根目录列表
        this.allowedLocalMediaRoots = initAllowedLocalMediaRoots();
    }

    /**
     * 初始化媒体根目录。
     * 如果配置中指定了 mediaDir，则使用配置路径；否则使用默认运行时路径。
     * @return 媒体根目录的绝对路径
     */
    private Path initMediaRoot() {
        Path root;
        if (config.getMediaDir() != null && !config.getMediaDir().isBlank()) {
            root = Path.of(config.getMediaDir()).toAbsolutePath().normalize();
        } else {
            root = RuntimePaths.getMediaDir("qq").toAbsolutePath().normalize();
        }

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("创建 QQ 媒体目录失败：" + root, e);
        }
        return root;
    }

    /**
     * 初始化允许的本地媒体根目录列表。
     * 包括配置的媒体根目录和默认的运行时媒体根目录。
     * @return 允许的路径列表
     */
    private List<Path> initAllowedLocalMediaRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(mediaRoot.toAbsolutePath().normalize());
        roots.add(defaultRuntimeMediaRoot());
        return List.copyOf(roots);
    }

    /**
     * 获取默认的运行时媒体根目录。
     * @return 默认路径 ~/.ricbot/media/qq
     */
    private Path defaultRuntimeMediaRoot() {
        return Path.of(System.getProperty("user.home"), ".ricbot", "media", "qq")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 启动 QQ 渠道。
     * 验证配置，初始化默认客户端（如果未注入），并启动机器人连接。
     * @throws Exception 启动失败时抛出异常
     */
    @Override
    public void start() throws Exception {
        // 检查 appId 和 secret 是否已配置
        if (config.getAppId() == null || config.getAppId().isBlank()
                || config.getSecret() == null || config.getSecret().isBlank()) {
            throw new IllegalStateException("未配置 QQ app_id 与 secret");
        }

        running = true;

        // 如果没有注入自定义客户端，则使用默认实现
        if (client == null) {
            this.client = new DefaultQQBotClient(httpClient);
        }

        // 启动客户端连接
        client.start(config.getAppId(), config.getSecret(), this::onMessage);
        log.info("QQ 机器人已启动");
    }

    // =========================================================
    // Default implementation of QQBotClient
    // =========================================================

    /**
     * QQ 机器人客户端的默认实现。
     * 负责与 QQ 开放平台网关建立 WebSocket 连接、维持心跳、处理鉴权以及发送消息。
     */
    public static class DefaultQQBotClient implements QQBotClient {
        // HTTP 客户端，用于发送 REST API 请求（如获取 Token、Gateway 地址等）
        private final HttpClient httpClient;
        // 熔断器，用于保护 HTTP 请求，防止因网络问题导致资源耗尽
        private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);
        // JSON 对象映射器，用于序列化和反序列化 JSON 数据
        private final ObjectMapper mapper = new ObjectMapper();
        // QQ 应用 ID
        private String appId;
        // QQ 应用密钥
        private String secret;
        // 访问令牌，用于 API 鉴权
        private String accessToken;
        // 访问令牌过期时间戳（毫秒）
        private long accessTokenExpiry;
        // 消息事件监听器，用于回调处理接收到的消息
        private QQEventListener listener;
        // WebSocket 连接实例，用于实时接收网关事件
        private WebSocket webSocket;
        // 心跳任务调度器，用于定期发送心跳包以维持连接
        private ScheduledExecutorService heartbeatScheduler;
        // 最后收到的序列号，用于心跳包中上报，确保消息不丢失
        private volatile int lastSSeq = 0;
        // 发送消息的序列号计数器，原子整数保证线程安全
        private final AtomicInteger sendMsgSeq = new AtomicInteger(1);

        /**
         * 构造函数。
         *
         * @param httpClient 注入的 HTTP 客户端实例
         */
        public DefaultQQBotClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            // 初始化发送消息序列号为一个随机值，避免多实例启动时序列号冲突
            this.sendMsgSeq.set(1 + Math.abs(new Random().nextInt()) % 50000);
        }

        /**
         * 启动机器人客户端。
         * 保存配置信息，刷新访问令牌，并建立与 QQ 网关的 WebSocket 连接。
         *
         * @param appId    QQ 应用 ID
         * @param secret   QQ 应用密钥
         * @param listener 消息事件监听器
         * @throws Exception 启动过程中可能抛出的异常
         */
        @Override
        public void start(String appId, String secret, QQEventListener listener) throws Exception {
            this.appId = appId;
            this.secret = secret;
            this.listener = listener;
            // 获取或刷新访问令牌
            refreshAccessToken();
            // 连接 QQ 网关
            connectGateway();
        }

        /**
         * 连接 QQ 开放平台网关。
         * 首先通过 HTTP 请求获取 WebSocket 连接地址，然后建立异步 WebSocket 连接。
         *
         * @throws Exception 连接失败时抛出的异常
         */
        private void connectGateway() throws Exception {
            // 构建获取 Gateway 地址的 HTTP GET 请求
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.sgroup.qq.com/gateway"))
                    .header("Authorization", "QQBot " + getAccessToken()) // 设置鉴权头
                    .GET().build();

            // 发送请求并获取响应
            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            // 检查响应状态码，非 200 表示失败
            if (response.statusCode() != 200) {
                throw new IOException("获取 QQ Gateway 失败：" + response.body());
            }

            // 解析响应 JSON，提取 WebSocket 连接 URL
            Map<String, Object> data = mapper.readValue(response.body(), new TypeReference<>() {});
            String wssUrl = (String) data.get("url");
            
            // 异步建立 WebSocket 连接，并等待连接完成
            this.webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wssUrl), new QqWsListener()).join();
        }

        /**
         * QQ WebSocket 监听器实现，用于处理来自 QQ 开放平台网关的事件。
         */
        private class QqWsListener implements WebSocket.Listener {
            // 用于累积分片传输的文本数据
            private final StringBuilder buffer = new StringBuilder();

            /**
             * 当 WebSocket 连接成功建立时调用。
             * @param webSocket 当前连接的 WebSocket 实例
             */
            @Override
            public void onOpen(WebSocket webSocket) {
                // 将当前 WebSocket 实例保存到外部类字段中，以便后续发送消息
                DefaultQQBotClient.this.webSocket = webSocket;
                log.info("QQ WebSocket 已连接");
                // 请求接收下一条消息
                webSocket.request(1);
            }

            /**
             * 当接收到文本消息片段时调用。
             * @param webSocket 当前连接的 WebSocket 实例
             * @param data 接收到的文本数据片段
             * @param last 是否为当前消息的最后一个片段
             * @return CompletionStage，此处返回 null 表示同步处理
             */
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                // 将片段追加到缓冲区
                buffer.append(data);
                // 如果是最后一个片段，则处理完整消息
                if (last) {
                    try {
                        // 解析并处理 JSON 消息
                        handleMessage(buffer.toString(), webSocket);
                    } catch (Exception e) {
                        log.error("处理 QQ WebSocket 消息失败", e);
                    }
                    // 清空缓冲区，准备接收下一条消息
                    buffer.setLength(0);
                }
                // 请求接收下一条消息
                webSocket.request(1);
                return null;
            }

            /**
             * 当 WebSocket 连接关闭时调用。
             * @param webSocket 当前连接的 WebSocket 实例
             * @param statusCode 关闭状态码
             * @param reason 关闭原因描述
             * @return CompletionStage，此处返回 null 表示同步处理
             */
            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.warn("QQ WebSocket 已关闭: {} {}", statusCode, reason);
                // 停止心跳任务
                stopHeartbeat();
                // 尝试重新连接
                try {
                    // 等待 3 秒后重试，避免频繁重连
                    Thread.sleep(3000);
                    // 重新获取网关地址并建立连接
                    connectGateway();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("QQ WebSocket 重连等待被中断");
                } catch (Exception e) {
                    log.error("QQ WebSocket 重连失败", e);
                }
                return null;
            }

            /**
             * 当 WebSocket 发生错误时调用。
             * @param webSocket 当前连接的 WebSocket 实例
             * @param error 发生的异常对象
             */
            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("QQ WebSocket 错误: {}", error.getMessage(), error);
                // 发生错误时停止心跳
                stopHeartbeat();
            }

            /**
             * 处理接收到的完整 JSON 消息。
             * @param json JSON 字符串
             * @param currentSocket 当前 WebSocket 实例，用于在需要时中断连接
             * @throws Exception 处理过程中可能抛出的异常
             */
            private void handleMessage(String json, WebSocket currentSocket) throws Exception {
                // 将 JSON 字符串解析为 Map 对象
                Map<String, Object> msg = mapper.readValue(json, new TypeReference<>() {});
                // 获取操作码 (opcode)
                Integer op = asInt(msg.get("op"));
                
                // 如果消息包含序列号 (s)，则更新最后收到的序列号，用于心跳
                if (msg.containsKey("s") && msg.get("s") != null) {
                    Integer seq = asInt(msg.get("s"));
                    if (seq != null) {
                        lastSSeq = seq;
                    }
                }

                // 如果操作码为空，直接返回
                if (op == null) return;

                // 根据操作码处理不同类型的消息
                switch (op) {
                    case 10: // Hello: 网关欢迎消息，包含心跳间隔
                        Map<String, Object> d = asObjectMap(msg.get("d"));
                        // 获取心跳间隔时间（毫秒）
                        Integer interval = d != null ? asInt(d.get("heartbeat_interval")) : null;
                        // 如果间隔无效，设置默认值 30 秒
                        if (interval == null || interval <= 0) {
                            interval = 30_000;
                        }
                        // 启动定时心跳任务
                        startHeartbeat(interval);
                        // 发送身份验证信息
                        identify(currentSocket);
                        break;
                    case 0: // Dispatch: 事件分发
                        // 获取事件类型
                        String t = (String) msg.get("t");
                        // 获取事件数据
                        Map<String, Object> eventData = asObjectMap(msg.get("d"));
                        // 如果是 READY 事件，表示机器人已就绪
                        if ("READY".equals(t)) {
                            log.info("QQ 机器人已就绪");
                        } 
                        // 如果是消息创建事件（群消息或私聊消息），则处理入站消息
                        else if (t != null && t.endsWith("MESSAGE_CREATE")) {
                            processInboundMessage(eventData);
                        }
                        break;
                    case 7: // Reconnect: 要求重连
                    case 9: // Invalid Session: 会话失效
                        // 中断当前连接，触发 onClose 方法进行重连
                        currentSocket.abort(); 
                        break;
                }
            }
        }

        /**
         * 启动心跳任务。
         * 按照 QQ 开放平台网关要求，定期发送心跳包以维持 WebSocket 连接活跃。
         *
         * @param interval 心跳间隔时间（毫秒）
         */
        private void startHeartbeat(int interval) {
            // 先停止可能存在的旧心跳任务，避免重复启动
            stopHeartbeat();
            
            // 创建单线程的定时任务调度器
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
            
            // 安排固定频率的心跳任务
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    // 构造心跳数据包
                    Map<String, Object> beat = new HashMap<>();
                    // 操作码 1 表示心跳 (Heartbeat)
                    beat.put("op", 1);
                    // 数据部分为上次接收到的序列号，若未收到过则为 null
                    beat.put("d", lastSSeq > 0 ? lastSSeq : null);
                    
                    // 将心跳对象序列化为 JSON 字符串
                    String json = mapper.writeValueAsString(beat);
                    
                    // 检查 WebSocket 连接状态，确保连接未关闭且输入流未关闭
                    if (webSocket != null && !webSocket.isInputClosed()) {
                        // 异步发送心跳文本消息，并等待发送完成
                        webSocket.sendText(json, true).join();
                    }
                } catch (Exception e) {
                    log.warn("发送 QQ 心跳失败", e);
                }
            }, interval, interval, TimeUnit.MILLISECONDS); // 初始延迟 interval，之后每 interval 毫秒执行一次
        }

        /**
         * 停止心跳任务。
         * 关闭当前的定时任务调度器，释放相关资源。
         */
        private void stopHeartbeat() {
            // 如果调度器存在，则立即关闭并中断正在执行的任务
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
                // 将调度器引用置空，便于垃圾回收和状态判断
                heartbeatScheduler = null;
            }
        }

        /**
         * 向 QQ 网关发送身份验证请求 (Identify)。
         * 在 WebSocket 连接建立后，必须发送此数据包以完成握手和鉴权。
         *
         * @param socket 当前使用的 WebSocket 实例，若为 null 则使用类成员变量中的 webSocket
         * @throws Exception 当序列化失败或 WebSocket 未就绪时抛出异常
         */
        private void identify(WebSocket socket) throws Exception {
            // 构造 Identify 数据包
            Map<String, Object> identify = getStringObjectMap();

            // 确定要使用的 WebSocket 实例：优先使用参数传入的，否则使用类成员变量
            WebSocket ws = socket != null ? socket : this.webSocket;
            
            // 双重检查，确保 WebSocket 实例有效
            if (ws == null) {
                throw new IllegalStateException("QQ WebSocket 未就绪，无法发送 identify");
            }
            
            // 序列化 Identify 对象并发送文本消息，等待发送完成
            ws.sendText(mapper.writeValueAsString(identify), true).join();
        }

        private Map<String, Object> getStringObjectMap() throws Exception {
            Map<String, Object> identify = new HashMap<>();
            // 操作码 2 表示身份验证 (Identify)
            identify.put("op", 2);

            // 构造数据部分 (Payload)
            Map<String, Object> d = new HashMap<>();
            // 设置鉴权 Token，格式为 "QQBot <access_token>"
            d.put("token", "QQBot " + getAccessToken());
            // 设置订阅的事件类型 intents
            // 33554432 (1<<25): C2C 消息事件
            // 1073741824 (1<<30): 群消息事件
            // 1 (1<<0): 公域/频道消息事件 (保留位，通常用于兼容)
            d.put("intents", 33554432 | 1073741824 | 1);
            identify.put("d", d);
            return identify;
        }

        /**
         * 处理来自 QQ 网关的入站消息事件。
         * 该方法解析原始 JSON 数据，提取关键信息（如聊天 ID、用户 ID、内容、附件等），
         * 并将其封装为统一的 QQInboundMessage 对象，通过监听器回调给上层业务逻辑。
         *
         * @param data 从 WebSocket 接收并解析后的消息数据 Map
         */
        private void processInboundMessage(Map<String, Object> data) {
            // 如果未设置消息监听器，则直接返回，不处理消息
            if (listener == null) return;

            // 判断消息是否来自群组：检查数据中是否包含 "group_id" 字段
            boolean isGroup = data.containsKey("group_id");
            
            // 获取聊天 ID：如果是群消息，则取 group_id；否则暂时设为 null
            String chatId = isGroup ? (String) data.get("group_id") : null;
            
            // 获取作者信息对象
            Map<String, Object> author = asObjectMap(data.get("author"));
            // 从作者信息中提取用户 ID，如果作者信息为空则 userId 为 null
            String userId = author != null ? (String) author.get("id") : null;
            
            // 如果不是群消息且 chatId 仍为 null（即私聊/C2C 场景），则将 chatId 设置为 userId
            // 这样在后续处理中可以使用 userId 作为会话标识
            if (!isGroup) {
                chatId = userId; // For C2C, fallback to user ID
            }

            // 提取消息唯一标识 ID
            String msgId = (String) data.get("id");
            // 提取消息文本内容
            String content = (String) data.get("content");
            
            // 初始化附件列表
            List<QQAttachment> atts = new ArrayList<>();
            // 获取原始附件数据列表
            List<Map<String, Object>> attachments = asObjectMapList(data.get("attachments"));
            
            // 如果存在附件数据，则遍历处理每一个附件
            if (attachments != null) {
                for (Map<String, Object> a : attachments) {
                    // 获取附件 URL
                    String url = (String) a.get("url");
                    
                    // 补全 URL 协议头：
                    // 1. 如果以 "//" 开头，补全为 "https:"
                    // 2. 如果不以 "http" 开头（且不为空），补全为 "https://"
                    if (url != null && url.startsWith("//")) {
                        url = "https:" + url;
                    } else if (url != null && !url.startsWith("http")) {
                        url = "https://" + url;
                    }
                    
                    // 创建 QQAttachment 对象并添加到列表中
                    // 参数依次为：URL、文件名、内容类型
                    atts.add(new QQAttachment(url, (String) a.get("filename"), (String) a.get("content_type")));
                }
            }

            // 由于要在匿名内部类中使用局部变量，需要将其声明为 final 或 effectively final
            final String fChatId = chatId;
            final String fUserId = userId;
            
            // 构造匿名的 QQInboundMessage 实现类实例，封装当前消息的所有关键信息
            // 并通过 listener.onMessage 回调通知上层业务逻辑有新消息到达
            listener.onMessage(new QQInboundMessage() {
                public String id() { return msgId; }
                public String content() { return content; }
                public boolean isGroup() { return isGroup; }
                public String chatId() { return fChatId; }
                public String userId() { return fUserId; }
                public List<QQAttachment> attachments() { return atts; }
            });
        }

        @Override
        public void close() throws Exception {
            stopHeartbeat();
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping").join();
            }
        }

        @Override
        public Object uploadFile(String chatId, boolean isGroup, int fileType, String base64Data, String fileName) throws Exception {
            // In real SDK, this would call /v2/groups/{group_id}/files or /v2/users/{user_id}/files
            return null;
        }

        @Override
        public void sendText(String chatId, boolean isGroup, String msgId, String content, String format) throws Exception {
            String token = getAccessToken();
            String url = isGroup ? "https://api.sgroup.qq.com/v2/groups/" + chatId + "/messages" 
                                 : "https://api.sgroup.qq.com/v2/users/" + chatId + "/messages";
            
            Map<String, Object> body = new HashMap<>();
            body.put("content", content);
            body.put("msg_type", 0); // 0 is text
            body.put("msg_seq", nextSendMsgSeq());
            if (msgId != null && !msgId.isBlank()) {
                body.put("msg_id", msgId);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "QQBot " + token)
                    .header("X-Union-Appid", appId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IOException("QQ sendText failed: status=" + response.statusCode() + " body=" + response.body());
            }
        }

        private int nextSendMsgSeq() {
            int next = sendMsgSeq.incrementAndGet();
            if (next > 100000) {
                sendMsgSeq.set(1);
                return 1;
            }
            return next;
        }

        @Override
        public void sendMediaText(String chatId, boolean isGroup, String msgId, Object mediaPayload) throws Exception {
            // Similar to sendText but with media payload
        }

        private String getAccessToken() throws Exception {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
                return accessToken;
            }
            refreshAccessToken();
            return accessToken;
        }

        private synchronized void refreshAccessToken() throws Exception {
            // 构建请求体，包含 appId 和 secret 用于获取新的访问令牌
            Map<String, String> body = Map.of(
                    "appId", appId,           // QQ 应用 ID
                    "clientSecret", secret    // QQ 应用密钥
            );
            
            // 构建 HTTP POST 请求，用于获取应用访问令牌
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://bots.qq.com/app/getAppAccessToken"))
                    .header("Content-Type", "application/json")      // 设置内容类型为 JSON
                    .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))  // 将请求体转换为 JSON 字符串并设置为 POST 请求体
                    .build();

            // 发送 HTTP 请求并获取响应结果
            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            
            // 将响应的 JSON 字符串解析为 Map 对象，方便获取其中的数据
            Map<String, Object> data = mapper.readValue(response.body(), new TypeReference<>() {});
            
            // 从响应数据中提取访问令牌
            this.accessToken = (String) data.get("access_token");
            
            // 从响应数据中提取令牌过期时间（单位：秒）
            Integer expires = asInt(data.get("expires_in"));
            
            // 如果过期时间无效，则使用默认值 3600 秒（1小时）
            if (expires == null || expires <= 0) {
                expires = 3600;
            }
            
            // 计算访问令牌的过期时间戳，提前 60 秒刷新以防过期
            this.accessTokenExpiry = System.currentTimeMillis() + (expires - 60) * 1000L;
        }

        /**
         * 将对象安全地转换为 Integer。
         * 支持 Number 类型直接转换，或 String 类型解析。
         *
         * @param value 待转换的对象
         * @return 转换后的 Integer，若无法转换则返回 null
         */
        private static Integer asInt(Object value) {
            // 如果值是数字类型，直接获取其 int 值
            if (value instanceof Number n) {
                return n.intValue();
            }
            // 如果值是字符串类型，尝试去除空白后解析为整数
            if (value instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (Exception ignored) {
                    // 解析失败时返回 null
                    return null;
                }
            }
            // 其他类型或 null 直接返回 null
            return null;
        }

        private static Map<String, Object> asObjectMap(Object value) {
            if (!(value instanceof Map<?, ?> raw)) {
                return new LinkedHashMap<>();
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
            if (!(value instanceof List<?> raw)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : raw) {
                Map<String, Object> map = asObjectMap(item);
                if (!map.isEmpty()) {
                    out.add(map);
                }
            }
            return out;
        }

        private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
            return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
        }
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (client != null) {
            client.close();
        }
    }

    /**
     * 处理接收到的 QQ 入站消息。
     * 该方法负责消息去重、附件下载、内容组装以及转发到统一消息总线。
     *
     * @param data 标准化的 QQ 入站消息对象
     */
    public void onMessage(QQInboundMessage data) {
        try {
            // 1. 基础校验：如果消息对象为空或消息 ID 为空，直接忽略
            if (data == null || data.id() == null) {
                return;
            }

            // 2. 消息去重：检查该消息 ID 是否已经处理过，避免重复处理
            if (processedIdSet.contains(data.id())) {
                return;
            }
            // 将当前消息 ID 记录为已处理
            rememberProcessedId(data.id());

            // 3. 提取消息基本属性
            String chatId = data.chatId();      // 聊天 ID（群 ID 或用户 ID）
            String userId = data.userId();      // 发送者用户 ID
            boolean isGroup = data.isGroup();   // 是否为群消息

            // 4. 更新缓存信息
            // 缓存聊天类型（group 或 c2c），用于后续回复时判断接口路径
            chatTypeCache.put(chatId, isGroup ? "group" : "c2c");
            // 缓存该聊天会话最后一条入站消息 ID，用于后续可能的回复关联
            lastInboundMsgIdByChat.put(chatId, data.id());

            // 5. 处理消息内容和附件
            // 获取消息文本内容，去除首尾空白；如果为空则设为空字符串
            String content = data.content() != null ? data.content().trim() : "";
            // 处理附件：下载文件到本地，并获取下载结果（本地路径列表、展示文本列表、元数据列表）
            AttachmentResult attachmentResult = handleAttachments(data.attachments());

            // 6. 组装最终显示内容
            // 如果有附件，构建附件描述文本
            if (!attachmentResult.recvLines.isEmpty()) {
                // 将附件列表拼接成多行字符串，前缀为 "收到文件："
                String extra = "收到文件：\n" + String.join("\n", attachmentResult.recvLines);
                // 如果原文本为空，则只显示附件信息；否则在原文本后追加附件信息
                content = content.isBlank() ? extra : content + "\n\n" + extra;
            }

            // 7. 有效性检查：如果既没有文本内容也没有成功下载的附件，则忽略该消息
            if (content.isBlank() && attachmentResult.mediaPaths.isEmpty()) {
                return;
            }

            // 8. 发送自动确认消息（ACK）
            // 如果配置中设置了 ackMessage，则立即回复一条“处理中”的消息，提升用户体验
            if (config.getAckMessage() != null && !config.getAckMessage().isBlank()) {
                try {
                    // 尝试发送纯文本确认消息，忽略发送失败异常，避免阻塞主流程
                    sendTextOnly(chatId, isGroup, data.id(), config.getAckMessage());
                } catch (Exception ignored) {
                    // 忽略异常
                }
            }

            // 9. 构建元数据
            Map<String, Object> metadata = new HashMap<>();
            // 放入原始消息 ID
            metadata.put("message_id", data.id());
            // 放入附件元数据（包含 URL、文件名、保存路径等）
            metadata.put("attachments", attachmentResult.attachmentMeta);

            // 10. 转发到统一消息总线
            // 调用父类方法，将标准化后的消息发送给核心业务逻辑处理
            handleMessage(
                    userId,           // 发送者 ID
                    chatId,           // 会话 ID
                    content,          // 合并后的文本内容
                    attachmentResult.mediaPaths, // 本地附件路径列表
                    metadata          // 额外元数据
            );

        } catch (Exception e) {
            log.error("处理 QQ 入站消息失败: id={}", data.id(), e);
        }
    }

    /**
     * 记录已处理的消息 ID，用于消息去重。
     * 该方法将消息 ID 添加到双端队列和集合中，并维护队列大小不超过 1000，
     * 以防止内存无限增长。当队列超过限制时，移除最旧的 ID。
     *
     * @param id 需要记录的消息唯一 ID
     */
    private void rememberProcessedId(String id) {
        // 将新消息 ID 添加到队列尾部
        processedIds.addLast(id);
        // 将新消息 ID 添加到集合中，以便快速查找去重
        processedIdSet.add(id);

        // 当已处理消息数量超过最大限制（1000）时，移除最旧的消息 ID
        while (processedIds.size() > MAX_PROCESSED_IDS) {
            // 从队列头部移除最旧的 ID
            String removed = processedIds.removeFirst();
            // 从集合中同步移除该 ID，确保数据结构一致性
            processedIdSet.remove(removed);
        }
    }

    /**
     * 处理附件：下载并返回本地路径 + 展示文本 + 元数据。
     *
     * @param attachments QQ 附件列表
     * @return 包含本地媒体路径、接收消息行和附件元数据的结果对象
     */
    private AttachmentResult handleAttachments(List<QQAttachment> attachments) {
        // 初始化存储本地媒体文件路径的列表
        List<String> mediaPaths = new ArrayList<>();
        // 初始化存储用于展示给用户看的附件信息行的列表
        List<String> recvLines = new ArrayList<>();
        // 初始化存储附件详细元数据（如 URL、文件名、保存路径等）的列表
        List<Map<String, Object>> attMeta = new ArrayList<>();

        // 如果附件列表为空，直接返回空的结果对象
        if (attachments == null || attachments.isEmpty()) {
            return new AttachmentResult(mediaPaths, recvLines, attMeta);
        }

        // 遍历每一个附件进行处理
        for (QQAttachment att : attachments) {
            AttachmentDescriptor attachment = AttachmentDescriptor.from(att);
            AttachmentDownload download = downloadAttachment(attachment.url(), attachment.filename());

            attMeta.add(buildAttachmentMetadata(attachment, download.savedPath()));
            recvLines.add(buildAttachmentReceiveLine(resolveAttachmentDisplayName(attachment, download), download.savedPath()));
            if (download.downloaded()) {
                mediaPaths.add(download.savedPath());
            }
        }

        // 返回包含所有处理结果的对象
        return new AttachmentResult(mediaPaths, recvLines, attMeta);
    }

    /**
     * 分块流式下载附件，避免一次性读入内存。
     */
    private AttachmentDownload downloadAttachment(String url, String filenameHint) {
        if (url == null || url.isBlank()) {
            return AttachmentDownload.failed(null);
        }

        if (url.startsWith("//")) {
            url = "https:" + url;
        }

        String safe = sanitizeFilename(filenameHint);
        long ts = System.currentTimeMillis();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = sendHttp(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return AttachmentDownload.failed(safe);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);

            String ext = extensionFromContentType(contentType);
            if (safe.isBlank()) {
                safe = "qq_" + ts + (ext != null ? ext : ".bin");
            }

            Path finalPath = mediaRoot.resolve(ts + "_" + safe).normalize();
            Path tmpPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");

            long total = 0;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[Math.max(1024, config.getDownloadChunkSize())];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > config.getDownloadMaxBytes()) {
                        try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
                        return AttachmentDownload.failed(safe);
                    }
                    out.write(buf, 0, n);
                }
            }

            Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return AttachmentDownload.success(finalPath.toString());

        } catch (Exception e) {
            log.warn("下载 QQ 附件失败: url={}, filenameHint={}", url, filenameHint, e);
            return AttachmentDownload.failed(safe);
        }
    }

    private Map<String, Object> buildAttachmentMetadata(AttachmentDescriptor attachment, String savedPath) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("url", attachment.url());
        meta.put("filename", attachment.filename());
        meta.put("content_type", attachment.contentType());
        meta.put("saved_path", savedPath);
        return meta;
    }

    private String resolveAttachmentDisplayName(AttachmentDescriptor attachment, AttachmentDownload download) {
        if (!attachment.filename().isBlank()) {
            return attachment.filename();
        }
        if (download.downloaded()) {
            return Path.of(download.savedPath()).getFileName().toString();
        }
        return attachment.url();
    }

    private String buildAttachmentReceiveLine(String displayName, String savedPath) {
        return savedPath != null
                ? "- " + displayName + "\n  已保存：" + savedPath
                : "- " + displayName + "\n  已保存：[下载失败]";
    }

    private String extensionFromContentType(String contentType) {
        if (contentType == null) return null;
        if (contentType.startsWith("image/png")) return ".png";
        if (contentType.startsWith("image/jpeg")) return ".jpg";
        if (contentType.startsWith("image/gif")) return ".gif";
        if (contentType.startsWith("image/webp")) return ".webp";
        if (contentType.startsWith("application/pdf")) return ".pdf";
        return null;
    }

    private static String sanitizeFilename(String name) {
        name = name == null ? "" : name.trim();
        name = Path.of(name.isBlank() ? "file.bin" : name).getFileName().toString();
        name = SAFE_NAME_RE.matcher(name).replaceAll("_").replaceAll("^[._ ]+|[._ ]+$", "");
        return name.isBlank() ? "file.bin" : name;
    }

    private static boolean isImageName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据文件名猜测发送的文件类型。
     * 如果是图片扩展名，则返回图片类型常量；否则返回普通文件类型常量。
     *
     * @param filename 文件名
     * @return QQ 文件类型常量（QQ_FILE_TYPE_IMAGE 或 QQ_FILE_TYPE_FILE）
     */
    private static int guessSendFileType(String filename) {
        return isImageName(filename) ? QQ_FILE_TYPE_IMAGE : QQ_FILE_TYPE_FILE;
    }

    /**
     * 发送出站消息到 QQ。
     * 该方法处理媒体上传和文本发送逻辑，支持回复特定消息。
     *
     * @param msg 出站消息对象，包含聊天 ID、内容、媒体引用和元数据
     * @throws Exception 发送失败时抛出异常
     */
    public void send(OutboundMessage msg) throws Exception {
        // 检查客户端是否已初始化
        if (client == null) {
            throw new IllegalStateException("QQ client not initialized");
        }

        // 获取目标聊天 ID
        String chatId = msg.getChatId();
        // 从缓存中获取聊天类型，默认为 C2C（私聊），判断是否为群聊
        boolean isGroup = "group".equalsIgnoreCase(chatTypeCache.getOrDefault(chatId, "c2c"));
        
        // 初始化回复消息 ID
        String replyToMsgId = null;
        // 尝试从消息元数据中获取原始消息 ID，用于回复
        if (msg.getMetadata() != null) {
            Object v = msg.getMetadata().get("message_id");
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isBlank()) {
                    replyToMsgId = s;
                }
            }
        }
        // 如果元数据中没有有效的消息 ID，则使用该聊天会话最后收到的入站消息 ID
        if (replyToMsgId == null || replyToMsgId.isBlank()) {
            replyToMsgId = lastInboundMsgIdByChat.get(chatId);
        }

        // 1. 优先发送媒体附件
        if (msg.getMedia() != null) {
            for (String mediaRef : msg.getMedia()) {
                // 读取媒体文件的字节数据和文件名
                MediaReadResult mediaResult = readMedia(mediaRef);
                if (!mediaResult.success()) {
                    log.debug("跳过不可读取的 QQ 出站媒体: ref={}, reason={}", mediaRef, mediaResult.reason());
                    continue;
                }
                MediaBytes media = mediaResult.media();

                // 猜测文件类型（图片或普通文件）
                int fileType = guessSendFileType(media.filename());
                // 将文件数据编码为 Base64 字符串
                String base64 = Base64.getEncoder().encodeToString(media.data());

                // 调用客户端上传文件
                // 注意：对于图片类型，某些 SDK 可能不需要传文件名，具体取决于实现
                Object mediaPayload = client.uploadFile(
                        chatId,
                        isGroup,
                        fileType,
                        base64,
                        fileType == QQ_FILE_TYPE_IMAGE ? null : media.filename()
                );

                // 发送包含媒体的消息
                client.sendMediaText(chatId, isGroup, replyToMsgId, mediaPayload);
            }
        }

        // 2. 发送文本内容
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            // 调用辅助方法发送纯文本消息
            sendTextOnly(chatId, isGroup, replyToMsgId, msg.getContent());
        }
    }

    /**
     * 发送纯文本消息。
     *
     * @param chatId 聊天 ID
     * @param isGroup 是否为群聊
     * @param msgId 回复的消息 ID
     * @param content 消息文本内容
     * @throws Exception 发送失败时抛出异常
     */
    private void sendTextOnly(String chatId, boolean isGroup, String msgId, String content) throws Exception {
        // 再次检查客户端状态
        if (client == null) {
            throw new IllegalStateException("QQ client not initialized");
        }
        // 委托给客户端执行发送操作，使用配置中的消息格式
        client.sendText(chatId, isGroup, msgId, content, config.getMsgFormat());
    }

    /**
     * 读取出站媒体资源。
     * 支持本地文件路径、file:// URI 以及 http(s) 远程 URL。
     * 会对本地路径进行安全校验，对远程 URL 进行网络安全性检查。
     *
     * @param mediaRef 媒体引用字符串（路径或 URL）
     * @return MediaBytes 对象，包含文件数据和文件名；如果读取失败则返回 null
     */
    private MediaReadResult readMedia(String mediaRef) {
        // 处理 null 输入并去除首尾空白
        mediaRef = mediaRef == null ? "" : mediaRef.trim();
        // 如果处理后为空，直接返回 null
        if (mediaRef.isBlank()) {
            return MediaReadResult.failure("blank media reference");
        }

        try {
            // 判断是否为本地文件（非 HTTP/HTTPS 协议）
            if (!isRemoteMediaRef(mediaRef)) {
                // 解析本地文件路径
                Path localPath = resolveLocalMediaPath(mediaRef);

                // 检查文件是否存在且为普通文件
                if (!Files.isRegularFile(localPath)) {
                    return MediaReadResult.failure("local file not found");
                }
                // 安全检查：确认文件路径在允许的媒体根目录下
                if (!isAllowedLocalMediaPath(localPath)) {
                    return MediaReadResult.failure("local file outside allowed roots");
                }

                // 读取文件所有字节
                byte[] data = Files.readAllBytes(localPath);
                // 返回包含数据和文件名的对象
                return MediaReadResult.success(new MediaBytes(data, localPath.getFileName().toString()));
            }

            // 处理远程 HTTP/HTTPS URL
            // 首先验证 URL 的安全性（防止 SSRF 等攻击）
            NetworkSecurity.ValidationResult urlCheck = validateRemoteMediaUrl(mediaRef);
            if (!urlCheck.ok()) {
                return MediaReadResult.failure("remote url rejected");
            }
            
            // 构建 HTTP GET 请求，设置超时时间
            HttpRequest request = HttpRequest.newBuilder(URI.create(mediaRef))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            // 发送请求并接收字节数组响应
            HttpResponse<byte[]> response = sendHttp(request, HttpResponse.BodyHandlers.ofByteArray());
            // 检查响应状态码和数据有效性
            if (response.statusCode() >= 400 || response.body() == null || response.body().length == 0) {
                return MediaReadResult.failure("remote response empty");
            }
            
            // 验证重定向后的 URL 安全性
            NetworkSecurity.ValidationResult redirectCheck = NetworkSecurity.validateResolvedUrl(response.uri().toString());
            if (!redirectCheck.ok()) {
                return MediaReadResult.failure("redirect target rejected");
            }

            // 从响应 URI 中提取文件名
            String path = response.uri().getPath();
            String filename = (path == null || path.isBlank()) ? "file.bin" : Path.of(path).getFileName().toString();

            // 返回包含下载数据和文件名的对象
            return MediaReadResult.success(new MediaBytes(response.body(), filename));

        } catch (Exception e) {
            log.warn("读取 QQ 出站媒体失败: ref={}", mediaRef, e);
            return MediaReadResult.failure("media read exception");
        }
    }

    private boolean isRemoteMediaRef(String mediaRef) {
        return mediaRef.startsWith("http://") || mediaRef.startsWith("https://");
    }

    /**
     * 发送 HTTP 请求，带有重试和熔断保护。
     *
     * @param request HTTP 请求对象
     * @param handler 响应体处理器
     * @param <T> 响应体类型
     * @return HTTP 响应对象
     * @throws Exception 请求失败时抛出异常
     */
    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        // 使用 RetryUtils 进行重试，并结合 CircuitBreaker 进行熔断保护
        return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
    }

    /**
     * 解析本地媒体路径。
     * 支持 file:// URI 和普通文件路径（绝对或相对）。
     * 相对路径会相对于配置的媒体根目录解析。
     *
     * @param mediaRef 媒体引用字符串
     * @return 规范化后的绝对路径
     */
    Path resolveLocalMediaPath(String mediaRef) {
        Path localPath;
        // 如果是 file:// 协议，转换为 URI 再转为 Path
        if (mediaRef.startsWith("file://")) {
            localPath = Path.of(URI.create(mediaRef));
        } else {
            // 否则直接创建 Path 对象
            Path raw = Path.of(mediaRef);
            // 如果是绝对路径直接使用，否则相对于 mediaRoot 解析
            localPath = raw.isAbsolute() ? raw : mediaRoot.resolve(raw);
        }
        // 返回绝对且规范化的路径，防止路径遍历攻击
        return localPath.toAbsolutePath().normalize();
    }

    /**
     * 检查本地路径是否在允许的媒体根目录范围内。
     * 用于防止访问非预期的文件系统位置。
     *
     * @param localPath 待检查的本地路径
     * @return 如果路径合法返回 true，否则返回 false
     */
    boolean isAllowedLocalMediaPath(Path localPath) {
        try {
            // 获取路径的真实路径（解析符号链接等）
            Path candidate = localPath.toRealPath();
            // 遍历所有允许的根目录
            for (Path root : allowedLocalMediaRoots) {
                // 获取允许根目录的真实路径
                Path allowedRoot = root.toRealPath();
                // 检查候选路径是否等于根目录或以根目录为前缀
                if (candidate.equals(allowedRoot) || candidate.startsWith(allowedRoot)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 如果发生异常（如文件不存在、权限不足），视为不合法
            return false;
        }
        // 默认返回 false
        return false;
    }

    /**
     * 验证远程媒体 URL 的安全性。
     *
     * @param mediaRef 媒体 URL
     * @return 验证结果对象
     */
    NetworkSecurity.ValidationResult validateRemoteMediaUrl(String mediaRef) {
        // 委托给 NetworkSecurity 工具类进行验证
        return NetworkSecurity.validateUrlTarget(mediaRef);
    }

    /**
     * 获取允许接收消息的用户或群组 ID 列表。
     *
     * @return 允许列表
     */
    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    /**
     * 记录附件处理结果的记录类。
     *
     * @param mediaPaths 成功下载的本地媒体路径列表
     * @param recvLines 用于展示给用户看的附件信息行列表
     * @param attachmentMeta 附件元数据列表
     */
    private record AttachmentResult(List<String> mediaPaths, List<String> recvLines,
                                    List<Map<String, Object>> attachmentMeta) {
    }

    private record AttachmentDescriptor(String url, String filename, String contentType) {

        static AttachmentDescriptor from(QQAttachment attachment) {
            if (attachment == null) {
                return new AttachmentDescriptor("", "", "");
            }
            return new AttachmentDescriptor(
                    attachment.url() != null ? attachment.url() : "",
                    attachment.filename() != null ? attachment.filename() : "",
                    attachment.contentType() != null ? attachment.contentType() : ""
            );
        }
    }

    private record AttachmentDownload(String savedPath) {

        static AttachmentDownload success(String savedPath) {
            return new AttachmentDownload(savedPath);
        }

        static AttachmentDownload failed(String ignoredFilename) {
            return new AttachmentDownload(null);
        }

        boolean downloaded() {
            return savedPath != null && !savedPath.isBlank();
        }
    }

    private record MediaBytes(byte[] data, String filename) {
    }

    private record MediaReadResult(MediaBytes media, String reason) {

        static MediaReadResult success(MediaBytes media) {
            return new MediaReadResult(media, null);
        }

        static MediaReadResult failure(String reason) {
            return new MediaReadResult(null, reason);
        }

        boolean success() {
            return media != null && media.data() != null && media.filename() != null;
        }
    }
}
