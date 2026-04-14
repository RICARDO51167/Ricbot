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
import java.util.regex.Pattern;

/**
 * Matrix (Element) 渠道实现。
 *
 * 主要目标：
 * 1. long-polling sync 接收入站消息
 * 2. 支持发送文本 / HTML / 媒体
 * 3. 支持 typing 指示
 * 4. 支持用 edit 事件实现流式输出
 *
 * 说明：
 * 这里基于 Matrix HTTP API 做通用实现。
 * E2EE 媒体解密等更深的 nio/crypto 能力，在 Java 里通常要接专门 SDK。
 */
public class MatrixChannel extends BaseChannel {

    private static final long TYPING_NOTICE_TIMEOUT_MS = 30_000;
    private static final long TYPING_KEEPALIVE_INTERVAL_MS = 20_000;
    private static final String MATRIX_HTML_FORMAT = "org.matrix.custom.html";
    private static final String ATTACH_MARKER = "[attachment: %s]";
    private static final String ATTACH_TOO_LARGE = "[attachment: %s - too large]";
    private static final String ATTACH_FAILED = "[attachment: %s - download failed]";
    private static final long STREAM_EDIT_INTERVAL_MS = 2000;

    private static final Pattern SIMPLE_MD_RE = Pattern.compile("\\*\\*.+?\\*\\*|__.+?__|~~.+?~~", Pattern.DOTALL);
    private static final Pattern LIST_RE = Pattern.compile("^[\\s]*[-*+]\\s+", Pattern.MULTILINE);
    private static final Pattern OLIST_RE = Pattern.compile("^[\\s]*\\d+\\.\\s+", Pattern.MULTILINE);

    public static class MatrixConfig {
        private boolean enabled = false;
        private String homeserver = "https://matrix.org";
        private String userId = "";
        private String password = "";
        private String accessToken = "";
        private String deviceId = "";
        private boolean e2eeEnabled = true;
        private int syncStopGraceSeconds = 2;
        private long maxMediaBytes = 20L * 1024 * 1024;
        private List<String> allowFrom = new ArrayList<>();
        private String groupPolicy = "open";
        private List<String> groupAllowFrom = new ArrayList<>();
        private boolean allowRoomMentions = false;
        private boolean streaming = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHomeserver() { return homeserver; }
        public void setHomeserver(String homeserver) { this.homeserver = homeserver; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        public boolean isE2eeEnabled() { return e2eeEnabled; }
        public void setE2eeEnabled(boolean e2eeEnabled) { this.e2eeEnabled = e2eeEnabled; }
        public int getSyncStopGraceSeconds() { return syncStopGraceSeconds; }
        public void setSyncStopGraceSeconds(int syncStopGraceSeconds) { this.syncStopGraceSeconds = syncStopGraceSeconds; }
        public long getMaxMediaBytes() { return maxMediaBytes; }
        public void setMaxMediaBytes(long maxMediaBytes) { this.maxMediaBytes = maxMediaBytes; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }
        public List<String> getGroupAllowFrom() { return groupAllowFrom; }
        public void setGroupAllowFrom(List<String> groupAllowFrom) { this.groupAllowFrom = groupAllowFrom; }
        public boolean isAllowRoomMentions() { return allowRoomMentions; }
        public void setAllowRoomMentions(boolean allowRoomMentions) { this.allowRoomMentions = allowRoomMentions; }
        public boolean isStreaming() { return streaming; }
        public void setStreaming(boolean streaming) { this.streaming = streaming; }
    }

    public static class StreamBuf {
        String text = "";
        String eventId;
        long lastEditMillis = 0;
    }

    private final MatrixConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private final Path storePath;
    private final Path sessionPath;
    private final Path mediaDir;

    private final Map<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    private final Map<String, StreamBuf> streamBufs = new ConcurrentHashMap<>();

    private volatile String syncToken;
    private volatile boolean loggedIn = false;
    private ScheduledFuture<?> syncFuture;

