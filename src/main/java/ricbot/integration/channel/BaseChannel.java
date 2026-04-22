package ricbot.integration.channel;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus; // 导入消息总线类，用于发布入站消息
import ricbot.domain.message.OutboundMessage; // 导出发消息类，用于发送出站消息
import ricbot.integration.llm.api.GroqTranscriptionProvider; // 导入 Groq 语音转写提供者实现
import ricbot.integration.llm.openai.OpenAITranscriptionProvider; // 导入 OpenAI 语音转写提供者实现
import ricbot.integration.llm.api.TranscriptionProvider; // 导入语音转写提供者接口
import ricbot.integration.channel.event.ChannelEvent; // 导入渠道事件类

import java.nio.file.Path; // 导入文件路径类，用于处理音频文件路径
import java.time.LocalDateTime; // 导入本地日期时间类，用于时间戳处理
import java.util.ArrayDeque; // 导入数组双端队列，用于维护最近的事件 ID 队列
import java.util.HashMap; // 导入哈希映射，用于处理元数据
import java.util.HashSet; // 导入哈希集合，用于快速查找最近的事件 ID
import java.util.List; // 导入列表接口，用于处理媒体文件列表等
import java.util.Map; // 导入映射接口，用于处理配置和元数据
import java.util.Set; // 导入集合接口，用于存储事件 ID 集合

/**
 * Channel 抽象基类
 * 定义了所有渠道共同的基础行为和属性
 */
public abstract class BaseChannel {

    /**
     * 渠道配置对象
     * 通常是一个 Map 或特定的配置类实例，存储渠道的初始化参数
     */
    protected final Object channelConfig;

    /**
     * 消息总线实例
     * 用于将接收到的消息发布到系统内部进行处理
     */
    protected final MessageBus bus;

    protected String name; // 渠道的唯一标识名称
    protected String displayName; // 渠道的显示名称，用于前端展示
    protected boolean running = false; // 渠道运行状态标志，true 表示正在运行
    protected String transcriptionProvider; // 语音转写服务提供商名称，如 "groq", "openai"
    protected String transcriptionApiKey; // 语音转写服务的 API 密钥
    protected String transcriptionApiBase; // 语音转写服务的基础 URL（可选，用于自定义端点）

    // 用于去重的最近事件 ID 队列，保持插入顺序
    private final ArrayDeque<String> recentEventIds = new ArrayDeque<>();
    // 用于快速查找事件 ID 是否已存在的集合
    private final Set<String> recentEventIdSet = new HashSet<>();

    /**
     * 构造函数
     *
     * @param channelConfig 渠道配置对象
     * @param bus           消息总线实例
     */
    protected BaseChannel(
            Object channelConfig,
            MessageBus bus
    ) {
        this.channelConfig = channelConfig; // 初始化渠道配置
        this.bus = bus; // 初始化消息总线
    }

    /**
     * 获取渠道名称
     *
     * @return 渠道名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取渠道显示名称
     *
     * @return 渠道显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 检查渠道是否正在运行
     *
     * @return 如果正在运行返回 true，否则返回 false
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 启动渠道
     * 具体实现由子类提供
     *
     * @throws Exception 启动过程中可能抛出的异常
     */
    public abstract void start() throws Exception;

    /**
     * 停止渠道
     * 具体实现由子类提供
     *
     * @throws Exception 停止过程中可能抛出的异常
     */
    public abstract void stop() throws Exception;

    /**
     * 获取允许的来源列表
     * 具体实现由子类提供，用于权限控制或过滤
     *
     * @return 允许的来源字符串列表
     */
    public abstract List<String> getAllowFrom();

    /**
     * 设置语音转写提供商名称
     *
     * @param provider 提供商名称，如 "groq", "openai"
     */
    public void setTranscriptionProvider(String provider) {
        this.transcriptionProvider = provider;
    }

    /**
     * 设置语音转写 API 密钥
     *
     * @param apiKey API 密钥
     */
    public void setTranscriptionApiKey(String apiKey) {
        this.transcriptionApiKey = apiKey;
    }

    /**
     * 设置语音转写 API 基础 URL
     *
     * @param apiBase API 基础 URL
     */
    public void setTranscriptionApiBase(String apiBase) {
        this.transcriptionApiBase = apiBase;
    }

