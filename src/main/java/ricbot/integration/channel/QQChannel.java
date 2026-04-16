package ricbot.integration.channel;


import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.CircuitBreaker;
import ricbot.infra.common.RetryUtils;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * QQ 渠道实现。
 *
 * 主要目标：
 * 1. 处理 QQ C2C / Group 入站消息
 * 2. 附件分块下载到本地
 * 3. 把消息转成统一 InboundMessage 发给 MessageBus
 * 4. 出站先上传媒体，再发文本
 *
 * 说明：
 * 这里把 botpy 的网络 / SDK 部分抽成 QQBotClient 接口，
 * 这样你后面换成任何 Java QQ Bot SDK 都能接。
 */
public class QQChannel extends BaseChannel {

    public static final int QQ_FILE_TYPE_IMAGE = 1;
    public static final int QQ_FILE_TYPE_FILE = 4;

    private static final Set<String> IMAGE_EXTS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff", ".ico", ".svg"
    );

    private static final Pattern SAFE_NAME_RE = Pattern.compile("[^\\w.\\-()\\[\\]（）【】\\u4e00-\\u9fff]+");

    public static class QQConfig {
        private boolean enabled = false;
        private String appId = "";
        private String secret = "";
        private List<String> allowFrom = new ArrayList<>();
        private String msgFormat = "plain";
        private String ackMessage = "⏳ 处理中…";
        private String mediaDir = "";
        private int downloadChunkSize = 1024 * 256;
        private long downloadMaxBytes = 1024L * 1024 * 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }

        public String getMsgFormat() { return msgFormat; }
        public void setMsgFormat(String msgFormat) { this.msgFormat = msgFormat; }

        public String getAckMessage() { return ackMessage; }
        public void setAckMessage(String ackMessage) { this.ackMessage = ackMessage; }

        public String getMediaDir() { return mediaDir; }
        public void setMediaDir(String mediaDir) { this.mediaDir = mediaDir; }

        public int getDownloadChunkSize() { return downloadChunkSize; }
        public void setDownloadChunkSize(int downloadChunkSize) { this.downloadChunkSize = downloadChunkSize; }

        public long getDownloadMaxBytes() { return downloadMaxBytes; }
        public void setDownloadMaxBytes(long downloadMaxBytes) { this.downloadMaxBytes = downloadMaxBytes; }
    }

    /**
     * 统一的入站消息对象，屏蔽具体 SDK 差异。
     */
    public interface QQInboundMessage {
        String id();
        String content();
        boolean isGroup();
        String chatId();
        String userId();
        List<QQAttachment> attachments();
    }

    public static class QQAttachment {
        private final String url;
        private final String filename;
        private final String contentType;

        public QQAttachment(String url, String filename, String contentType) {
            this.url = url;
            this.filename = filename;
            this.contentType = contentType;
        }

        public String getUrl() { return url; }
        public String getFilename() { return filename; }
        public String getContentType() { return contentType; }
    }

    public interface QQBotClient {
        void start(String appId, String secret, QQEventListener listener) throws Exception;
        void close() throws Exception;
        Object uploadFile(String chatId, boolean isGroup, int fileType, String base64Data, String fileName) throws Exception;
        void sendText(String chatId, boolean isGroup, String msgId, String content, String format) throws Exception;
        void sendMediaText(String chatId, boolean isGroup, String msgId, Object mediaPayload) throws Exception;
    }

    public interface QQEventListener {
        void onMessage(QQInboundMessage message);
    }

    private final QQConfig config;
    private QQBotClient client;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);

    private final Deque<String> processedIds = new ArrayDeque<>(1000);
    private final Set<String> processedIdSet = ConcurrentHashMap.newKeySet();
    private int msgSeq = 1;

    private final Map<String, String> chatTypeCache = new ConcurrentHashMap<>();
    private final Map<String, String> lastInboundMsgIdByChat = new ConcurrentHashMap<>();
    private final Path mediaRoot;

    public QQChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "qq";
        this.displayName = "QQ";
        this.config = (config instanceof QQConfig c) ? c : new QQConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mediaRoot = initMediaRoot();
    }

    private Path initMediaRoot() {
        Path root;
        if (config.getMediaDir() != null && !config.getMediaDir().isBlank()) {
            root = Path.of(config.getMediaDir()).toAbsolutePath().normalize();
        } else {
            root = Path.of(System.getProperty("user.home"), ".nanobot", "media", "qq");
        }

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("创建 QQ 媒体目录失败：" + root, e);
        }
        return root;
    }

    @Override
    public void start() throws Exception {
        if (config.getAppId() == null || config.getAppId().isBlank()
                || config.getSecret() == null || config.getSecret().isBlank()) {
            throw new IllegalStateException("未配置 QQ app_id 与 secret");
        }

        running = true;

        if (client == null) {
            // Instantiate default client if none provided
            this.client = new DefaultQQBotClient(httpClient);
        }

        client.start(config.getAppId(), config.getSecret(), this::onMessage);
        System.out.println("QQ 机器人已启动");
    }

    // =========================================================
    // Default implementation of QQBotClient
    // =========================================================

    public static class DefaultQQBotClient implements QQBotClient {
        private final HttpClient httpClient;
        private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30_000);
        private final ObjectMapper mapper = new ObjectMapper();
        private String appId;
        private String secret;
        private String accessToken;
        private long accessTokenExpiry;
        private QQEventListener listener;
        private WebSocket webSocket;
        private ScheduledExecutorService heartbeatScheduler;
        private volatile int lastSSeq = 0;
        private final AtomicInteger sendMsgSeq = new AtomicInteger(1);

        public DefaultQQBotClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            this.sendMsgSeq.set(1 + Math.abs(new Random().nextInt()) % 50000);
        }

        @Override
        public void start(String appId, String secret, QQEventListener listener) throws Exception {
            this.appId = appId;
            this.secret = secret;
            this.listener = listener;
            refreshAccessToken();
            connectGateway();
        }

        private void connectGateway() throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.sgroup.qq.com/gateway"))
                    .header("Authorization", "QQBot " + getAccessToken())
                    .GET().build();

            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("获取 QQ Gateway 失败：" + response.body());
            }

            Map<String, Object> data = mapper.readValue(response.body(), Map.class);
            String wssUrl = (String) data.get("url");
            
            this.webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wssUrl), new QqWsListener()).join();
        }

        private class QqWsListener implements WebSocket.Listener {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                DefaultQQBotClient.this.webSocket = webSocket;
                System.out.println("QQ WebSocket 已连接");
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    try {
                        handleMessage(buffer.toString(), webSocket);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    buffer.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                System.out.println("QQ WebSocket 已关闭：" + statusCode + " " + reason);
                stopHeartbeat();
                // Attempt reconnect
                try {
                    Thread.sleep(3000);
                    connectGateway();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                System.err.println("QQ WebSocket 错误：" + error.getMessage());
                stopHeartbeat();
            }

            private void handleMessage(String json, WebSocket currentSocket) throws Exception {
                Map<String, Object> msg = mapper.readValue(json, Map.class);
                Integer op = asInt(msg.get("op"));
                
                if (msg.containsKey("s") && msg.get("s") != null) {
                    Integer seq = asInt(msg.get("s"));
                    if (seq != null) {
                        lastSSeq = seq;
                    }
                }

                if (op == null) return;

                switch (op) {
                    case 10: // Hello
                        Map<String, Object> d = (Map<String, Object>) msg.get("d");
                        Integer interval = d != null ? asInt(d.get("heartbeat_interval")) : null;
                        if (interval == null || interval <= 0) {
                            interval = 30_000;
                        }
                        startHeartbeat(interval);
                        identify(currentSocket);
                        break;
                    case 0: // Dispatch
                        String t = (String) msg.get("t");
                        Map<String, Object> eventData = (Map<String, Object>) msg.get("d");
                        if ("READY".equals(t)) {
                            System.out.println("QQ 机器人已就绪。");
                        } else if (t != null && t.endsWith("MESSAGE_CREATE")) {
                            processInboundMessage(eventData);
                        }
                        break;
                    case 7: // Reconnect
                    case 9: // Invalid Session
                        currentSocket.abort(); // Will trigger onClose and reconnect
                        break;
                }
            }
        }

        private void startHeartbeat(int interval) {
            stopHeartbeat();
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    Map<String, Object> beat = new HashMap<>();
                    beat.put("op", 1);
                    beat.put("d", lastSSeq > 0 ? lastSSeq : null);
                    
                    String json = mapper.writeValueAsString(beat);
                    if (webSocket != null && !webSocket.isInputClosed()) {
                        webSocket.sendText(json, true).join();
                    }
                } catch (Exception e) {
                    System.err.println("发送 QQ 心跳失败");
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
        }

        private void stopHeartbeat() {
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
                heartbeatScheduler = null;
            }
        }

        private void identify(WebSocket socket) throws Exception {
            Map<String, Object> identify = new HashMap<>();
            identify.put("op", 2);
            
            Map<String, Object> d = new HashMap<>();
            d.put("token", "QQBot " + getAccessToken());
            d.put("intents", 33554432 | 1073741824 | 1); // C2C & Group & Guilds
            identify.put("d", d);

            WebSocket ws = socket != null ? socket : this.webSocket;
            if (ws == null) {
                throw new IllegalStateException("QQ WebSocket 未就绪，无法发送 identify");
            }
            ws.sendText(mapper.writeValueAsString(identify), true).join();
        }

        private void processInboundMessage(Map<String, Object> data) {
            if (listener == null) return;

            boolean isGroup = data.containsKey("group_id");
            String chatId = isGroup ? (String) data.get("group_id") : null;
            
            Map<String, Object> author = (Map<String, Object>) data.get("author");
            String userId = author != null ? (String) author.get("id") : null;
            
            if (!isGroup && chatId == null) {
                chatId = userId; // For C2C, fallback to user ID
            }

            String msgId = (String) data.get("id");
            String content = (String) data.get("content");
            
            List<QQAttachment> atts = new ArrayList<>();
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) data.get("attachments");
            if (attachments != null) {
                for (Map<String, Object> a : attachments) {
                    String url = (String) a.get("url");
                    if (url != null && url.startsWith("//")) url = "https:" + url;
                    else if (url != null && !url.startsWith("http")) url = "https://" + url;
                    
                    atts.add(new QQAttachment(url, (String) a.get("filename"), (String) a.get("content_type")));
                }
            }

            final String fChatId = chatId;
            final String fUserId = userId;
            
            listener.onMessage(new QQInboundMessage() {
                public String id() { return msgId; }
                public String content() { return content; }
                public boolean isGroup() { return isGroup; }
                public String chatId() { return fChatId; }
                public String userId() { return fUserId; }
                public List<QQAttachment> attachments() { return atts; }
            });
        }

        @Override
        public void close() throws Exception {
            stopHeartbeat();
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping").join();
            }
        }

        @Override
        public Object uploadFile(String chatId, boolean isGroup, int fileType, String base64Data, String fileName) throws Exception {
            // In real SDK, this would call /v2/groups/{group_id}/files or /v2/users/{user_id}/files
            return null;
        }

        @Override
        public void sendText(String chatId, boolean isGroup, String msgId, String content, String format) throws Exception {
            String token = getAccessToken();
            String url = isGroup ? "https://api.sgroup.qq.com/v2/groups/" + chatId + "/messages" 
                                 : "https://api.sgroup.qq.com/v2/users/" + chatId + "/messages";
            
            Map<String, Object> body = new HashMap<>();
            body.put("content", content);
            body.put("msg_type", 0); // 0 is text
            body.put("msg_seq", nextSendMsgSeq());
            if (msgId != null && !msgId.isBlank()) {
                body.put("msg_id", msgId);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "QQBot " + token)
                    .header("X-Union-Appid", appId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IOException("QQ sendText failed: status=" + response.statusCode() + " body=" + response.body());
            }
        }

        private int nextSendMsgSeq() {
            int next = sendMsgSeq.incrementAndGet();
            if (next > 100000) {
                sendMsgSeq.set(1);
                return 1;
            }
            return next;
        }

        @Override
        public void sendMediaText(String chatId, boolean isGroup, String msgId, Object mediaPayload) throws Exception {
            // Similar to sendText but with media payload
        }

        private String getAccessToken() throws Exception {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry) {
                return accessToken;
            }
            refreshAccessToken();
            return accessToken;
        }

        private synchronized void refreshAccessToken() throws Exception {
            Map<String, String> body = Map.of(
                    "appId", appId,
                    "clientSecret", secret
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://bots.qq.com/app/getAppAccessToken"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
            this.accessToken = (String) data.get("access_token");
            Integer expires = asInt(data.get("expires_in"));
            if (expires == null || expires <= 0) {
                expires = 3600;
            }
            this.accessTokenExpiry = System.currentTimeMillis() + (expires - 60) * 1000L;
        }

        private static Integer asInt(Object value) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            if (value instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }

        private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
            return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
        }
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (client != null) {
            client.close();
        }
    }

    /**
     * 注入具体 SDK client。
     */
    public void setClient(QQBotClient client) {
        this.client = client;
    }

    /**
     * 入站消息处理。
     */
    public void onMessage(QQInboundMessage data) {
        try {
            if (data == null || data.id() == null) {
                return;
            }

            if (processedIdSet.contains(data.id())) {
                return;
            }
            rememberProcessedId(data.id());

            String chatId = data.chatId();
            String userId = data.userId();
            boolean isGroup = data.isGroup();

            chatTypeCache.put(chatId, isGroup ? "group" : "c2c");
            lastInboundMsgIdByChat.put(chatId, data.id());

            String content = data.content() != null ? data.content().trim() : "";
            AttachmentResult attachmentResult = handleAttachments(data.attachments());

            if (!attachmentResult.recvLines.isEmpty()) {
                String extra = "收到文件：\n" + String.join("\n", attachmentResult.recvLines);
                content = content.isBlank() ? extra : content + "\n\n" + extra;
            }

            if ((content == null || content.isBlank()) && attachmentResult.mediaPaths.isEmpty()) {
                return;
            }

            if (config.getAckMessage() != null && !config.getAckMessage().isBlank()) {
                try {
                    sendTextOnly(chatId, isGroup, data.id(), config.getAckMessage());
                } catch (Exception ignored) {
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("message_id", data.id());
            metadata.put("attachments", attachmentResult.attachmentMeta);

            handleMessage(
                    userId,
                    chatId,
                    content,
                    attachmentResult.mediaPaths,
                    metadata
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void rememberProcessedId(String id) {
        processedIds.addLast(id);
        processedIdSet.add(id);

        while (processedIds.size() > 1000) {
            String removed = processedIds.removeFirst();
            processedIdSet.remove(removed);
        }
    }

    /**
     * 处理附件：下载并返回本地路径 + 展示文本 + 元数据。
     */
    private AttachmentResult handleAttachments(List<QQAttachment> attachments) {
        List<String> mediaPaths = new ArrayList<>();
        List<String> recvLines = new ArrayList<>();
        List<Map<String, Object>> attMeta = new ArrayList<>();

        if (attachments == null) {
            return new AttachmentResult(mediaPaths, recvLines, attMeta);
        }

        for (QQAttachment att : attachments) {
            String url = att.getUrl() != null ? att.getUrl() : "";
            String filename = att.getFilename() != null ? att.getFilename() : "";
            String ctype = att.getContentType() != null ? att.getContentType() : "";

            String localPath = downloadToMediaDirChunked(url, filename);

            Map<String, Object> meta = new HashMap<>();
            meta.put("url", url);
            meta.put("filename", filename);
            meta.put("content_type", ctype);
            meta.put("saved_path", localPath);
            attMeta.add(meta);

            if (localPath != null) {
                mediaPaths.add(localPath);
                String shownName = !filename.isBlank() ? filename : Path.of(localPath).getFileName().toString();
                recvLines.add("- " + shownName + "\n  已保存：" + localPath);
            } else {
                String shownName = !filename.isBlank() ? filename : url;
                recvLines.add("- " + shownName + "\n  已保存：[下载失败]");
            }
        }

        return new AttachmentResult(mediaPaths, recvLines, attMeta);
    }

    /**
     * 分块流式下载附件，避免一次性读入内存。
     */
    private String downloadToMediaDirChunked(String url, String filenameHint) {
        if (url == null || url.isBlank()) {
            return null;
        }

        if (url.startsWith("//")) {
            url = "https:" + url;
        }

        String safe = sanitizeFilename(filenameHint);
        long ts = System.currentTimeMillis();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = sendHttp(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);

            String ext = extensionFromContentType(contentType);
            if (safe == null || safe.isBlank()) {
                safe = "qq_" + ts + (ext != null ? ext : ".bin");
            }

            Path finalPath = mediaRoot.resolve(ts + "_" + safe).normalize();
            Path tmpPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");

            long total = 0;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[Math.max(1024, config.getDownloadChunkSize())];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > config.getDownloadMaxBytes()) {
                        try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
                        return null;
                    }
                    out.write(buf, 0, n);
                }
            }

            Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return finalPath.toString();

        } catch (Exception e) {
            return null;
        }
    }

    private String extensionFromContentType(String contentType) {
        if (contentType == null) return null;
        if (contentType.startsWith("image/png")) return ".png";
        if (contentType.startsWith("image/jpeg")) return ".jpg";
        if (contentType.startsWith("image/gif")) return ".gif";
        if (contentType.startsWith("image/webp")) return ".webp";
        if (contentType.startsWith("application/pdf")) return ".pdf";
        return null;
    }

    private static String sanitizeFilename(String name) {
        name = name == null ? "" : name.trim();
        name = Path.of(name.isBlank() ? "file.bin" : name).getFileName().toString();
        name = SAFE_NAME_RE.matcher(name).replaceAll("_").replaceAll("^[._ ]+|[._ ]+$", "");
        return name.isBlank() ? "file.bin" : name;
    }

    private static boolean isImageName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static int guessSendFileType(String filename) {
        return isImageName(filename) ? QQ_FILE_TYPE_IMAGE : QQ_FILE_TYPE_FILE;
    }

    public void send(OutboundMessage msg) throws Exception {
        if (client == null) {
            throw new IllegalStateException("QQ client not initialized");
        }

        String chatId = msg.getChatId();
        boolean isGroup = "group".equalsIgnoreCase(chatTypeCache.getOrDefault(chatId, "c2c"));
        String replyToMsgId = null;
        if (msg.getMetadata() != null) {
            Object v = msg.getMetadata().get("message_id");
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isBlank()) {
                    replyToMsgId = s;
                }
            }
        }
        if (replyToMsgId == null || replyToMsgId.isBlank()) {
            replyToMsgId = lastInboundMsgIdByChat.get(chatId);
        }

        // 1. 先发媒体
        if (msg.getMedia() != null) {
            for (String mediaRef : msg.getMedia()) {
                MediaBytes media = readMediaBytes(mediaRef);
                if (media == null || media.data == null || media.filename == null) {
                    continue;
                }

                int fileType = guessSendFileType(media.filename);
                String base64 = Base64.getEncoder().encodeToString(media.data);

                Object mediaPayload = client.uploadFile(
                        chatId,
                        isGroup,
                        fileType,
                        base64,
                        fileType == QQ_FILE_TYPE_IMAGE ? null : media.filename
                );

                client.sendMediaText(chatId, isGroup, replyToMsgId, mediaPayload);
            }
        }

        // 2. 再发文本
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            sendTextOnly(chatId, isGroup, replyToMsgId, msg.getContent());
        }
    }

    private void sendTextOnly(String chatId, boolean isGroup, String msgId, String content) throws Exception {
        if (client == null) {
            throw new IllegalStateException("QQ client not initialized");
        }
        client.sendText(chatId, isGroup, msgId, content, config.getMsgFormat());
    }

    private String nextMsgId() {
        msgSeq++;
        return String.valueOf(System.currentTimeMillis()) + "_" + msgSeq;
    }

    /**
     * 读取出站媒体。
     *
     * 支持：
     * - 本地路径
     * - file://
     * - http(s)
     */
    private MediaBytes readMediaBytes(String mediaRef) {
        mediaRef = mediaRef == null ? "" : mediaRef.trim();
        if (mediaRef.isBlank()) {
            return null;
        }

        try {
            if (!mediaRef.startsWith("http://") && !mediaRef.startsWith("https://")) {
                Path localPath;
                if (mediaRef.startsWith("file://")) {
                    localPath = Path.of(URI.create(mediaRef));
                } else {
                    localPath = Path.of(mediaRef).toAbsolutePath().normalize();
                }

                if (!Files.isRegularFile(localPath)) {
                    return null;
                }

                byte[] data = Files.readAllBytes(localPath);
                return new MediaBytes(data, localPath.getFileName().toString());
            }

            // TODO: 这里最好接你前面已有的 SSRF / URL 校验模块
            HttpRequest request = HttpRequest.newBuilder(URI.create(mediaRef))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = sendHttp(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400 || response.body() == null || response.body().length == 0) {
                return null;
            }

            String path = URI.create(mediaRef).getPath();
            String filename = (path == null || path.isBlank()) ? "file.bin" : Path.of(path).getFileName().toString();

            return new MediaBytes(response.body(), filename);

        } catch (Exception e) {
            return null;
        }
    }

    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        return RetryUtils.executeWithRetry(() -> circuitBreaker.execute(() -> httpClient.send(request, handler)));
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    private static class MediaBytes {
        final byte[] data;
        final String filename;

        MediaBytes(byte[] data, String filename) {
            this.data = data;
            this.filename = filename;
        }
    }

    private static class AttachmentResult {
        final List<String> mediaPaths;
        final List<String> recvLines;
        final List<Map<String, Object>> attachmentMeta;

        AttachmentResult(List<String> mediaPaths, List<String> recvLines, List<Map<String, Object>> attachmentMeta) {
            this.mediaPaths = mediaPaths;
            this.recvLines = recvLines;
            this.attachmentMeta = attachmentMeta;
        }
    }
}
