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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 飞书 / Lark 渠道实现。
 */
public class FeishuChannel extends BaseChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long STREAM_EDIT_INTERVAL_MS = 500;
    private static final int TEXT_MAX_LEN = 200;
    private static final int POST_MAX_LEN = 2000;

    private static final Pattern COMPLEX_MD_RE = Pattern.compile("```|^#{1,6}\\s+|^\\|.+\\|.*\\n\\s*\\|[-:\\s|]+\\|", Pattern.MULTILINE);
    private static final Pattern SIMPLE_MD_RE = Pattern.compile("\\*\\*.+?\\*\\*|__.+?__|~~.+?~~", Pattern.DOTALL);
    private static final Pattern MD_LINK_RE = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)");
    private static final Pattern LIST_RE = Pattern.compile("^[\\s]*[-*+]\\s+", Pattern.MULTILINE);
    private static final Pattern OLIST_RE = Pattern.compile("^[\\s]*\\d+\\.\\s+", Pattern.MULTILINE);

    private final FeishuConfig config;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);
    private String accessToken;
    private long accessTokenExpiry;

    private final Map<String, FeishuStreamBuf> streamBufs = new ConcurrentHashMap<>();

    public FeishuChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "feishu";
        this.displayName = "飞书";
        this.config = convertConfig(config);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    private FeishuConfig convertConfig(Object raw) {
        if (raw instanceof FeishuConfig c) return c;
        if (raw instanceof Map<?, ?> m) {
            FeishuConfig c = new FeishuConfig();
            c.setEnabled(Boolean.TRUE.equals(m.get("enabled")));
            c.setAppId((String) m.get("app_id"));
            c.setAppSecret((String) m.get("app_secret"));
            c.setAllowFrom((List<String>) m.get("allow_from"));
            return c;
        }
        return new FeishuConfig();
    }

    @Override
    public void start() throws Exception {
        if (config.getAppId() == null || config.getAppId().isBlank()) {
            return;
        }
        running = true;
        System.out.println("飞书渠道已启动（仅 HTTP 模式）");
    }

    @Override
    public void stop() throws Exception {
        running = false;
        streamBufs.clear();
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
        String format = detectMsgFormat(content);

        Map<String, Object> body = new HashMap<>();
        body.put("receive_id", chatId);
        body.put("msg_type", format.equals("interactive") ? "interactive" : (format.equals("post") ? "post" : "text"));

        if (format.equals("interactive")) {
            body.put("content", MAPPER.writeValueAsString(buildCard(content)));
        } else if (format.equals("post")) {
            body.put("content", MAPPER.writeValueAsString(buildPost(content)));
        } else {
            body.put("content", MAPPER.writeValueAsString(Map.of("text", content)));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("发送飞书消息失败：" + response.body());
        }
    }

    private String getAccessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
            return accessToken;
        }

        Map<String, String> body = new HashMap<>();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> map = MAPPER.readValue(response.body(), new TypeReference<>() {});
        
        if ((Integer) map.get("code") == 0) {
            accessToken = (String) map.get("tenant_access_token");
            accessTokenExpiry = System.currentTimeMillis() + ((Integer) map.get("expire") - 60) * 1000L;
            return accessToken;
        }
        throw new RuntimeException("获取飞书访问令牌失败：" + response.body());
    }

    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
    }

    private Map<String, Object> buildCard(String content) {
        Map<String, Object> card = new HashMap<>();
        Map<String, Object> config = new HashMap<>();
        config.put("wide_screen_mode", true);
        card.put("config", config);

        Map<String, Object> header = new HashMap<>();
        header.put("template", "blue");
        header.put("title", Map.of("tag", "plain_text", "content", "Ricbot 回复"));
        card.put("header", header);

        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", content));
        card.put("elements", elements);

        return card;
    }

    private Map<String, Object> buildPost(String content) {
        Map<String, Object> post = new HashMap<>();
        Map<String, Object> zhCn = new HashMap<>();
        zhCn.put("title", "Ricbot 回复");
        
        List<List<Map<String, Object>>> contentList = new ArrayList<>();
        List<Map<String, Object>> line = new ArrayList<>();
        line.add(Map.of("tag", "text", "text", content));
        contentList.add(line);
        
        zhCn.put("content", contentList);
        post.put("zh_cn", zhCn);
        return post;
    }

    public String detectMsgFormat(String content) {
        String stripped = content == null ? "" : content.strip();
        if (COMPLEX_MD_RE.matcher(stripped).find()) return "interactive";
        if (stripped.length() > POST_MAX_LEN) return "interactive";
        if (SIMPLE_MD_RE.matcher(stripped).find()) return "interactive";
        if (LIST_RE.matcher(stripped).find() || OLIST_RE.matcher(stripped).find()) return "interactive";
        if (MD_LINK_RE.matcher(stripped).find()) return "post";
        if (stripped.length() <= TEXT_MAX_LEN) return "text";
        return "post";
    }

    public static class FeishuConfig {
        private boolean enabled = false;
        private String appId = "";
        private String appSecret = "";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }

    public static class FeishuStreamBuf {
        private String text = "";
        private String cardId;
        private long lastEditMillis = 0;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getCardId() { return cardId; }
        public void setCardId(String cardId) { this.cardId = cardId; }
        public long getLastEditMillis() { return lastEditMillis; }
        public void setLastEditMillis(long lastEditMillis) { this.lastEditMillis = lastEditMillis; }
    }
}