    /**
     * 从配置中获取字符串类型的值
     *
     * @param key 配置键
     * @return 对应的字符串值，如果不存在或配置不是 Map 类型则返回 null
     */
    protected String getStringConfig(String key) {
        // 检查配置对象是否是 Map 类型
        if (channelConfig instanceof Map<?, ?> m) {
            Object v = m.get(key); // 获取指定键的值
            // 如果值不为 null，转换为字符串返回，否则返回 null
            return v != null ? String.valueOf(v) : null;
        }
        return null; // 配置不是 Map 类型，返回 null
    }

    /**
     * 处理入站消息入口方法
     * 捕获异常并记录错误日志，避免单个消息处理失败影响整体运行
     *
     * @param content    消息内容
     * @param chatId     聊天 ID
     * @param senderId   发送者 ID
     * @param senderName 发送者名称
     * @param metadata   消息元数据
     */
    protected void onMessage(
            String content,
            String chatId,
            String senderId,
            String senderName,
            Map<String, Object> metadata
    ) {
        try {
            // 调用核心的 handleMessage 方法处理消息
            handleMessage(senderId, chatId, content, null, metadata, senderName, null);
        } catch (InterruptedException e) {
            // 如果线程被中断，恢复中断状态
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 捕获其他异常并打印错误信息，包含渠道名称和异常消息
            System.err.println("Error processing message from " + getName() + ": " + e.getMessage());
        }
    }

    /**
     * 处理入站消息 (遗留版本，兼容性方法)
     *
     * @param senderId 发送者 ID
     * @param chatId   聊天 ID
     * @param content  消息内容
     * @param media    媒体文件列表
     * @param metadata 消息元数据
     * @throws Exception 处理过程中可能抛出的异常
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata
    ) throws Exception {
        // 委托给更完整参数的 handleMessage 方法，senderName 和 sessionKeyOverride 为 null
        handleMessage(senderId, chatId, content, media, metadata, null);
    }

    /**
     * 处理入站消息 (带 sessionKeyOverride 参数)
     *
     * @param senderId          发送者 ID
     * @param chatId            聊天 ID
     * @param content           消息内容
     * @param media             媒体文件列表
     * @param metadata          消息元数据
     * @param sessionKeyOverride 会话键覆盖值，用于指定特定的会话上下文
     * @throws Exception 处理过程中可能抛出的异常
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKeyOverride
    ) throws Exception {
        // 委托给最完整参数的 handleMessage 方法，senderName 为 null
        handleMessage(senderId, chatId, content, media, metadata, null, sessionKeyOverride);
    }

    /**
     * 核心入站消息处理方法
     * 构建 InboundMessage 对象并通过消息总线发布
     *
     * @param senderId          发送者 ID
     * @param chatId            聊天 ID
     * @param content           消息内容
     * @param media             媒体文件列表
     * @param metadata          消息元数据
     * @param senderName        发送者名称
     * @param sessionKeyOverride 会话键覆盖值
     * @throws Exception 处理过程中可能抛出的异常
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String senderName,
            String sessionKeyOverride
    ) throws Exception {
        // 复制元数据，避免修改原始引用
        Map<String, Object> m = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        // 如果发送者名称不为空且非空白，将其加入元数据
        if (senderName != null && !senderName.isBlank()) {
            m.putIfAbsent("sender_name", senderName);
        }
        InboundMessage msg = InboundMessages.of(
                getName(),
                senderId,
                chatId,
                content,
                media,
                m,
                sessionKeyOverride,
                java.time.LocalDateTime.now()
        );
        bus.publishInbound(msg); // 通过消息总线发布入站消息
    }

    /**
     * 发布渠道事件
     * 将 ChannelEvent 转换为 InboundMessage 并发布，同时进行事件去重检查
     *
     * @param event 渠道事件对象
     * @throws Exception 发布过程中可能抛出的异常
     */
    protected void publishEvent(ChannelEvent event) throws Exception {
        if (event == null) {
            return; // 如果事件为空，直接返回
        }
        if (!acceptEvent(event.eventId())) {
            return; // 如果事件 ID 已存在（重复事件），则拒绝处理
        }

        // 将事件转换为入站消息
        InboundMessage msg = event.toInboundMessage();
        // 如果消息中未设置渠道名称，则使用当前渠道名称
        if (msg.getChannel() == null || msg.getChannel().isBlank()) {
            msg.setChannel(getName());
        }
        // 如果消息中未设置时间戳，则使用当前时间
        if (msg.getTimestamp() == null) {
            msg.setTimestamp(LocalDateTime.now());
        }
        bus.publishInbound(msg); // 发布入站消息
    }

