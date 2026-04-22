package ricbot.integration.channel;

import lombok.extern.slf4j.Slf4j;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.integration.channel.event.ChannelEvent;
import ricbot.integration.llm.api.GroqTranscriptionProvider;
import ricbot.integration.llm.api.TranscriptionProvider;
import ricbot.integration.llm.openai.OpenAITranscriptionProvider;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Channel 抽象基类
 *
 * 设计目标：
 * 1. 统一各类渠道的公共行为
 * 2. 向消息总线发布入站消息
 * 3. 提供事件去重能力
 * 4. 提供语音转写能力
 *
 * 本次重构重点：
 * - 将多个 handleMessage 重载收敛为一个请求对象 + 一个核心方法
 * - 保留旧重载方法用于兼容外部已有子类调用
 */
@Slf4j
public abstract class BaseChannel {

    /**
     * 默认转写 provider
     */
    private static final String DEFAULT_TRANSCRIPTION_PROVIDER = "groq";

    /**
     * 最近事件 ID 最大保留数量，避免去重集合无限增长
     */
    private static final int MAX_RECENT_EVENT_IDS = 5000;

    /**
     * 渠道配置对象
     */
    protected final Object channelConfig;

    /**
     * 消息总线
     */
    protected final MessageBus bus;

    /**
     * 渠道标识名称
     */
    protected String name;

    /**
     * 渠道展示名称
     */
    protected String displayName;

    /**
     * 渠道运行状态
     */
    protected boolean running = false;

    /**
     * 转写 provider 名称，例如 groq / openai
     */
    protected String transcriptionProvider;

    /**
     * 转写 API Key
     */
    protected String transcriptionApiKey;

    /**
     * 转写 API Base
     */
    protected String transcriptionApiBase;

    /**
     * 最近事件 ID 队列：保持插入顺序，便于淘汰最老数据
     */
    private final ArrayDeque<String> recentEventIds = new ArrayDeque<>();

    /**
     * 最近事件 ID 集合：便于快速判重
     */
    private final Set<String> recentEventIdSet = new HashSet<>();

    protected BaseChannel(Object channelConfig, MessageBus bus) {
        this.channelConfig = channelConfig;
        this.bus = bus;
    }

    public String getName() {
        return name;
    }

    public boolean isRunning() {
        return running;
    }

    public abstract void start() throws Exception;

    public abstract void stop() throws Exception;

    public abstract List<String> getAllowFrom();

    public void setTranscriptionProvider(String provider) {
        this.transcriptionProvider = provider;
    }

    public void setTranscriptionApiKey(String apiKey) {
        this.transcriptionApiKey = apiKey;
    }

    public void setTranscriptionApiBase(String apiBase) {
        this.transcriptionApiBase = apiBase;
    }

    // =========================================================
    // Inbound message dispatch
    // =========================================================

    /**
     * 统一分发入站消息的核心方法。
     *
     * @param request 入站分发请求对象，包含消息的所有必要字段
     * @throws Exception 当请求为空或处理过程中出现异常时抛出
     */
    protected void dispatchInbound(InboundDispatchRequest request) throws Exception {
        // 1. 参数校验：确保请求对象不为空
        if (request == null) {
            throw new IllegalArgumentException("InboundDispatchRequest must not be null");
        }

        // 2. 深拷贝元数据，避免修改原始请求中的 Map，保证线程安全及数据隔离
        Map<String, Object> metadata = copyMetadata(request.metadata());

        // 3. 如果存在发送者名称且非空，将其放入元数据中（若 key 已存在则不覆盖）
        if (hasText(request.senderName())) {
            metadata.putIfAbsent("sender_name", request.senderName());
        }

        // 4. 构建入站消息对象
        InboundMessage msg = InboundMessages.of(
                getName(),                                  // 渠道名称
                request.senderId(),                         // 发送者 ID
                request.chatId(),                           // 会话 ID
                request.content(),                          // 消息内容
                safeMedia(request.media()),                 // 媒体资源列表（确保非 null）
                metadata,                                   // 元数据
                request.sessionKeyOverride(),               // 会话密钥覆盖值
                request.timestamp() != null ? request.timestamp() : LocalDateTime.now() // 时间戳，若未提供则使用当前时间
        );

        // 5. 将构建好的入站消息发布到消息总线
        bus.publishInbound(msg);
    }

