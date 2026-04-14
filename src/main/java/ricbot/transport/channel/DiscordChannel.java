package ricbot.transport.channel;


import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord 渠道实现。
 *
 * 主要目标：
 * 1. 通过 discord bot 接收入站消息
 * 2. 支持 slash commands
 * 3. 支持附件下载
 * 4. 支持流式输出（编辑消息）
 * 5. 支持 reaction / typing 等交互体验
 */
public class DiscordChannel extends BaseChannel {

    public static class DiscordConfig {
        private boolean enabled = false;
        private String token = "";
        private List<String> allowFrom = new ArrayList<>();
        private int intents = 37377;
        private String groupPolicy = "mention";
        private String readReceiptEmoji = "👀";
        private String workingEmoji = "🔧";
        private double workingEmojiDelay = 2.0;
        private boolean streaming = true;
        private String proxy;
        private String proxyUsername;
        private String proxyPassword;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }

        public int getIntents() { return intents; }
        public void setIntents(int intents) { this.intents = intents; }

        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }

        public String getReadReceiptEmoji() { return readReceiptEmoji; }
        public void setReadReceiptEmoji(String readReceiptEmoji) { this.readReceiptEmoji = readReceiptEmoji; }

        public String getWorkingEmoji() { return workingEmoji; }
        public void setWorkingEmoji(String workingEmoji) { this.workingEmoji = workingEmoji; }

        public double getWorkingEmojiDelay() { return workingEmojiDelay; }
        public void setWorkingEmojiDelay(double workingEmojiDelay) { this.workingEmojiDelay = workingEmojiDelay; }

        public boolean isStreaming() { return streaming; }
        public void setStreaming(boolean streaming) { this.streaming = streaming; }

        public String getProxy() { return proxy; }
        public void setProxy(String proxy) { this.proxy = proxy; }

        public String getProxyUsername() { return proxyUsername; }
        public void setProxyUsername(String proxyUsername) { this.proxyUsername = proxyUsername; }

        public String getProxyPassword() { return proxyPassword; }
        public void setProxyPassword(String proxyPassword) { this.proxyPassword = proxyPassword; }
    }

    /**
     * 流式输出缓冲区。
     */
    public static class StreamBuf {
        private String text = "";
        private String messageId;
        private long lastEditMillis = 0;
        private String streamId;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public long getLastEditMillis() { return lastEditMillis; }
        public void setLastEditMillis(long lastEditMillis) { this.lastEditMillis = lastEditMillis; }

        public String getStreamId() { return streamId; }
        public void setStreamId(String streamId) { this.streamId = streamId; }
    }

    private static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
    private static final int MAX_MESSAGE_LEN = 2000;
    private static final long TYPING_INTERVAL_MS = 8000;
    private static final long STREAM_EDIT_INTERVAL_MS = 500;

    private final DiscordConfig config;
    private Object client;
    private String botUserId;

    private final Map<String, StreamBuf> streamBufs = new ConcurrentHashMap<>();
    private final Map<String, String> pendingReactions = new ConcurrentHashMap<>();
    private final Map<String, String> workingEmojiTasks = new ConcurrentHashMap<>();

    public DiscordChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "discord";
        this.displayName = "Discord";
        this.config = (config instanceof DiscordConfig c) ? c : new DiscordConfig();
    }

    @Override
    public void start() throws Exception {
        if (config.getToken() == null || config.getToken().isBlank()) {
            System.err.println("Discord token not configured");
            return;
        }

        running = true;

        // TODO:
        // 1. 初始化 discord client
        // 2. 注册 on_ready / on_message / slash commands
        // 3. 启动 websocket 连接

        System.out.println("Discord bot started");
    }

    @Override
    public void stop() throws Exception {
        running = false;

        // TODO: 关闭 discord client
        streamBufs.clear();
        pendingReactions.clear();
        workingEmojiTasks.clear();
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        // TODO:
        // 1. resolve channel by msg.chatId
        // 2. 如果有附件，先传文件
        // 3. 文本分块发送（Discord 2000 字限制）
        // 4. 处理 reply_to / thread / mention

        System.out.println("Discord send -> " + msg.getChatId() + ": " + msg.getContent());
    }

    /**
     * 支持流式输出：通过编辑同一条消息实现增量显示。
     */
    @Override
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        StreamBuf buf = streamBufs.computeIfAbsent(chatId, k -> new StreamBuf());
        buf.setText(buf.getText() + (delta != null ? delta : ""));

        if (buf.getText().isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();

        Boolean streamEnd = metadata != null ? (Boolean) metadata.get("_stream_end") : false;
        if (Boolean.TRUE.equals(streamEnd)) {
            finalizeStream(chatId, buf);
            return;
        }

        if (buf.getMessageId() == null) {
            // TODO: 首次发送 Discord message，并保存 messageId
            buf.setMessageId("TODO_DISCORD_MESSAGE_ID");
            buf.setLastEditMillis(now);
            return;
        }

        if ((now - buf.getLastEditMillis()) < STREAM_EDIT_INTERVAL_MS) {
            return;
        }

        // TODO: 编辑已有 Discord message 内容
        buf.setLastEditMillis(now);
    }

    private void finalizeStream(String chatId, StreamBuf buf) {
        // TODO:
        // 1. 把最终内容分块
        // 2. 第一块编辑已有消息
        // 3. 超出部分继续发新消息
        streamBufs.remove(chatId);
    }

    /**
     * 处理入站 Discord 消息。
     */
    public void handleDiscordMessage(
            String senderId,
            String channelId,
            String content,
            List<String> mediaPaths,
            Map<String, Object> metadata
    ) throws Exception {
        handleMessage(senderId, channelId, content, mediaPaths, metadata);
    }

    /**
     * 下载附件。
     */
    public List<String> downloadAttachments(List<String> attachmentUrls) {
        // TODO: 下载 Discord 附件到 media dir
        return new ArrayList<>(attachmentUrls);
    }

    /**
     * 启动 typing 指示器。
     */
    public void startTyping(String channelId) {
        // TODO: 调 Discord typing API
    }

    /**
     * 停止 typing。
     */
    public void stopTyping(String channelId) {
        // TODO: 停止 typing keepalive
    }

    /**
     * 清除 read receipt / working emoji 等状态。
     */
    public void clearReactions(String channelId) {
        pendingReactions.remove(channelId);
        workingEmojiTasks.remove(channelId);
    }

    /**
     * 文本分块，适配 Discord 2000 字限制。
     */
    public List<String> splitMessage(String text) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }

        List<String> parts = new ArrayList<>();
        String remaining = text;

        while (remaining.length() > MAX_MESSAGE_LEN) {
            parts.add(remaining.substring(0, MAX_MESSAGE_LEN));
            remaining = remaining.substring(MAX_MESSAGE_LEN);
        }
        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }
        return parts;
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}