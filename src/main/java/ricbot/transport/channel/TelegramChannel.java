package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram 渠道实现。
 *
 * 对应 Python: telegram.py
 *
 * 主要职责：
 * 1. long polling 收消息
 * 2. 支持命令、文本、照片、语音、文档、位置
 * 3. 支持 reply / reaction / typing
 * 4. 支持 streaming：通过 editMessageText 渐进更新
 * 5. 支持 Markdown -> Telegram HTML 转换
 */
public class TelegramChannel extends BaseChannel {

    private static final int TELEGRAM_MAX_MESSAGE_LEN = 4000;
    private static final int SEND_MAX_RETRIES = 3;
    private static final double SEND_RETRY_BASE_DELAY_S = 0.5;
    private static final double STREAM_EDIT_INTERVAL_DEFAULT = 0.6;

    public static class TelegramConfig {
        private boolean enabled = false;
        private String token = "";
        private List<String> allowFrom = new ArrayList<>();
        private String proxy;
        private boolean replyToMessage = false;
        private String reactEmoji = "👀";
        private String groupPolicy = "mention"; // open / mention
        private int connectionPoolSize = 32;
        private double poolTimeout = 5.0;
        private boolean streaming = true;
        private double streamEditInterval = STREAM_EDIT_INTERVAL_DEFAULT;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public String getProxy() { return proxy; }
        public void setProxy(String proxy) { this.proxy = proxy; }
        public boolean isReplyToMessage() { return replyToMessage; }
        public void setReplyToMessage(boolean replyToMessage) { this.replyToMessage = replyToMessage; }
        public String getReactEmoji() { return reactEmoji; }
        public void setReactEmoji(String reactEmoji) { this.reactEmoji = reactEmoji; }
        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }
        public int getConnectionPoolSize() { return connectionPoolSize; }
        public void setConnectionPoolSize(int connectionPoolSize) { this.connectionPoolSize = connectionPoolSize; }
        public double getPoolTimeout() { return poolTimeout; }
        public void setPoolTimeout(double poolTimeout) { this.poolTimeout = poolTimeout; }
        public boolean isStreaming() { return streaming; }
        public void setStreaming(boolean streaming) { this.streaming = streaming; }
        public double getStreamEditInterval() { return streamEditInterval; }
        public void setStreamEditInterval(double streamEditInterval) { this.streamEditInterval = streamEditInterval; }
    }

    public static class StreamBuf {
        String text = "";
        Long messageId;
        long lastEditMillis = 0;
        String streamId;
    }

    /**
     * 统一抽象 Telegram 入站消息。
     */
    public static class TelegramInboundMessage {
        public String senderId;
        public String chatId;
        public String text;
        public boolean isGroup;
        public Long messageId;
        public List<String> mediaPaths = new ArrayList<>();
        public Map<String, Object> metadata = new HashMap<>();
    }

    private final TelegramConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private final Map<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    private final Map<String, StreamBuf> streamBufs = new ConcurrentHashMap<>();

    private volatile String botUsername;
    private volatile Long botUserId;
    private volatile long updateOffset = 0;

    public TelegramChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "telegram";
        this.displayName = "Telegram";
        this.config = (config instanceof TelegramConfig c) ? c : new TelegramConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void start() throws Exception {
        if (config.getToken() == null || config.getToken().isBlank()) {
            throw new IllegalStateException("Telegram token not configured");
        }

        running = true;
        resolveBotInfo();

        scheduler.scheduleWithFixedDelay(this::pollUpdatesOnce, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() throws Exception {
        running = false;
        for (ScheduledFuture<?> task : typingTasks.values()) {
            task.cancel(true);
        }
        typingTasks.clear();
        scheduler.shutdownNow();
    }

    public void send(OutboundMessage msg) throws Exception {
        String chatId = msg.getChatId();
        Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap();
        Long replyToMessageId = longValue(meta.get("telegram_reply_to"));

        // 先发媒体
        if (msg.getMedia() != null) {
            for (String path : msg.getMedia()) {
                sendMedia(chatId, path, replyToMessageId);
            }
        }

        // 再发文本
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            for (String part : splitMessage(msg.getContent(), TELEGRAM_MAX_MESSAGE_LEN)) {
                sendTextWithRetry(chatId, part, replyToMessageId);
                replyToMessageId = null; // 后续分片不再 reply
            }
        }
    }

    @Override
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        StreamBuf buf = streamBufs.computeIfAbsent(chatId, k -> new StreamBuf());
        String streamId = metadata != null ? stringValue(metadata.get("_stream_id")) : null;

        if (streamId != null && !streamId.isBlank()) {
            if (buf.streamId != null && !buf.streamId.equals(streamId)) {
                finalizeStream(chatId, buf);
                buf = new StreamBuf();
                buf.streamId = streamId;
                streamBufs.put(chatId, buf);
            } else {
                buf.streamId = streamId;
            }
        }

        buf.text += (delta != null ? delta : "");
        boolean streamEnd = metadata != null && Boolean.TRUE.equals(metadata.get("_stream_end"));
        long now = System.currentTimeMillis();

        if (streamEnd) {
            finalizeStream(chatId, buf);
            return;
        }

        if (buf.text.isBlank()) {
            return;
        }

        if (buf.messageId == null) {
            buf.messageId = sendTextWithRetry(chatId, buf.text, null);
            buf.lastEditMillis = now;
            startTyping(chatId);
            return;
        }

        long intervalMs = (long) (config.getStreamEditInterval() * 1000);
        if (now - buf.lastEditMillis < intervalMs) {
            return;
        }

        editMessageText(chatId, buf.messageId, buf.text);
        buf.lastEditMillis = now;
    }

    private void finalizeStream(String chatId, StreamBuf buf) throws Exception {
        if (buf.text == null || buf.text.isBlank()) {
            streamBufs.remove(chatId);
            stopTyping(chatId);
            return;
        }

        if (buf.messageId == null) {
            sendTextWithRetry(chatId, buf.text, null);
        } else {
            List<String> parts = splitMessage(buf.text, TELEGRAM_MAX_MESSAGE_LEN);
            if (!parts.isEmpty()) {
                editMessageText(chatId, buf.messageId, parts.get(0));
                for (int i = 1; i < parts.size(); i++) {
                    sendTextWithRetry(chatId, parts.get(i), null);
                }
            }
        }
        streamBufs.remove(chatId);
        stopTyping(chatId);
    }

    // =========================================================
    // Polling
    // =========================================================

    @SuppressWarnings("unchecked")
    private void pollUpdatesOnce() {
        if (!running) {
            return;
        }

        try {
            String url = apiBase() + "/getUpdates?timeout=30&allowed_updates=%5B%22message%22%5D&offset=" + updateOffset;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(40)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
            Object ok = json.get("ok");
            if (!Boolean.TRUE.equals(ok)) {
                return;
            }

            Object resultObj = json.get("result");
            if (!(resultObj instanceof List<?> updates)) {
                return;
            }

            for (Object uObj : updates) {
                if (!(uObj instanceof Map<?, ?> raw)) continue;
                Map<String, Object> update = (Map<String, Object>) raw;

                Long updateId = longValue(update.get("update_id"));
                if (updateId != null) {
                    updateOffset = updateId + 1;
                }

                Map<String, Object> message = safeMap(update.get("message"));
                if (message.isEmpty()) continue;

                TelegramInboundMessage inbound = parseInboundMessage(message);
                if (inbound == null) continue;

                if (inbound.isGroup && !shouldRespondInGroup(inbound.text)) {
                    continue;
                }

                if (config.isReplyToMessage() && inbound.messageId != null) {
                    inbound.metadata.put("telegram_reply_to", inbound.messageId);
                }

                handleMessage(
                        inbound.senderId,
                        inbound.chatId,
                        inbound.text,
                        inbound.mediaPaths,
                        inbound.metadata
                );
            }

        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private TelegramInboundMessage parseInboundMessage(Map<String, Object> msg) {
        Map<String, Object> from = safeMap(msg.get("from"));
        Map<String, Object> chat = safeMap(msg.get("chat"));

        String senderId = stringValue(from.get("id"));
        String chatId = stringValue(chat.get("id"));
        if (senderId.isBlank() || chatId.isBlank()) {
            return null;
        }

        String chatType = stringValue(chat.get("type"));
        boolean isGroup = Set.of("group", "supergroup").contains(chatType);

        String text = stringValue(msg.get("text"));

        TelegramInboundMessage inbound = new TelegramInboundMessage();
        inbound.senderId = senderId;
        inbound.chatId = chatId;
        inbound.text = text;
        inbound.isGroup = isGroup;
        inbound.messageId = longValue(msg.get("message_id"));

        inbound.metadata.put("telegram_chat_type", chatType);

        // photo
        Object photoObj = msg.get("photo");
        if (photoObj instanceof List<?> photos && !photos.isEmpty()) {
            Map<String, Object> best = null;
            for (Object p : photos) {
                if (p instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> photo = (Map<String, Object>) m;
                    best = photo;
                }
            }
            if (best != null) {
                String fileId = stringValue(best.get("file_id"));
                String local = downloadTelegramFile(fileId, "photo.jpg");
                if (local != null) {
                    inbound.mediaPaths.add(local);
                }
                if (inbound.text == null || inbound.text.isBlank()) {
                    inbound.text = "[photo]";
                }
            }
        }

        // voice / audio / document
        for (String field : List.of("voice", "audio", "document")) {
            Map<String, Object> media = safeMap(msg.get(field));
            if (!media.isEmpty()) {
                String fileId = stringValue(media.get("file_id"));
                String fileName = stringValue(media.get("file_name"));
                if (fileName.isBlank()) {
                    fileName = field + ".bin";
                }
                String local = downloadTelegramFile(fileId, fileName);
                if (local != null) {
                    inbound.mediaPaths.add(local);
                }
                if (inbound.text == null || inbound.text.isBlank()) {
                    inbound.text = "[" + field + "]";
                }
            }
        }

        // location
        Map<String, Object> location = safeMap(msg.get("location"));
        if (!location.isEmpty()) {
            inbound.text = "Location: lat=" + location.get("latitude") + ", lon=" + location.get("longitude");
        }

        return inbound;
    }

    private boolean shouldRespondInGroup(String text) {
        if ("open".equalsIgnoreCase(config.getGroupPolicy())) {
            return true;
        }
        if ("mention".equalsIgnoreCase(config.getGroupPolicy())) {
            if (botUsername == null || botUsername.isBlank()) {
                return false;
            }
            return text != null && text.contains("@" + botUsername);
        }
        return true;
    }

    // =========================================================
    // Telegram API helpers
    // =========================================================

    private void resolveBotInfo() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + "/getMe")).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
        Map<String, Object> result = safeMap(json.get("result"));

        botUsername = stringValue(result.get("username"));
        botUserId = longValue(result.get("id"));
    }

    private Long sendTextWithRetry(String chatId, String text, Long replyToMessageId) throws Exception {
        Exception last = null;
        for (int i = 0; i < SEND_MAX_RETRIES; i++) {
            try {
                return sendText(chatId, text, replyToMessageId);
            } catch (Exception e) {
                last = e;
                Thread.sleep((long) (SEND_RETRY_BASE_DELAY_S * 1000 * Math.pow(2, i)));
            }
        }
        throw last != null ? last : new RuntimeException("Telegram send failed");
    }

    private Long sendText(String chatId, String text, Long replyToMessageId) throws Exception {
        String html = markdownToTelegramHtml(text);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        if (replyToMessageId != null) {
            body.put("reply_parameters", Map.of("message_id", replyToMessageId));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + "/sendMessage"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
        Map<String, Object> result = safeMap(json.get("result"));
        return longValue(result.get("message_id"));
    }

    private void editMessageText(String chatId, Long messageId, String text) throws Exception {
        if (messageId == null) return;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", markdownToTelegramHtml(text));
        body.put("parse_mode", "HTML");

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + "/editMessageText"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private void sendMedia(String chatId, String path, Long replyToMessageId) throws Exception {
        String mediaType = getMediaType(path);

        // 这里先用 Telegram file URL 方式的简化实现；真正 multipart 后续可补。
        if (isRemoteMediaUrl(path)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put(mediaType, path);
            if (replyToMessageId != null) {
                body.put("reply_parameters", Map.of("message_id", replyToMessageId));
            }
            String method = switch (mediaType) {
                case "photo" -> "sendPhoto";
                case "voice" -> "sendVoice";
                case "audio" -> "sendAudio";
                default -> "sendDocument";
            };

            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + "/" + method))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } else {
            // TODO: 这里后续可换成 multipart/form-data 上传本地文件
            // 当前为了不把代码展开过大，先跳过本地 multipart 细节
        }
    }

    private String downloadTelegramFile(String fileId, String fallbackName) {
        if (fileId == null || fileId.isBlank()) {
            return null;
        }

        try {
            HttpRequest req1 = HttpRequest.newBuilder(URI.create(apiBase() + "/getFile?file_id=" + fileId)).GET().build();
            HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> json1 = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resp1.body(), Map.class);
            Map<String, Object> result = safeMap(json1.get("result"));
            String filePath = stringValue(result.get("file_path"));
            if (filePath.isBlank()) {
                return null;
            }

            String url = "https://api.telegram.org/file/bot" + config.getToken() + "/" + filePath;
            HttpRequest req2 = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(120)).build();
            HttpResponse<byte[]> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofByteArray());

            Path mediaDir = Path.of(System.getProperty("user.home"), ".nanobot", "media", "telegram");
            Files.createDirectories(mediaDir);

            String fileName = fallbackName;
            int idx = filePath.lastIndexOf('/');
            if (idx >= 0 && idx + 1 < filePath.length()) {
                fileName = filePath.substring(idx + 1);
            }

            Path out = mediaDir.resolve(System.currentTimeMillis() + "_" + sanitizeFilename(fileName));
            Files.write(out, resp2.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return out.toString();

        } catch (Exception e) {
            return null;
        }
    }

    private void startTyping(String chatId) {
        stopTyping(chatId);
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                sendChatAction(chatId, "typing");
            } catch (Exception ignored) {
            }
        }, 0, 4, TimeUnit.SECONDS);
        typingTasks.put(chatId, task);
    }

    private void stopTyping(String chatId) {
        ScheduledFuture<?> task = typingTasks.remove(chatId);
        if (task != null) {
            task.cancel(true);
        }
    }

    private void sendChatAction(String chatId, String action) throws Exception {
        Map<String, Object> body = Map.of("chat_id", chatId, "action", action);
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + "/sendChatAction"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private String apiBase() {
        return "https://api.telegram.org/bot" + config.getToken();
    }

    private static String getMediaType(String path) {
        String ext = path != null && path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext)) return "photo";
        if ("ogg".equals(ext)) return "voice";
        if (Set.of("mp3", "m4a", "wav", "aac").contains(ext)) return "audio";
        return "document";
    }

    private static boolean isRemoteMediaUrl(String path) {
        return path != null && (path.startsWith("http://") || path.startsWith("https://"));
    }

    private static List<String> splitMessage(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String remaining = text;
        while (remaining.length() > maxLen) {
            out.add(remaining.substring(0, maxLen));
            remaining = remaining.substring(maxLen);
        }
        if (!remaining.isEmpty()) out.add(remaining);
        return out;
    }

    // =========================================================
    // Markdown -> Telegram HTML
    // =========================================================

    private static String escapeTelegramHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stripMd(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("~~(.+?)~~", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .trim();
    }

    private static String renderTableBox(List<String> lines) {
        List<List<String>> rows = new ArrayList<>();
        boolean hasSep = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);

            String[] cells = trimmed.split("\\|", -1);
            List<String> row = new ArrayList<>();
            boolean sep = true;

            for (String c : cells) {
                String cell = stripMd(c.trim());
                row.add(cell);
                if (!cell.matches("^:?-+:?$")) sep = false;
            }
            if (sep) {
                hasSep = true;
                continue;
            }
            rows.add(row);
        }

        if (rows.isEmpty() || !hasSep) {
            return String.join("\n", lines);
        }

        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> row : rows) {
            while (row.size() < cols) row.add("");
        }

        int[] widths = new int[cols];
        for (List<String> row : rows) {
            for (int i = 0; i < cols; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        List<String> out = new ArrayList<>();
        out.add(padRow(rows.get(0), widths));
        List<String> sep = new ArrayList<>();
        for (int w : widths) sep.add("─".repeat(Math.max(1, w)));
        out.add(String.join("  ", sep));

        for (int i = 1; i < rows.size(); i++) {
            out.add(padRow(rows.get(i), widths));
        }
        return String.join("\n", out);
    }

    private static String padRow(List<String> row, int[] widths) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < widths.length; i++) {
            parts.add(String.format("%-" + widths[i] + "s", row.get(i)));
        }
        return String.join("  ", parts);
    }

    public static String markdownToTelegramHtml(String text) {
        if (text == null || text.isBlank()) return "";

        List<String> codeBlocks = new ArrayList<>();
        Matcher blockMatcher = Pattern.compile("```[\\w]*\\n?([\\s\\S]*?)```").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (blockMatcher.find()) {
            codeBlocks.add(blockMatcher.group(1));
            blockMatcher.appendReplacement(sb, "\u0000CB" + (codeBlocks.size() - 1) + "\u0000");
        }
        blockMatcher.appendTail(sb);
        text = sb.toString();

        String[] lines = text.split("\n");
        List<String> rebuilt = new ArrayList<>();
        for (int i = 0; i < lines.length; ) {
            if (lines[i].trim().matches("^\\|.+\\|$")) {
                List<String> tbl = new ArrayList<>();
                while (i < lines.length && lines[i].trim().matches("^\\|.+\\|$")) {
                    tbl.add(lines[i]);
                    i++;
                }
                String box = renderTableBox(tbl);
                if (!box.equals(String.join("\n", tbl))) {
                    codeBlocks.add(box);
                    rebuilt.add("\u0000CB" + (codeBlocks.size() - 1) + "\u0000");
                } else {
                    rebuilt.addAll(tbl);
                }
            } else {
                rebuilt.add(lines[i++]);
            }
        }
        text = String.join("\n", rebuilt);

        List<String> inlineCodes = new ArrayList<>();
        Matcher inlineMatcher = Pattern.compile("`([^`]+)`").matcher(text);
        sb = new StringBuffer();
        while (inlineMatcher.find()) {
            inlineCodes.add(inlineMatcher.group(1));
            inlineMatcher.appendReplacement(sb, "\u0000IC" + (inlineCodes.size() - 1) + "\u0000");
        }
        inlineMatcher.appendTail(sb);
        text = sb.toString();

        text = text.replaceAll("(?m)^#{1,6}\\s+(.+)$", "$1");
        text = text.replaceAll("(?m)^>\\s*(.*)$", "$1");
        text = escapeTelegramHtml(text);

        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        text = text.replaceAll("__(.+?)__", "<b>$1</b>");
        text = text.replaceAll("(?<![a-zA-Z0-9])_([^_]+)_(?![a-zA-Z0-9])", "<i>$1</i>");
        text = text.replaceAll("~~(.+?)~~", "<s>$1</s>");
        text = text.replaceAll("(?m)^[-*]\\s+", "• ");

        for (int i = 0; i < inlineCodes.size(); i++) {
            text = text.replace("\u0000IC" + i + "\u0000", "<code>" + escapeTelegramHtml(inlineCodes.get(i)) + "</code>");
        }
        for (int i = 0; i < codeBlocks.size(); i++) {
            text = text.replace("\u0000CB" + i + "\u0000", "<pre><code>" + escapeTelegramHtml(codeBlocks.get(i)) + "</code></pre>");
        }

        return text;
    }

    private static String sanitizeFilename(String fileName) {
        return fileName.replaceAll("[^\\w.\\-]+", "_");
    }

    private static Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return new HashMap<>();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}