package ricbot.transport.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 个人微信渠道实现。
 *
 * 对应 Python: weixin.py
 *
 * 主要职责：
 * 1. 通过 ilinkai.weixin.qq.com 的 HTTP long-poll 接口收发消息
 * 2. token / account 状态持久化
 * 3. QR 登录后持久化 token
 * 4. context_token / typing_ticket 缓存
 * 5. 支持文本、图片、语音、文件、视频
 * 6. 支持 typing keepalive
 */
public class WeixinChannel extends BaseChannel {

    // ------------------------------------------------------------------
    // Protocol constants
    // ------------------------------------------------------------------

    private static final int ITEM_TEXT = 1;
    private static final int ITEM_IMAGE = 2;
    private static final int ITEM_VOICE = 3;
    private static final int ITEM_FILE = 4;
    private static final int ITEM_VIDEO = 5;

    private static final int MESSAGE_TYPE_USER = 1;
    private static final int MESSAGE_TYPE_BOT = 2;
    private static final int MESSAGE_STATE_FINISH = 2;

    private static final int WEIXIN_MAX_MESSAGE_LEN = 4000;
    private static final String WEIXIN_CHANNEL_VERSION = "2.1.1";
    private static final String ILINK_APP_ID = "bot";

    private static final int ERRCODE_SESSION_EXPIRED = -14;
    private static final long SESSION_PAUSE_DURATION_MS = 60L * 60 * 1000;
    private static final int DEFAULT_LONG_POLL_TIMEOUT_S = 35;

    private static final int UPLOAD_MEDIA_IMAGE = 1;
    private static final int UPLOAD_MEDIA_VIDEO = 2;
    private static final int UPLOAD_MEDIA_FILE = 3;
    private static final int UPLOAD_MEDIA_VOICE = 4;

    public static class WeixinConfig {
        private boolean enabled = false;
        private List<String> allowFrom = new ArrayList<>();
        private String baseUrl = "https://ilinkai.weixin.qq.com";
        private String cdnBaseUrl = "https://novac2c.cdn.weixin.qq.com/c2c";
        private String routeTag = "";
        private String token = "";
        private String stateDir = "";
        private int pollTimeout = DEFAULT_LONG_POLL_TIMEOUT_S;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getCdnBaseUrl() { return cdnBaseUrl; }
        public void setCdnBaseUrl(String cdnBaseUrl) { this.cdnBaseUrl = cdnBaseUrl; }
        public String getRouteTag() { return routeTag; }
        public void setRouteTag(String routeTag) { this.routeTag = routeTag; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getStateDir() { return stateDir; }
        public void setStateDir(String stateDir) { this.stateDir = stateDir; }
        public int getPollTimeout() { return pollTimeout; }
        public void setPollTimeout(int pollTimeout) { this.pollTimeout = pollTimeout; }
    }

    private final WeixinConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private String getUpdatesBuf = "";
    private final Map<String, String> contextTokens = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Boolean> processedIds = new LinkedHashMap<>();
    private final Map<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> typingTickets = new ConcurrentHashMap<>();

    private Path stateDir;
    private String token = "";
    private ScheduledFuture<?> pollTask;
    private long sessionPauseUntilMillis = 0L;

    public WeixinChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "weixin";
        this.displayName = "WeChat";
        this.config = (config instanceof WeixinConfig c) ? c : new WeixinConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void start() throws Exception {
        running = true;
        loadState();

        if (token.isBlank()) {
            token = config.getToken() != null ? config.getToken() : "";
        }

        pollTask = scheduler.scheduleWithFixedDelay(this::pollOnce, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (pollTask != null) {
            pollTask.cancel(true);
        }
        for (ScheduledFuture<?> task : typingTasks.values()) {
            task.cancel(true);
        }
        typingTasks.clear();
        saveState();
        scheduler.shutdownNow();
    }

    public void send(OutboundMessage msg) throws Exception {
        String toUserId = msg.getChatId();

        if (msg.getMedia() != null) {
            for (String mediaPath : msg.getMedia()) {
                sendMediaMessage(toUserId, mediaPath);
            }
        }

        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            for (String part : splitMessage(msg.getContent(), WEIXIN_MAX_MESSAGE_LEN)) {
                sendTextMessage(toUserId, part);
            }
        }
    }

    // ------------------------------------------------------------------
    // State persistence
    // ------------------------------------------------------------------

    private Path getStateDir() throws IOException {
        if (stateDir != null) return stateDir;

        if (config.getStateDir() != null && !config.getStateDir().isBlank()) {
            stateDir = Path.of(config.getStateDir()).toAbsolutePath().normalize();
        } else {
            stateDir = Path.of(System.getProperty("user.home"), ".nanobot", "weixin");
        }
        Files.createDirectories(stateDir);
        return stateDir;
    }