    /**
     * 兼容旧调用方式：senderId/chatId/content/media/metadata
     *
     * 保留该方法，避免外部已有子类直接调用时报错。
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata
    ) throws Exception {
        dispatchInbound(
                InboundDispatchRequest.builder()
                        .senderId(senderId)
                        .chatId(chatId)
                        .content(content)
                        .media(media)
                        .metadata(metadata)
                        .build()
        );
    }

    /**
     * 兼容旧调用方式：增加 sessionKeyOverride
     */
    @Deprecated
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKeyOverride
    ) throws Exception {
        dispatchInbound(
                InboundDispatchRequest.builder()
                        .senderId(senderId)
                        .chatId(chatId)
                        .content(content)
                        .media(media)
                        .metadata(metadata)
                        .sessionKeyOverride(sessionKeyOverride)
                        .build()
        );
    }

    /**
     * 兼容旧调用方式：完整参数版本
     */
    @Deprecated
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String senderName,
            String sessionKeyOverride
    ) throws Exception {
        dispatchInbound(
                InboundDispatchRequest.builder()
                        .senderId(senderId)
                        .chatId(chatId)
                        .content(content)
                        .media(media)
                        .metadata(metadata)
                        .senderName(senderName)
                        .sessionKeyOverride(sessionKeyOverride)
                        .build()
        );
    }

    /**
     * 发布渠道事件：
     * 1. 判空
     * 2. 去重
     * 3. 转换为入站消息
     * 4. 自动补齐 channel/timestamp
     * 5. 发布到消息总线
     */
    protected void publishEvent(ChannelEvent event) throws Exception {
        if (event == null) {
            return;
        }
        if (!acceptEvent(event.eventId())) {
            return;
        }

        InboundMessage msg = event.toInboundMessage();

        if (!hasText(msg.getChannel())) {
            msg.setChannel(getName());
        }
        if (msg.getTimestamp() == null) {
            msg.setTimestamp(LocalDateTime.now());
        }

        bus.publishInbound(msg);
    }

    /**
     * 事件去重。
     *
     * 线程安全：
     * - 用 synchronized 保护 recentEventIds / recentEventIdSet
     */
    private synchronized boolean acceptEvent(String eventId) {
        if (!hasText(eventId)) {
            return true;
        }
        if (recentEventIdSet.contains(eventId)) {
            return false;
        }

        recentEventIds.addLast(eventId);
        recentEventIdSet.add(eventId);

        while (recentEventIds.size() > MAX_RECENT_EVENT_IDS) {
            String removed = recentEventIds.removeFirst();
            recentEventIdSet.remove(removed);
        }
        return true;
    }

    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        // 默认不支持流式输出，子类按需覆盖
    }

    public abstract void send(OutboundMessage msg) throws Exception;

    // =========================================================
    // Audio transcription
    // =========================================================

    public String transcribeAudio(Path filePath) {
        if (filePath == null) {
            return "";
        }

        String providerName = resolveTranscriptionProviderName();
        TranscriptionProvider provider = buildTranscriptionProvider(providerName);

        if (provider == null) {
            log.warn("当前渠道没有可用的语音转写 provider：{}", getName());
            return "";
        }

        return provider.transcribe(filePath);
    }

    /**
     * 解析最终使用的转写 provider 名称
     */
    protected String resolveTranscriptionProviderName() {
        if (hasText(transcriptionProvider)) {
            return transcriptionProvider;
        }
        return DEFAULT_TRANSCRIPTION_PROVIDER;
    }

    /**
     * 构建具体的转写 provider
     */
    protected TranscriptionProvider buildTranscriptionProvider(String providerName) {
        String name = hasText(providerName)
                ? providerName.trim().toLowerCase(Locale.ROOT)
                : DEFAULT_TRANSCRIPTION_PROVIDER;

        return switch (name) {
            case "openai", "whisper", "openai_whisper" ->
                    new OpenAITranscriptionProvider(transcriptionApiKey, transcriptionApiBase);

            case "groq", "groq_whisper" ->
                    new GroqTranscriptionProvider(transcriptionApiKey, transcriptionApiBase);

            default -> {
                log.warn("不支持的语音转写 provider：{}，将回退到 {}", providerName, DEFAULT_TRANSCRIPTION_PROVIDER);
                yield new GroqTranscriptionProvider(transcriptionApiKey, transcriptionApiBase);
            }
        };
    }

    // =========================================================
    // Helper methods
    // =========================================================

    /**
     * 拷贝 metadata，避免直接修改调用方传入的 map
     */
    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        return metadata == null ? new HashMap<>() : new HashMap<>(metadata);
    }

    /**
     * 保证 media 非空，减少下游 NPE 风险
     */
    private List<String> safeMedia(List<String> media) {
        return media == null ? Collections.emptyList() : media;
    }

    /**
     * 统一字符串判空逻辑
     */
    protected boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // =========================================================
    // Request object
    // =========================================================

    /**
     * 入站分发请求对象。
     *
     * 好处：
     * 1. 替代参数爆炸式重载
     * 2. 增加字段时不需要继续新增 handleMessage(...)
     * 3. 调用方语义更清晰，避免位置参数传错
     */
    protected static final class InboundDispatchRequest {
        /**
         * 发送者 ID
         */
        private final String senderId;

        /**
         * 会话/聊天 ID
         */
        private final String chatId;

        /**
         * 消息内容
         */
        private final String content;

        /**
         * 媒体资源列表（如图片、音频链接等）
         */
        private final List<String> media;

        /**
         * 附加元数据
         */
        private final Map<String, Object> metadata;

        /**
         * 发送者名称
         */
        private final String senderName;

        /**
         * 会话密钥覆盖值（用于特定场景下的会话标识覆盖）
         */
        private final String sessionKeyOverride;

        /**
         * 消息时间戳
         */
        private final LocalDateTime timestamp;

        /**
         * 私有构造函数，仅通过 Builder 创建实例
         *
         * @param builder 构建器对象
         */
        private InboundDispatchRequest(Builder builder) {
            this.senderId = builder.senderId;
            this.chatId = builder.chatId;
            this.content = builder.content;
            this.media = builder.media;
            this.metadata = builder.metadata;
            this.senderName = builder.senderName;
            this.sessionKeyOverride = builder.sessionKeyOverride;
            this.timestamp = builder.timestamp;
        }

        /**
         * 创建一个新的构建器实例
         *
         * @return Builder 实例
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * 获取发送者 ID
         *
         * @return 发送者 ID
         */
        public String senderId() {
            return senderId;
        }

        /**
         * 获取会话/聊天 ID
         *
         * @return 会话 ID
         */
        public String chatId() {
            return chatId;
        }

        /**
         * 获取消息内容
         *
         * @return 消息内容
         */
        public String content() {
            return content;
        }

        /**
         * 获取媒体资源列表
         *
         * @return 媒体资源列表
         */
        public List<String> media() {
            return media;
        }

        /**
         * 获取附加元数据
         *
         * @return 元数据 Map
         */
        public Map<String, Object> metadata() {
            return metadata;
        }

        /**
         * 获取发送者名称
         *
         * @return 发送者名称
         */
        public String senderName() {
            return senderName;
        }

        /**
         * 获取会话密钥覆盖值
         *
         * @return 会话密钥覆盖值
         */
        public String sessionKeyOverride() {
            return sessionKeyOverride;
        }

        /**
         * 获取消息时间戳
         *
         * @return 时间戳
         */
        public LocalDateTime timestamp() {
            return timestamp;
        }

        /**
         * 构建器类，用于链式创建 InboundDispatchRequest 实例
         */
        protected static final class Builder {
            /**
             * 发送者 ID
             */
            private String senderId;

            /**
             * 会话/聊天 ID
             */
            private String chatId;

            /**
             * 消息内容
             */
            private String content;

            /**
             * 媒体资源列表
             */
            private List<String> media;

            /**
             * 附加元数据
             */
            private Map<String, Object> metadata;

            /**
             * 发送者名称
             */
            private String senderName;

            /**
             * 会话密钥覆盖值
             */
            private String sessionKeyOverride;

            /**
             * 消息时间戳
             */
            private LocalDateTime timestamp;

            /**
             * 设置发送者 ID
             *
             * @param senderId 发送者 ID
             * @return 当前 Builder 实例
             */
            public Builder senderId(String senderId) {
                this.senderId = senderId;
                return this;
            }

            /**
             * 设置会话/聊天 ID
             *
             * @param chatId 会话 ID
             * @return 当前 Builder 实例
             */
            public Builder chatId(String chatId) {
                this.chatId = chatId;
                return this;
            }

            /**
             * 设置消息内容
             *
             * @param content 消息内容
             * @return 当前 Builder 实例
             */
            public Builder content(String content) {
                this.content = content;
                return this;
            }

            /**
             * 设置媒体资源列表
             *
             * @param media 媒体资源列表
             * @return 当前 Builder 实例
             */
            public Builder media(List<String> media) {
                this.media = media;
                return this;
            }

            /**
             * 设置附加元数据
             *
             * @param metadata 元数据 Map
             * @return 当前 Builder 实例
             */
            public Builder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            /**
             * 设置发送者名称
             *
             * @param senderName 发送者名称
             * @return 当前 Builder 实例
             */
            public Builder senderName(String senderName) {
                this.senderName = senderName;
                return this;
            }

            /**
             * 设置会话密钥覆盖值
             *
             * @param sessionKeyOverride 会话密钥覆盖值
             * @return 当前 Builder 实例
             */
            public Builder sessionKeyOverride(String sessionKeyOverride) {
                this.sessionKeyOverride = sessionKeyOverride;
                return this;
            }

            /**
             * 设置消息时间戳
             *
             * @param timestamp 时间戳
             * @return 当前 Builder 实例
             */
            public Builder timestamp(LocalDateTime timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            /**
             * 构建并返回 InboundDispatchRequest 实例
             *
             * @return 构建完成的 InboundDispatchRequest 对象
             */
            public InboundDispatchRequest build() {
                return new InboundDispatchRequest(this);
            }
        }
    }
}
