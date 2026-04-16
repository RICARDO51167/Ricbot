package ricbot.integration.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.CircuitBreaker;
import ricbot.infra.common.RetryUtils;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 钉钉渠道实现类。
 */
public class DingTalkChannel extends BaseChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DingTalkConfig config;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);
    private String accessToken;
    private long accessTokenExpiry;

    public DingTalkChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "dingtalk";
        this.displayName = "钉钉";
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
        System.out.println("钉钉渠道已启动（仅 HTTP 模式）");
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
                "title", "Ricbot 回复",
                "text", content
        ));

        String url = "https://oapi.dingtalk.com/robot/send?access_token=" + token;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("发送钉钉消息失败：" + response.body());
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

        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> map = MAPPER.readValue(response.body(), new TypeReference<>() {});
        
        if ((Integer) map.get("errcode") == 0) {
            accessToken = (String) map.get("access_token");
            accessTokenExpiry = System.currentTimeMillis() + ((Integer) map.get("expires_in") - 60) * 1000L;
            return accessToken;
        }
        throw new RuntimeException("获取钉钉 access token 失败：" + response.body());
    }

    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
    }

    /**
     * 钉钉渠道配置内部类。
     */
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