    /**
     * 检查并接受事件 ID，用于去重
     * 同步方法，确保线程安全
     *
     * @param eventId 事件 ID
     * @return 如果是新事件返回 true，如果是重复事件返回 false
     */
    private synchronized boolean acceptEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true; // 如果事件 ID 为空或空白，视为新事件
        }
        if (recentEventIdSet.contains(eventId)) {
            return false; // 如果集合中已存在该事件 ID，视为重复事件，拒绝
        }
        // 将新事件 ID 添加到队列和集合中
        recentEventIds.addLast(eventId);
        recentEventIdSet.add(eventId);
        
        // 限制历史记录大小，防止内存无限增长
        int max = 5000; // 最大保留的事件 ID 数量
        while (recentEventIds.size() > max) {
            String removed = recentEventIds.removeFirst(); // 移除最旧的事件 ID
            recentEventIdSet.remove(removed); // 从集合中也移除该 ID
        }
        return true; // 接受该新事件
    }

    /**
     * 发送增量消息（流式输出）
     * 默认实现为空，子类可根据需要重写以支持流式响应
     *
     * @param chatId   聊天 ID
     * @param delta    增量内容
     * @param metadata 元数据
     * @throws Exception 发送过程中可能抛出的异常
     */
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        // 默认不支持流式，子类可重写
    }

    /**
     * 发送出站消息
     * 具体实现由子类提供
     *
     * @param msg 出站消息对象
     * @throws Exception 发送过程中可能抛出的异常
     */
    public abstract void send(OutboundMessage msg) throws Exception;

    // =========================================================
    // Audio transcription (语音转写相关方法)
    // =========================================================

    /**
     * 转写音频文件
     *
     * @param filePath 音频文件路径
     * @return 转写后的文本内容，如果失败或文件为空则返回空字符串
     */
    public String transcribeAudio(Path filePath) {
        if (filePath == null) {
            return ""; // 如果文件路径为空，返回空字符串
        }

        // 解析要使用的语音转写提供商名称
        String providerName = resolveTranscriptionProviderName();
        // 构建对应的语音转写提供者实例
        TranscriptionProvider provider = buildTranscriptionProvider(providerName);

        if (provider == null) {
            // 如果没有可用的提供商，打印错误信息并返回空字符串
            System.err.println("当前渠道没有可用的语音转写 provider：" + getName());
            return "";
        }

        // 调用提供商的转写方法并返回结果
        return provider.transcribe(filePath);
    }

    /**
     * 解析语音转写提供商名称
     * 如果未配置，则默认使用 "groq"
     *
     * @return 提供商名称
     */
    protected String resolveTranscriptionProviderName() {
        // 如果已配置提供商名称且非空白，则使用配置的名称
        if (transcriptionProvider != null && !transcriptionProvider.isBlank()) {
            return transcriptionProvider;
        }
        return "groq"; // 默认返回 "groq"
    }

    /**
     * 构建语音转写提供者实例
     * 根据提供商名称创建对应的 TranscriptionProvider 实现类
     *
     * @param providerName 提供商名称
     * @return TranscriptionProvider 实例，如果名称不支持则回退到 Groq
     */
    protected TranscriptionProvider buildTranscriptionProvider(String providerName) {
        // 规范化提供商名称：去除空格并转为小写，如果为空则默认为 "groq"
        String name = providerName != null
                ? providerName.trim().toLowerCase(java.util.Locale.ROOT)
                : "groq";

        // 根据名称切换创建不同的提供者实例
        return switch (name) {
            case "openai", "whisper", "openai_whisper" ->
                    // 创建 OpenAI 语音转写提供者
                    new OpenAITranscriptionProvider(transcriptionApiKey, transcriptionApiBase);

            case "groq", "groq_whisper" ->
                    // 创建 Groq 语音转写提供者
                    new GroqTranscriptionProvider(transcriptionApiKey, transcriptionApiBase);

            default -> {
                // 对于不支持的提供商名称，打印警告并回退到 Groq
                System.err.println("不支持的语音转写 provider：" + providerName + "，将回退到 groq");
                yield new GroqTranscriptionProvider(transcriptionApiKey, transcriptionApiBase);
            }
        };
    }
}
