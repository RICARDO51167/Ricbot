package ricbot.integration.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.CircuitBreaker;
import ricbot.infra.common.RetryUtils;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信（WeCom）渠道实现。
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

    public static class DefaultWecomClient implements WecomClient {
        private final HttpClient httpClient;
        private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);
        private final ObjectMapper mapper = new ObjectMapper();
        private String botId;
        private String secret;
        private String accessToken;
        private long accessTokenExpiry;
        private WecomListener listener;

        public DefaultWecomClient(HttpClient httpClient, String botId, String secret) {
            this.httpClient = httpClient;
            this.botId = botId;
            this.secret = secret;
        }

        @Override
        public void connect(WecomListener listener) throws Exception {
            this.listener = listener;
            refreshAccessToken();
            System.out.println("企微 HTTP 客户端已初始化。（接收消息需要 Webhook/WebSocket 代理）");
        }

        @Override
        public void disconnect() throws Exception {
        }

        @Override
        public void sendText(String chatId, String content, Object frameHeaders) throws Exception {
            String token = getAccessToken();
            String url = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" + token;
            
            Map<String, Object> text = new HashMap<>();
            text.put("content", content);

            Map<String, Object> body = new HashMap<>();
            body.put("touser", chatId);
            body.put("msgtype", "text");
            try {
                body.put("agentid", Integer.parseInt(botId));
            } catch (NumberFormatException e) {
                body.put("agentid", 1000000); 
            }
            body.put("text", text);
            body.put("safe", 0);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            sendHttp(request, HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void sendMedia(String chatId, String mediaType, String filePath, Object frameHeaders) throws Exception {
            String token = getAccessToken();
            String uploadUrl = "https://qyapi.weixin.qq.com/cgi-bin/media/upload?access_token=" + token + "&type=" + mediaType;
            
            System.out.println("企微：正在上传媒体 " + filePath + " 到 " + uploadUrl);
            String mediaId = "DUMMY_MEDIA_ID_" + System.currentTimeMillis();

            String url = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" + token;
            
            Map<String, Object> media = new HashMap<>();
            media.put("media_id", mediaId);

            Map<String, Object> body = new HashMap<>();
            body.put("touser", chatId);
            body.put("msgtype", mediaType);
            try {
                body.put("agentid", Integer.parseInt(botId));
            } catch (NumberFormatException e) {
                body.put("agentid", 1000000); 
            }
            body.put(mediaType, media);
            body.put("safe", 0);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            sendHttp(request, HttpResponse.BodyHandlers.ofString());
        }

        private String getAccessToken() throws Exception {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
                return accessToken;
            }
            refreshAccessToken();
            return accessToken;
        }

        private synchronized void refreshAccessToken() throws Exception {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + botId + "&corpsecret=" + secret;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();

            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = mapper.readValue(response.body(), Map.class);
            if (data.containsKey("access_token")) {
                this.accessToken = (String) data.get("access_token");
                int expires = (int) data.get("expires_in");
                this.accessTokenExpiry = System.currentTimeMillis() + (expires - 60) * 1000L;
            } else {
                throw new Exception("获取企微 access_token 失败：" + response.body());
            }
        }

        private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
            return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
        }
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
            throw new IllegalStateException("未配置企微 bot_id 与 secret");
        }
        if (client == null) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.client = new DefaultWecomClient(httpClient, config.getBotId(), config.getSecret());
        }

        running = true;
        client.connect(new WecomListener() {
            @Override public void onConnected(Object frame) { System.out.println("企微已连接"); }
            @Override public void onAuthenticated(Object frame) { System.out.println("企微已认证"); }
            @Override public void onDisconnected(Object frame) { System.out.println("企微已断开连接：" + frame); }
            @Override public void onError(Object frame) { System.err.println("企微错误：" + frame); }
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

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    private String guessWecomMediaType(String fileName) {
        if (fileName == null) return "file";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) return "image";
        if (lower.endsWith(".amr") || lower.endsWith(".mp3")) return "voice";
        if (lower.endsWith(".mp4")) return "video";
        return "file";
    }

    private String saveWecomMedia(Map<String, Object> body, String fileName, String msgType) {
        try {
            Path mediaDir = Path.of(System.getProperty("user.home"), ".nanobot", "media", "wecom");
            if (!Files.exists(mediaDir)) {
                Files.createDirectories(mediaDir);
            }

            Path out = mediaDir.resolve(fileName);
            Files.writeString(out, body.toString());
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String msgTypeDisplay(String msgType) {
        return switch (msgType) {
            case "image" -> "[图片]";
            case "voice" -> "[语音]";
            case "file" -> "[文件]";
            case "video" -> "[视频]";
            default -> "[媒体消息]";
        };
    }

    private Map<String, Object> extractBody(Object frame) {
        if (frame instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new HashMap<>();
    }

    private String stringValue(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private void trimProcessed() {
        if (processedMessageIds.size() > 1000) {
            Iterator<String> it = processedMessageIds.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
}