    public MatrixChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "matrix";
        this.displayName = "Matrix";
        this.config = (config instanceof MatrixConfig c) ? c : new MatrixConfig();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        this.storePath = Path.of(System.getProperty("user.home"), ".nanobot", "matrix-store");
        this.sessionPath = storePath.resolve("session.json");
        this.mediaDir = Path.of(System.getProperty("user.home"), ".nanobot", "media", "matrix");
    }

    @Override
    public void start() throws Exception {
        running = true;

        Files.createDirectories(storePath);
        Files.createDirectories(mediaDir);

        loadSession();

        if (config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
            loggedIn = true;
        } else {
            login();
        }

        syncFuture = scheduler.scheduleWithFixedDelay(
                this::syncLoopOnce,
                0,
                1,
                TimeUnit.SECONDS
        );
    }

    @Override
    public void stop() throws Exception {
        running = false;

        if (syncFuture != null) {
            syncFuture.cancel(true);
        }
        for (ScheduledFuture<?> task : typingTasks.values()) {
            task.cancel(true);
        }
        typingTasks.clear();

        scheduler.shutdownNow();
    }

    /**
     * 登录 Matrix。
     */
    private void login() throws Exception {
        if ((config.getAccessToken() == null || config.getAccessToken().isBlank())
                && (config.getPassword() == null || config.getPassword().isBlank())) {
            throw new IllegalStateException("Matrix requires access_token or password");
        }

        if (config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
            loggedIn = true;
            saveSession();
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "m.login.password");
        body.put("user", config.getUserId());
        body.put("password", config.getPassword());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getHomeserver() + "/_matrix/client/v3/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Matrix login failed: " + response.body());
        }

        Map<String, Object> json = mapper.readValue(response.body(), new TypeReference<>() {});
        config.setAccessToken(String.valueOf(json.getOrDefault("access_token", "")));
        config.setDeviceId(String.valueOf(json.getOrDefault("device_id", "")));
        loggedIn = true;
        saveSession();
    }

    private void loadSession() {
        if (!Files.exists(sessionPath)) {
            return;
        }
        try {
            Map<String, Object> saved = mapper.readValue(sessionPath.toFile(), new TypeReference<>() {});
            Object accessToken = saved.get("access_token");
            Object deviceId = saved.get("device_id");
            Object syncTokenSaved = saved.get("sync_token");

            if (accessToken instanceof String s && !s.isBlank()) {
                config.setAccessToken(s);
            }
            if (deviceId instanceof String s && !s.isBlank()) {
                config.setDeviceId(s);
            }
            if (syncTokenSaved instanceof String s && !s.isBlank()) {
                syncToken = s;
            }
        } catch (Exception ignored) {
        }
    }

    private void saveSession() {
        try {
            Files.createDirectories(sessionPath.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("access_token", config.getAccessToken());
            data.put("device_id", config.getDeviceId());
            data.put("sync_token", syncToken);
            mapper.writerWithDefaultPrettyPrinter().writeValue(sessionPath.toFile(), data);
        } catch (IOException ignored) {
        }
    }

    /**
     * 一次 sync。
     */
    private void syncLoopOnce() {
        if (!running || !loggedIn) {
            return;
        }

        try {
            String url = config.getHomeserver() + "/_matrix/client/v3/sync?timeout=30000";
            if (syncToken != null && !syncToken.isBlank()) {
                url += "&since=" + syncToken;
            }

            HttpRequest request = authorizedRequest(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return;
            }

            Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() {});
            Object nextBatch = body.get("next_batch");
            if (nextBatch instanceof String s && !s.isBlank()) {
                syncToken = s;
                saveSession();
            }

            handleSyncResponse(body);

        } catch (Exception ignored) {
        }
    }

    /**
     * 处理 sync 响应中的房间事件。
     */
    @SuppressWarnings("unchecked")
    private void handleSyncResponse(Map<String, Object> body) throws Exception {
        Map<String, Object> rooms = safeMap(body.get("rooms"));
        Map<String, Object> invite = safeMap(rooms.get("invite"));
        Map<String, Object> join = safeMap(rooms.get("join"));

        // 自动接受邀请
        for (String roomId : invite.keySet()) {
            joinRoom(roomId);
        }

        for (Map.Entry<String, Object> roomEntry : join.entrySet()) {
            String roomId = roomEntry.getKey();
            Map<String, Object> roomData = safeMap(roomEntry.getValue());

            Map<String, Object> timeline = safeMap(roomData.get("timeline"));
            Object eventsObj = timeline.get("events");
            if (!(eventsObj instanceof List<?> events)) {
                continue;
            }

            for (Object eventObj : events) {
                Map<String, Object> event = safeMap(eventObj);
                String type = stringValue(event.get("type"));

                if ("m.room.message".equals(type)) {
                    handleRoomMessage(roomId, event);
                }
            }
        }
    }

    private void joinRoom(String roomId) {
        try {
            HttpRequest request = authorizedRequest(
                    URI.create(config.getHomeserver() + "/_matrix/client/v3/rooms/" + roomId + "/join")
            ).POST(HttpRequest.BodyPublishers.noBody()).build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    /**
     * 处理普通房间消息事件。
     */
    private void handleRoomMessage(String roomId, Map<String, Object> event) throws Exception {
        String sender = stringValue(event.get("sender"));
        if (!isAllowed(sender)) {
            return;
        }

        Map<String, Object> content = safeMap(event.get("content"));
        String msgtype = stringValue(content.get("msgtype"));
        String body = stringValue(content.get("body"));
        String eventId = stringValue(event.get("event_id"));

        if (body.isBlank() && !"m.file".equals(msgtype) && !"m.image".equals(msgtype)
                && !"m.audio".equals(msgtype) && !"m.video".equals(msgtype)) {
            return;
        }

        if (isGroupRoom(roomId) && !shouldRespondInGroup(body, roomId)) {
            return;
        }

        List<String> mediaPaths = new ArrayList<>();
        if (Set.of("m.image", "m.file", "m.audio", "m.video").contains(msgtype)) {
            String markerName = !body.isBlank() ? body : "attachment";
            String marker = switch (msgtype) {
                case "m.image" -> ATTACH_MARKER.formatted(markerName);
                default -> ATTACH_MARKER.formatted(markerName);
            };
            body = body.isBlank() ? marker : body + "\n" + marker;

            String url = stringValue(content.get("url"));
            if (!url.isBlank()) {
                String local = downloadMatrixMedia(url, markerName);
                if (local != null) {
                    mediaPaths.add(local);
                }
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("event_id", eventId);
        metadata.put("room_id", roomId);

        handleMessage(sender, roomId, body, mediaPaths, metadata);
    }

    private boolean isGroupRoom(String roomId) {
        // Matrix 里大多数 room 都可视为 group；DM room 判断需要 account data / tags / member count。
        return true;
    }

    private boolean shouldRespondInGroup(String content, String roomId) {
        if ("open".equalsIgnoreCase(config.getGroupPolicy())) {
            return true;
        }
        if ("allowlist".equalsIgnoreCase(config.getGroupPolicy())) {
            return config.getGroupAllowFrom() != null && config.getGroupAllowFrom().contains(roomId);
        }
        if ("mention".equalsIgnoreCase(config.getGroupPolicy())) {
            if (config.getUserId() == null || config.getUserId().isBlank()) {
                return false;
            }
            return content.contains(config.getUserId()) || (config.isAllowRoomMentions() && content.contains("@room"));
        }
        return true;
    }

    /**
     * 下载 Matrix 媒体。
     */
    private String downloadMatrixMedia(String mxcUrl, String filenameHint) {
        try {
            // mxc://server/mediaId -> /_matrix/media/v3/download/server/mediaId
            if (!mxcUrl.startsWith("mxc://")) {
                return null;
            }

            String stripped = mxcUrl.substring("mxc://".length());
            int idx = stripped.indexOf('/');
            if (idx <= 0) {
                return null;
            }

            String server = stripped.substring(0, idx);
            String mediaId = stripped.substring(idx + 1);

            String downloadUrl = config.getHomeserver()
                    + "/_matrix/media/v3/download/"
                    + server + "/" + mediaId;

            HttpRequest request = authorizedRequest(URI.create(downloadUrl)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 400 || response.body() == null) {
                return null;
            }
            if (response.body().length > config.getMaxMediaBytes()) {
                return null;
            }

            String safeName = sanitizeFilename(filenameHint.isBlank() ? "attachment" : filenameHint);
            Path file = mediaDir.resolve(System.currentTimeMillis() + "_" + safeName);
            Files.write(file, response.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return file.toString();

        } catch (Exception e) {
            return null;
        }
    }

    public void send(OutboundMessage msg) throws Exception {
        String roomId = msg.getChatId();

        // 先发媒体
        if (msg.getMedia() != null) {
            for (String mediaPath : msg.getMedia()) {
                sendMedia(roomId, mediaPath);
            }
        }

        // 再发文本
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            sendText(roomId, msg.getContent(), null, null);
        }
    }

    @Override
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        StreamBuf buf = streamBufs.computeIfAbsent(chatId, k -> new StreamBuf());
        buf.text = buf.text + (delta != null ? delta : "");

        boolean streamEnd = metadata != null && Boolean.TRUE.equals(metadata.get("_stream_end"));
        long now = System.currentTimeMillis();

        if (streamEnd) {
            finalizeStream(chatId, buf);
            return;
        }

        if (buf.eventId == null) {
            buf.eventId = sendText(chatId, buf.text, null, null);
            buf.lastEditMillis = now;
            startTyping(chatId);
            return;
        }

        if ((now - buf.lastEditMillis) < STREAM_EDIT_INTERVAL_MS) {
            return;
        }

        editMessageText(chatId, buf.eventId, buf.text, null);
        buf.lastEditMillis = now;
    }

    private void finalizeStream(String chatId, StreamBuf buf) throws Exception {
        if (buf.eventId == null) {
            sendText(chatId, buf.text, null, null);
        } else {
            editMessageText(chatId, buf.eventId, buf.text, null);
        }
        streamBufs.remove(chatId);
        stopTyping(chatId);
    }

    /**
     * 发送文本消息。
     *
     * 返回 event_id。
     */
    private String sendText(String roomId, String text, String replyToEventId, Map<String, Object> threadRelatesTo) throws Exception {
        Map<String, Object> content = buildMatrixTextContent(text, null, threadRelatesTo);
        if (replyToEventId != null && !replyToEventId.isBlank()) {
            content.put("m.relates_to", Map.of(
                    "m.in_reply_to", Map.of("event_id", replyToEventId)
            ));
        }

        String txnId = UUID.randomUUID().toString();
        HttpRequest request = authorizedRequest(
                URI.create(config.getHomeserver() + "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + txnId)
        ).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(content))).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Matrix send text failed: " + response.body());
        }

        Map<String, Object> json = mapper.readValue(response.body(), new TypeReference<>() {});
        return stringValue(json.get("event_id"));
    }

    /**
     * 编辑已有消息。
     */
    private void editMessageText(String roomId, String eventId, String text, Map<String, Object> threadRelatesTo) throws Exception {
        Map<String, Object> content = buildMatrixTextContent(text, eventId, threadRelatesTo);

        String txnId = UUID.randomUUID().toString();
        HttpRequest request = authorizedRequest(
                URI.create(config.getHomeserver() + "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + txnId)
        ).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(content))).build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * 发送媒体。
     */
    private void sendMedia(String roomId, String mediaPath) throws Exception {
        Path path = Path.of(mediaPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return;
        }

        byte[] data = Files.readAllBytes(path);
        String mxcUrl = uploadMedia(path.getFileName().toString(), data);

        String msgtype = guessMsgType(path);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("body", path.getFileName().toString());
        content.put("msgtype", msgtype);
        content.put("url", mxcUrl);

        String txnId = UUID.randomUUID().toString();
        HttpRequest request = authorizedRequest(
                URI.create(config.getHomeserver() + "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + txnId)
        ).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(content))).build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private String uploadMedia(String filename, byte[] data) throws Exception {
        HttpRequest request = authorizedRequest(
                URI.create(config.getHomeserver() + "/_matrix/media/v3/upload?filename=" + filename)
        )
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Matrix upload failed: " + response.body());
        }

        Map<String, Object> json = mapper.readValue(response.body(), new TypeReference<>() {});
        return stringValue(json.get("content_uri"));
    }

    /**
     * typing 指示。
     */
    private void startTyping(String roomId) {
        stopTyping(roomId);

        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        sendTyping(roomId, true);
                    } catch (Exception ignored) {
                    }
                },
                0,
                TYPING_KEEPALIVE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        typingTasks.put(roomId, task);
    }

    private void stopTyping(String roomId) {
        ScheduledFuture<?> task = typingTasks.remove(roomId);
        if (task != null) {
            task.cancel(true);
        }
        try {
            sendTyping(roomId, false);
        } catch (Exception ignored) {
        }
    }

    private void sendTyping(String roomId, boolean typing) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typing", typing);
        if (typing) {
            body.put("timeout", TYPING_NOTICE_TIMEOUT_MS);
        }

        HttpRequest request = authorizedRequest(
                URI.create(config.getHomeserver() + "/_matrix/client/v3/rooms/" + roomId + "/typing/" + encodeUserId(config.getUserId()))
        ).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private String encodeUserId(String userId) {
        return userId.replace(":", "%3A");
    }

    private HttpRequest.Builder authorizedRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + config.getAccessToken())
                .header("Content-Type", "application/json");
    }

    private String guessMsgType(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp")) {
            return "m.image";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) {
            return "m.audio";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")) {
            return "m.video";
        }
        return "m.file";
    }

    private static String sanitizeFilename(String name) {
        String safe = name == null ? "attachment" : name.replaceAll("[^\\w.\\-]+", "_");
        return safe.isBlank() ? "attachment" : safe;
    }

    private static Map<String, Object> buildMatrixTextContent(
            String text,
            String eventId,
            Map<String, Object> threadRelatesTo
    ) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("msgtype", "m.text");
        content.put("body", text);
        content.put("m.mentions", new LinkedHashMap<>());

        String html = renderMarkdownHtml(text);
        if (html != null) {
            content.put("format", MATRIX_HTML_FORMAT);
            content.put("formatted_body", html);
        }

        if (eventId != null && !eventId.isBlank()) {
            Map<String, Object> newContent = new LinkedHashMap<>();
            newContent.put("body", text);
            newContent.put("msgtype", "m.text");
            if (threadRelatesTo != null) {
                newContent.put("m.relates_to", threadRelatesTo);
            }

            content.put("m.new_content", newContent);
            content.put("m.relates_to", Map.of(
                    "rel_type", "m.replace",
                    "event_id", eventId
            ));
        } else if (threadRelatesTo != null) {
            content.put("m.relates_to", threadRelatesTo);
        }

        return content;
    }

    private static String renderMarkdownHtml(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean formatted = SIMPLE_MD_RE.matcher(text).find()
                || LIST_RE.matcher(text).find()
                || OLIST_RE.matcher(text).find();

        if (!formatted) {
            return null;
        }

        String html = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>");

        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("~~(.+?)~~", "<del>$1</del>");

        return html;
    }

    private static Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return new LinkedHashMap<>();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}