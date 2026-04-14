package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信（WeCom）渠道实现。
 *
 * 对应 Python: wecom.py
 *
 * 主要职责：
 * 1. 通过 WebSocket 长连接收消息
 * 2. 按 messageId 去重
 * 3. 支持文本 / 图片 / 语音 / 文件 / mixed
 * 4. 支持 enter_chat 欢迎语
 * 5. 出站支持文本与媒体上传
 */
public class WecomChannel extends BaseChannel {

    public static class WecomConfig {
        private boolean enabled = false;
        private String botId = "";
        private String secret = "";
        private List<String> allowFrom = new ArrayList<>();
        private String welcomeMessage = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBotId() { return botId; }
        public void setBotId(String botId) { this.botId = botId; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public String getWelcomeMessage() { return welcomeMessage; }
        public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    }

    public interface WecomClient {
        void connect(WecomListener listener) throws Exception;
        void disconnect() throws Exception;
        void sendText(String chatId, String content, Object frameHeaders) throws Exception;
        void sendMedia(String chatId, String mediaType, String filePath, Object frameHeaders) throws Exception;
    }

    public interface WecomListener {
        void onConnected(Object frame);
        void onAuthenticated(Object frame);
        void onDisconnected(Object frame);
        void onError(Object frame);
        void onTextMessage(Object frame);
        void onImageMessage(Object frame);
        void onVoiceMessage(Object frame);
        void onFileMessage(Object frame);
        void onMixedMessage(Object frame);
        void onEnterChat(Object frame);
    }

    private static final long WECOM_UPLOAD_MAX_BYTES = 1024L * 1024 * 200;

    private final WecomConfig config;
    private WecomClient client;

    private final LinkedHashMap<String, Boolean> processedMessageIds = new LinkedHashMap<>();
    private final Map<String, Object> chatFrames = new ConcurrentHashMap<>();

    public WecomChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "wecom";
        this.displayName = "WeCom";
        this.config = (config instanceof WecomConfig c) ? c : new WecomConfig();
    }

    public void setClient(WecomClient client) {
        this.client = client;
    }

    @Override
    public void start() throws Exception {
        if (config.getBotId() == null || config.getBotId().isBlank()
                || config.getSecret() == null || config.getSecret().isBlank()) {
            throw new IllegalStateException("WeCom bot_id and secret not configured");
        }
        if (client == null) {
            throw new IllegalStateException("WeCom client not injected");
        }

        running = true;
        client.connect(new WecomListener() {
            @Override public void onConnected(Object frame) { System.out.println("WeCom connected"); }
            @Override public void onAuthenticated(Object frame) { System.out.println("WeCom authenticated"); }
            @Override public void onDisconnected(Object frame) { System.out.println("WeCom disconnected: " + frame); }
            @Override public void onError(Object frame) { System.err.println("WeCom error: " + frame); }
            @Override public void onTextMessage(Object frame) { processMessage(frame, "text"); }
            @Override public void onImageMessage(Object frame) { processMessage(frame, "image"); }
            @Override public void onVoiceMessage(Object frame) { processMessage(frame, "voice"); }
            @Override public void onFileMessage(Object frame) { processMessage(frame, "file"); }
            @Override public void onMixedMessage(Object frame) { processMessage(frame, "mixed"); }
            @Override public void onEnterChat(Object frame) { handleEnterChat(frame); }
        });
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (client != null) {
            client.disconnect();
        }
    }

    public void send(OutboundMessage msg) throws Exception {
        if (client == null) return;

        Object frameHeaders = chatFrames.get(msg.getChatId());

        if (msg.getMedia() != null) {
            for (String mediaPath : msg.getMedia()) {
                Path p = Path.of(mediaPath).toAbsolutePath().normalize();
                if (!Files.isRegularFile(p)) continue;
                if (Files.size(p) > WECOM_UPLOAD_MAX_BYTES) continue;

                String mediaType = guessWecomMediaType(p.getFileName().toString());
                client.sendMedia(msg.getChatId(), mediaType, p.toString(), frameHeaders);
            }
        }

        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            client.sendText(msg.getChatId(), msg.getContent(), frameHeaders);
        }
    }

    private void processMessage(Object frame, String msgType) {
        try {
            Map<String, Object> body = extractBody(frame);
            String messageId = stringValue(body.get("msgid"));
            if (messageId.isBlank()) {
                messageId = stringValue(body.get("message_id"));
            }

            if (!messageId.isBlank()) {
                if (processedMessageIds.containsKey(messageId)) {
                    return;
                }
                processedMessageIds.put(messageId, Boolean.TRUE);
                trimProcessed();
            }

            String fromUser = stringValue(body.get("from_userid"));
            String content = stringValue(body.get("content"));
            String chatId = fromUser;

            if (chatId.isBlank()) {
                return;
            }

            chatFrames.put(chatId, frame);

            List<String> mediaPaths = new ArrayList<>();

            if (!"text".equals(msgType)) {
                String fileName = messageId.isBlank() ? msgType + ".bin" : messageId + "_" + msgType + ".bin";
                String saved = saveWecomMedia(body, fileName, msgType);
                if (saved != null) {
                    mediaPaths.add(saved);
                }
                if (content.isBlank()) {
                    content = msgTypeDisplay(msgType);
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("wecom_msg_type", msgType);
            metadata.put("frame", body);

            handleMessage(fromUser, chatId, content, mediaPaths, metadata);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleEnterChat(Object frame) {
        try {
            if (config.getWelcomeMessage() == null || config.getWelcomeMessage().isBlank()) {
                return;
            }

            Map<String, Object> body = extractBody(frame);
            String fromUser = stringValue(body.get("from_userid"));
            if (fromUser.isBlank()) return;

            chatFrames.put(fromUser, frame);

            if (client != null) {
                client.sendText(fromUser, config.getWelcomeMessage(), frame);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void trimProcessed() {
        while (processedMessageIds.size() > 1000) {
            String first = processedMessageIds.keySet().iterator().next();
            processedMessageIds.remove(first);
        }
    }

    private String saveWecomMedia(Map<String, Object> body, String fileName, String msgType) {
        try {
            Path mediaDir = Path.of(System.getProperty("user.home"), ".nanobot", "media", "wecom");
            Files.createDirectories(mediaDir);

            Path out = mediaDir.resolve(sanitizeFilename(fileName));

            // TODO: 这里后续可接 SDK 下载媒体接口
            // 当前先把 body 中可能的 base64 / url 留作扩展点
            Object data = body.get("data");
            if (data instanceof String s && !s.isBlank()) {
                Files.writeString(out, s, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return out.toString();
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessWecomMediaType(String filename) {
        String ext = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(ext)) return "image";
        if (Set.of("mp4", "avi", "mov").contains(ext)) return "video";
        if (Set.of("amr", "mp3", "wav", "ogg").contains(ext)) return "voice";
        return "file";
    }

    private static String msgTypeDisplay(String msgType) {
        return switch (msgType) {
            case "image" -> "[image]";
            case "voice" -> "[voice]";
            case "file" -> "[file]";
            case "mixed" -> "[mixed content]";
            default -> "[message]";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractBody(Object frame) {
        if (frame instanceof Map<?, ?> map) {
            if (map.get("body") instanceof Map<?, ?> body) {
                return (Map<String, Object>) body;
            }
            return (Map<String, Object>) map;
        }
        return new HashMap<>();
    }

    private static String sanitizeFilename(String name) {
        return Path.of(name == null ? "file.bin" : name).getFileName().toString().replaceAll("[^\\w.\\-（）【】()\\[\\]\\u4e00-\\u9fff]+", "_");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}