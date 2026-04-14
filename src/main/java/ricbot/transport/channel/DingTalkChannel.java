package ricbot.transport.channel;


import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉渠道实现（Stream Mode）。
 *
 * 主要目标：
 * 1. 通过钉钉 Stream SDK 接收入站消息
 * 2. 支持图片 / 文件下载
 * 3. 把消息转成统一 InboundMessage 发给主 Agent
 * 4. 通过 HTTP API 发送文本和媒体消息
 */
public class DingTalkChannel extends BaseChannel {

    public static class DingTalkConfig {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }

    private final DingTalkConfig config;
    private Object client;
    private Object httpClient;

    /**
     * Access Token 缓存
     */
    private String accessToken;
    private long tokenExpiryEpochSeconds = 0;

    /**
     * 防止后台任务被 GC / 丢失引用
     */
    private final Set<String> backgroundTasks = ConcurrentHashMap.newKeySet();

    public DingTalkChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "dingtalk";
        this.displayName = "DingTalk";
        this.config = (config instanceof DingTalkConfig c) ? c : new DingTalkConfig();
    }

    @Override
    public void start() throws Exception {
        if (config.getClientId() == null || config.getClientId().isBlank()
                || config.getClientSecret() == null || config.getClientSecret().isBlank()) {
            System.err.println("DingTalk client_id and client_secret not configured");
            return;
        }

        running = true;

        // TODO: 初始化钉钉 HTTP 客户端
        // TODO: 初始化 DingTalkStreamClient
        // TODO: 注册回调处理器
        // TODO: 启动 reconnect loop

        System.out.println("DingTalk bot started with Stream Mode");
    }

    @Override
    public void stop() throws Exception {
        running = false;

        // TODO: 关闭 http client
        // TODO: 停止 stream client
        backgroundTasks.clear();
    }

    /**
     * 获取 / 刷新 access token。
     */
    public synchronized String getAccessToken() {
        long now = System.currentTimeMillis() / 1000;
        if (accessToken != null && now < tokenExpiryEpochSeconds) {
            return accessToken;
        }

        // TODO: 调 https://api.dingtalk.com/v1.0/oauth2/accessToken
        // 这里先给占位
        this.accessToken = "TODO_ACCESS_TOKEN";
        this.tokenExpiryEpochSeconds = now + 7200 - 60;
        return accessToken;
    }

    /**
     * 入站消息处理入口。
     *
     * 对应 Python 里的 _on_message / handler.process(...) 最终调用的逻辑。
     */
    public void onMessage(
            String content,
            String senderId,
            String senderName,
            String conversationType,
            String conversationId
    ) throws Exception {
        String chatId;
        if ("2".equals(conversationType) || "group".equalsIgnoreCase(conversationType)) {
            chatId = "group:" + conversationId;
        } else {
            chatId = conversationId;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sender_name", senderName);
        metadata.put("conversation_type", conversationType);
        metadata.put("conversation_id", conversationId);

        handleMessage(senderId, chatId, content, new ArrayList<>(), metadata);
    }

    /**
     * 下载钉钉文件到本地。
     */
    public String downloadDingTalkFile(String downloadCode, String fileName, String senderUid) {
        // TODO: 调用钉钉下载接口，把文件保存到 media 目录
        // 当前先返回一个占位本地路径
        return "/tmp/dingtalk/" + senderUid + "/" + fileName;
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        String token = getAccessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("DingTalk access token unavailable");
        }

        // chat_id 可能是：
        // - group:xxx
        // - 1:1 conversation id
        String targetChatId = msg.getChatId();

        // TODO:
        // 1. 如果有媒体，先上传媒体
        // 2. 再发文本
        // 3. 区分私聊 / 群聊接口
        // 4. 按钉钉消息结构组装 payload

        System.out.println("DingTalk send -> " + targetChatId + ": " + msg.getContent());
    }

    /**
     * 判断 URL 是否是 http(s)。
     */
    public boolean isHttpUrl(String value) {
        if (value == null) return false;
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /**
     * 猜测上传类型：image / voice / video / file
     */
    public String guessUploadType(String mediaRef) {
        if (mediaRef == null) return "file";
        String lower = mediaRef.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "image";
        }
        if (lower.endsWith(".amr") || lower.endsWith(".mp3") || lower.endsWith(".wav")
                || lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".aac")) {
            return "voice";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi")
                || lower.endsWith(".mkv") || lower.endsWith(".webm")) {
            return "video";
        }
        return "file";
    }

    /**
     * 猜测默认文件名
     */
    public String guessFilename(String mediaRef, String uploadType) {
        if (mediaRef == null || mediaRef.isBlank()) {
            return switch (uploadType) {
                case "image" -> "image.jpg";
                case "voice" -> "audio.amr";
                case "video" -> "video.mp4";
                default -> "file.bin";
            };
        }
        Path p = Path.of(mediaRef);
        String fileName = p.getFileName() != null ? p.getFileName().toString() : "";
        if (!fileName.isBlank()) {
            return fileName;
        }
        return switch (uploadType) {
            case "image" -> "image.jpg";
            case "voice" -> "audio.amr";
            case "video" -> "video.mp4";
            default -> "file.bin";
        };
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}