    private void loadState() {
        try {
            Path file = getStateDir().resolve("account.json");
            if (!Files.exists(file)) return;

            Map<String, Object> data = mapper.readValue(file.toFile(), new TypeReference<>() {});
            token = stringValue(data.get("token"));
            getUpdatesBuf = stringValue(data.get("get_updates_buf"));

            Object contextObj = data.get("context_tokens");
            if (contextObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    contextTokens.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }

            Object ticketsObj = data.get("typing_tickets");
            if (ticketsObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> inner) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) inner;
                        typingTickets.put(String.valueOf(e.getKey()), cast);
                    }
                }
            }

            String baseUrl = stringValue(data.get("base_url"));
            if (!baseUrl.isBlank()) {
                config.setBaseUrl(baseUrl);
            }

        } catch (Exception ignored) {
        }
    }

    private void saveState() {
        try {
            Path file = getStateDir().resolve("account.json");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", token);
            data.put("get_updates_buf", getUpdatesBuf);
            data.put("context_tokens", contextTokens);
            data.put("typing_tickets", typingTickets);
            data.put("base_url", config.getBaseUrl());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Polling
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void pollOnce() {
        if (!running) return;
        if (System.currentTimeMillis() < sessionPauseUntilMillis) return;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("buf", getUpdatesBuf);
            body.put("timeout", config.getPollTimeout());

            Map<String, Object> json = apiPost("getupdates", body, true);

            Number errCode = number(json.get("errcode"));
            if (errCode != null && errCode.intValue() == ERRCODE_SESSION_EXPIRED) {
                sessionPauseUntilMillis = System.currentTimeMillis() + SESSION_PAUSE_DURATION_MS;
                return;
            }

            String nextBuf = stringValue(json.get("buf"));
            if (!nextBuf.isBlank()) {
                getUpdatesBuf = nextBuf;
                saveState();
            }

            Object messagesObj = json.get("messages");
            if (!(messagesObj instanceof List<?> messages)) {
                return;
            }

            for (Object mObj : messages) {
                if (!(mObj instanceof Map<?, ?> raw)) continue;
                Map<String, Object> msg = (Map<String, Object>) raw;
                handleInboundMessage(msg);
            }

        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private void handleInboundMessage(Map<String, Object> msg) {
        String messageId = stringValue(msg.get("message_id"));
        if (!messageId.isBlank()) {
            if (processedIds.containsKey(messageId)) {
                return;
            }
            processedIds.put(messageId, Boolean.TRUE);
            trimProcessed();
        }

        Number msgTypeNum = number(msg.get("message_type"));
        if (msgTypeNum == null || msgTypeNum.intValue() != MESSAGE_TYPE_USER) {
            return;
        }

        String fromUserId = stringValue(msg.get("from_user_id"));
        if (fromUserId.isBlank()) return;

        List<String> mediaPaths = new ArrayList<>();
        String content = "";

        Object itemsObj = msg.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object itemObj : items) {
                if (!(itemObj instanceof Map<?, ?> raw)) continue;
                Map<String, Object> item = (Map<String, Object>) raw;

                Number itemType = number(item.get("item_type"));
                if (itemType == null) continue;

                switch (itemType.intValue()) {
                    case ITEM_TEXT -> {
                        content = content + stringValue(item.get("content"));
                    }
                    case ITEM_IMAGE, ITEM_FILE, ITEM_VIDEO, ITEM_VOICE -> {
                        String local = downloadMediaItem(item, itemType.intValue());
                        if (local != null) {
                            mediaPaths.add(local);
                        }
                        if (content.isBlank()) {
                            content = switch (itemType.intValue()) {
                                case ITEM_IMAGE -> "[image]";
                                case ITEM_FILE -> "[file]";
                                case ITEM_VIDEO -> "[video]";
                                case ITEM_VOICE -> "[voice]";
                                default -> "[media]";
                            };
                        }
                    }
                }
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("weixin_message_id", messageId);

        try {
            handleMessage(fromUserId, fromUserId, content, mediaPaths, metadata);
        } catch (Exception ignored) {
        }
    }

    private void trimProcessed() {
        while (processedIds.size() > 1000) {
            String first = processedIds.keySet().iterator().next();
            processedIds.remove(first);
        }
    }

    // ------------------------------------------------------------------
    // Send
    // ------------------------------------------------------------------

    private void sendTextMessage(String toUserId, String content) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("item_type", ITEM_TEXT);
        item.put("content", content);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to_user_id", toUserId);
        body.put("message_type", MESSAGE_TYPE_BOT);
        body.put("state", MESSAGE_STATE_FINISH);
        body.put("items", List.of(item));

        apiPost("sendmessage", body, true);
    }

    private void sendMediaMessage(String toUserId, String mediaPath) throws Exception {
        Path path = Path.of(mediaPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return;

        int uploadType = guessUploadMediaType(path.getFileName().toString());
        String uploadUrl = getUploadUrl(uploadType);

        // 这里简化成直接把文件作为 bytes 发到 upload_url
        byte[] data = Files.readAllBytes(path);
        String mediaKey = uploadMedia(uploadUrl, data);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("item_type", switch (uploadType) {
            case UPLOAD_MEDIA_IMAGE -> ITEM_IMAGE;
            case UPLOAD_MEDIA_VIDEO -> ITEM_VIDEO;
            case UPLOAD_MEDIA_VOICE -> ITEM_VOICE;
            default -> ITEM_FILE;
        });
        item.put("media_key", mediaKey);
        item.put("file_name", path.getFileName().toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to_user_id", toUserId);
        body.put("message_type", MESSAGE_TYPE_BOT);
        body.put("state", MESSAGE_STATE_FINISH);
        body.put("items", List.of(item));

        apiPost("sendmessage", body, true);
    }

    // ------------------------------------------------------------------
    // Typing
    // ------------------------------------------------------------------

    public void startTyping(String toUserId) {
        stopTyping(toUserId);

        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                sendTypingStatus(toUserId, 1);
            } catch (Exception ignored) {
            }
        }, 0, 5, TimeUnit.SECONDS);

        typingTasks.put(toUserId, task);
    }

    public void stopTyping(String toUserId) {
        ScheduledFuture<?> task = typingTasks.remove(toUserId);
        if (task != null) {
            task.cancel(true);
        }
        try {
            sendTypingStatus(toUserId, 2);
        } catch (Exception ignored) {
        }
    }

    private void sendTypingStatus(String toUserId, int status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to_user_id", toUserId);
        body.put("typing_status", status);
        apiPost("typing", body, true);
    }

    // ------------------------------------------------------------------
    // Media download/upload
    // ------------------------------------------------------------------

    private String downloadMediaItem(Map<String, Object> item, int itemType) {
        try {
            String mediaUrl = stringValue(item.get("full_url"));
            String encryptQuery = stringValue(item.get("encrypt_query_param"));

            if (mediaUrl.isBlank() && encryptQuery.isBlank()) {
                return null;
            }

            String url = !mediaUrl.isBlank()
                    ? mediaUrl
                    : config.getCdnBaseUrl() + "?" + encryptQuery;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) return null;

            Path mediaDir = Path.of(System.getProperty("user.home"), ".nanobot", "media", "weixin");
            Files.createDirectories(mediaDir);

            String suffix = switch (itemType) {
                case ITEM_IMAGE -> ".jpg";
                case ITEM_VIDEO -> ".mp4";
                case ITEM_VOICE -> ".mp3";
                default -> ".bin";
            };

            Path out = mediaDir.resolve(System.currentTimeMillis() + suffix);
            Files.write(out, response.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return out.toString();

        } catch (Exception e) {
            return null;
        }
    }

    private String getUploadUrl(int uploadMediaType) throws Exception {
        Map<String, Object> body = Map.of("media_type", uploadMediaType);
        Map<String, Object> resp = apiPost("getuploadurl", body, true);
        return stringValue(resp.get("upload_url"));
    }

    private String uploadMedia(String uploadUrl, byte[] data) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> json = mapper.readValue(response.body(), new TypeReference<>() {});
        return stringValue(json.get("media_key"));
    }

    private int guessUploadMediaType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return UPLOAD_MEDIA_IMAGE;
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".flv")) {
            return UPLOAD_MEDIA_VIDEO;
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".amr")
                || lower.endsWith(".silk") || lower.endsWith(".ogg") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".flac")) {
            return UPLOAD_MEDIA_VOICE;
        }
        return UPLOAD_MEDIA_FILE;
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private Map<String, Object> apiGet(String endpoint, Map<String, Object> params, boolean auth) throws Exception {
        StringBuilder url = new StringBuilder(config.getBaseUrl()).append("/").append(endpoint);
        if (params != null && !params.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!first) url.append("&");
                first = false;
                url.append(e.getKey()).append("=").append(String.valueOf(e.getValue()));
            }
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .GET()
                .timeout(Duration.ofSeconds(120));
        for (Map.Entry<String, String> h : makeHeaders(auth).entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String, Object> apiPost(String endpoint, Map<String, Object> body, boolean auth) throws Exception {
        String url = config.getBaseUrl() + "/" + endpoint;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(120));

        for (Map.Entry<String, String> h : makeHeaders(auth).entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String, String> makeHeaders(boolean auth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-WECHAT-UIN", randomWechatUin());
        headers.put("Content-Type", "application/json");
        headers.put("AuthorizationType", "ilink_bot_token");
        headers.put("iLink-App-Id", ILINK_APP_ID);
        headers.put("iLink-App-ClientVersion", String.valueOf(buildClientVersion(WEIXIN_CHANNEL_VERSION)));

        if (auth && token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        if (config.getRouteTag() != null && !config.getRouteTag().isBlank()) {
            headers.put("SKRouteTag", config.getRouteTag().trim());
        }
        return headers;
    }

    private static String randomWechatUin() {
        long val = Math.abs(new Random().nextInt()) & 0xffffffffL;
        return Base64.getEncoder().encodeToString(String.valueOf(val).getBytes());
    }

    private static int buildClientVersion(String version) {
        String[] parts = version.split("\\.");
        int major = parts.length > 0 ? parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? parseInt(parts[2]) : 0;
        return ((major & 0xFF) << 16) | ((minor & 0xFF) << 8) | (patch & 0xFF);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static List<String> splitMessage(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String remain = text;
        while (remain.length() > maxLen) {
            out.add(remain.substring(0, maxLen));
            remain = remain.substring(maxLen);
        }
        if (!remain.isEmpty()) out.add(remain);
        return out;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}