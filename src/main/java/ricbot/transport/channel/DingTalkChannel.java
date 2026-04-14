package ricbot.transport.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉渠道实现。
 */
public class DingTalkChannel extends BaseChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DingTalkConfig config;
    private final HttpClient httpClient;
    private String accessToken;
    private long accessTokenExpiry;

    public DingTalkChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "dingtalk";
        this.displayName = "DingTalk";
        this.config = convertConfig(config);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    private DingTalkConfig convertConfig(Object raw) {
        if (raw instanceof DingTalkConfig c) return c;
        if (raw instanceof Map<?, ?> m) {
            DingTalkConfig c = new DingTalkConfig();
            c.setEnabled(Boolean.TRUE.equals(m.get("enabled")));
            c.setAppKey((String) m.get("app_key"));
            c.setAppSecret((String) m.get("app_secret"));
            c.setAllowFrom((List<String>) m.get("allow_from"));
            return c;
        }
        return new DingTalkConfig();
    }

    @Override
    public void start() throws Exception {
        if (config.getAppKey() == null || config.getAppKey().isBlank()) {
            return;
        }
        running = true;
        System.out.println("DingTalk channel started (HTTP only mode)");
    }

    @Override
    public void stop() throws Exception {
        running = false;
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        String token = getAccessToken();
        String chatId = msg.getChatId();
        String content = msg.getContent() != null ? msg.getContent() : "";

        Map<String, Object> body = new HashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of(
                "title", "Ricbot Response",
                "text", content
        ));

        // Use the chat_id or open_conversation_id if provided
        String url = "https://oapi.dingtalk.com/robot/send?access_token=" + token; // Default to robot webhook for simplicity if not enterprise
        // In real enterprise app, it would be /topapi/im/chat/scencegroup/message/send or similar
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("Failed to send DingTalk message: " + response.body());
        }
    }

    private String getAccessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
            return accessToken;
        }

        String url = "https://oapi.dingtalk.com/gettoken?appkey=" + config.getAppKey() + "&appsecret=" + config.getAppSecret();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> map = MAPPER.readValue(response.body(), new TypeReference<>() {});
        
        if ((Integer) map.get("errcode") == 0) {
            accessToken = (String) map.get("access_token");
            accessTokenExpiry = System.currentTimeMillis() + ((Integer) map.get("expires_in") - 60) * 1000L;
            return accessToken;
        }
        throw new RuntimeException("Failed to get DingTalk access token: " + response.body());
    }

    public static class DingTalkConfig {
        private boolean enabled = false;
        private String appKey = "";
        private String appSecret = "";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }
}