package ricbot.transport.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mochat 渠道实现。
 *
 * 主要目标：
 * 1. 优先用 socket.io 接入
 * 2. websocket 不可用时回退到 HTTP polling
 * 3. 维护 session / panel target
 * 4. 支持延迟回复缓冲
 * 5. 维护 cursor 和 message-id 去重
 *
 * 说明：
 * Java 这里把 socket.io 依赖抽象成接口，方便你接任意 Java socket.io client。
 */
public class MochatChannel extends BaseChannel {

    private static final int MAX_SEEN_MESSAGE_IDS = 2000;
    private static final long CURSOR_SAVE_DEBOUNCE_MS = 500L;

    public static class MochatConfig {
        private boolean enabled = false;
        private String baseUrl = "https://mochat.io";
        private String socketUrl = "";
        private String socketPath = "/socket.io";
        private boolean socketDisableMsgpack = false;
        private int socketReconnectDelayMs = 1000;
        private int socketMaxReconnectDelayMs = 10000;
        private int socketConnectTimeoutMs = 10000;
        private int refreshIntervalMs = 30000;
        private int watchTimeoutMs = 25000;
        private int watchLimit = 100;
        private int retryDelayMs = 500;
        private int maxRetryAttempts = 0;
        private String clawToken = "";
        private String agentUserId = "";
        private List<String> sessions = new ArrayList<>();
        private List<String> panels = new ArrayList<>();
        private List<String> allowFrom = new ArrayList<>();
        private MentionConfig mention = new MentionConfig();
        private Map<String, GroupRule> groups = new HashMap<>();
        private String replyDelayMode = "non-mention";
        private int replyDelayMs = 120000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getSocketUrl() { return socketUrl; }
        public void setSocketUrl(String socketUrl) { this.socketUrl = socketUrl; }
        public String getSocketPath() { return socketPath; }
        public void setSocketPath(String socketPath) { this.socketPath = socketPath; }
        public boolean isSocketDisableMsgpack() { return socketDisableMsgpack; }
        public void setSocketDisableMsgpack(boolean socketDisableMsgpack) { this.socketDisableMsgpack = socketDisableMsgpack; }
        public int getSocketReconnectDelayMs() { return socketReconnectDelayMs; }
        public void setSocketReconnectDelayMs(int socketReconnectDelayMs) { this.socketReconnectDelayMs = socketReconnectDelayMs; }
        public int getSocketMaxReconnectDelayMs() { return socketMaxReconnectDelayMs; }
        public void setSocketMaxReconnectDelayMs(int socketMaxReconnectDelayMs) { this.socketMaxReconnectDelayMs = socketMaxReconnectDelayMs; }
        public int getSocketConnectTimeoutMs() { return socketConnectTimeoutMs; }
        public void setSocketConnectTimeoutMs(int socketConnectTimeoutMs) { this.socketConnectTimeoutMs = socketConnectTimeoutMs; }
        public int getRefreshIntervalMs() { return refreshIntervalMs; }
        public void setRefreshIntervalMs(int refreshIntervalMs) { this.refreshIntervalMs = refreshIntervalMs; }
        public int getWatchTimeoutMs() { return watchTimeoutMs; }
        public void setWatchTimeoutMs(int watchTimeoutMs) { this.watchTimeoutMs = watchTimeoutMs; }
        public int getWatchLimit() { return watchLimit; }
        public void setWatchLimit(int watchLimit) { this.watchLimit = watchLimit; }
        public int getRetryDelayMs() { return retryDelayMs; }
        public void setRetryDelayMs(int retryDelayMs) { this.retryDelayMs = retryDelayMs; }
        public int getMaxRetryAttempts() { return maxRetryAttempts; }
        public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }
        public String getClawToken() { return clawToken; }
        public void setClawToken(String clawToken) { this.clawToken = clawToken; }
        public String getAgentUserId() { return agentUserId; }
        public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
        public List<String> getSessions() { return sessions; }
        public void setSessions(List<String> sessions) { this.sessions = sessions; }
        public List<String> getPanels() { return panels; }
        public void setPanels(List<String> panels) { this.panels = panels; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public MentionConfig getMention() { return mention; }
        public void setMention(MentionConfig mention) { this.mention = mention; }
        public Map<String, GroupRule> getGroups() { return groups; }
        public void setGroups(Map<String, GroupRule> groups) { this.groups = groups; }
        public String getReplyDelayMode() { return replyDelayMode; }
        public void setReplyDelayMode(String replyDelayMode) { this.replyDelayMode = replyDelayMode; }
        public int getReplyDelayMs() { return replyDelayMs; }
        public void setReplyDelayMs(int replyDelayMs) { this.replyDelayMs = replyDelayMs; }
    }

    public static class MentionConfig {
        private boolean requireInGroups = false;
        public boolean isRequireInGroups() { return requireInGroups; }
        public void setRequireInGroups(boolean requireInGroups) { this.requireInGroups = requireInGroups; }
    }

    public static class GroupRule {
        private boolean requireMention = false;
        public boolean isRequireMention() { return requireMention; }
        public void setRequireMention(boolean requireMention) { this.requireMention = requireMention; }
    }

    public interface MochatSocketClient {
        boolean connect(String socketUrl, String socketPath, Map<String, String> headers, MochatSocketListener listener);
        void close();
    }

    public interface MochatSocketListener {
        void onMessage(Map<String, Object> event);
        void onDisconnect();
    }

    public static class MochatBufferedEntry {
        String rawBody;
        String author;
        String senderName = "";
        String senderUsername = "";
        Long timestamp;
        String messageId = "";
        String groupId = "";
    }

    public static class DelayState {
        final List<MochatBufferedEntry> entries = new ArrayList<>();
        final ReentrantLock lock = new ReentrantLock();
        ScheduledFuture<?> timer;
    }

    public static class MochatTarget {
        final String id;
        final boolean isPanel;

        public MochatTarget(String id, boolean isPanel) {
            this.id = id;
            this.isPanel = isPanel;
        }
    }

    private final MochatConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private MochatSocketClient socketClient;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> refreshFuture;

    private final Path stateDir;
    private final Path cursorPath;

    private final Map<String, Long> sessionCursor = new ConcurrentHashMap<>();
    private ScheduledFuture<?> cursorSaveTask;

    private final Set<String> sessionSet = ConcurrentHashMap.newKeySet();
    private final Set<String> panelSet = ConcurrentHashMap.newKeySet();

    private final Set<String> coldSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sessionByConverse = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> seenSet = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> seenQueue = new ConcurrentHashMap<>();

    private final Map<String, DelayState> delayStates = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> targetLocks = new ConcurrentHashMap<>();

    private volatile boolean fallbackMode = false;

    public MochatChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "mochat";
        this.displayName = "Mochat";
        this.config = (config instanceof MochatConfig c) ? c : new MochatConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.stateDir = Path.of(System.getProperty("user.home"), ".nanobot", "mochat");
        this.cursorPath = stateDir.resolve("session_cursors.json");
    }

    public void setSocketClient(MochatSocketClient socketClient) {
        this.socketClient = socketClient;
    }

    @Override
    public void start() throws Exception {
        if (config.getClawToken() == null || config.getClawToken().isBlank()) {
            throw new IllegalStateException("Mochat claw_token not configured");
        }

        running = true;
        Files.createDirectories(stateDir);
        loadSessionCursors();

        seedTargetsFromConfig();
        refreshTargets(false);

        if (!startSocketClient()) {
            ensureFallbackWorkers();
        }

        refreshFuture = scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        refreshTargets(true);
                    } catch (Exception ignored) {
                    }
                },
                config.getRefreshIntervalMs(),
                config.getRefreshIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void stop() throws Exception {
        running = false;

        if (refreshFuture != null) {
            refreshFuture.cancel(true);
        }
        if (cursorSaveTask != null) {
            cursorSaveTask.cancel(true);
        }
        if (socketClient != null) {
            socketClient.close();
        }
        scheduler.shutdownNow();
    }

    /**
     * 出站发送。
     */
    public void send(OutboundMessage msg) throws Exception {
        MochatTarget target = resolveMochatTarget(msg.getChatId());
        if (target.id == null || target.id.isBlank()) {
            throw new IllegalArgumentException("Invalid Mochat target: " + msg.getChatId());
        }

        if (target.isPanel) {
            sendPanelMessage(target.id, msg.getContent());
        } else {
            sendSessionMessage(target.id, msg.getContent());
        }
    }

    /**
     * socket.io 启动。
     */
    private boolean startSocketClient() {
        if (socketClient == null) {
            return false;
        }

        String socketUrl = (config.getSocketUrl() != null && !config.getSocketUrl().isBlank())
                ? config.getSocketUrl()
                : config.getBaseUrl();

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + config.getClawToken()
        );

        boolean ok = socketClient.connect(socketUrl, config.getSocketPath(), headers, new MochatSocketListener() {
            @Override
            public void onMessage(Map<String, Object> event) {
                handleRealtimeEvent(event);
            }

            @Override
            public void onDisconnect() {
                fallbackMode = true;
                ensureFallbackWorkers();
            }
        });

        fallbackMode = !ok;
        return ok;
    }

    /**
     * fallback：开启 polling workers。
     */
    private void ensureFallbackWorkers() {
        fallbackMode = true;

        for (String sessionId : sessionSet) {
            scheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            watchSession(sessionId);
                        } catch (Exception ignored) {
                        }
                    },
                    0,
                    Math.max(config.getWatchTimeoutMs(), 1000),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * 从 config 初始化 target 集合。
     */
    private void seedTargetsFromConfig() {
        sessionSet.clear();
        panelSet.clear();
        sessionSet.addAll(config.getSessions());
        panelSet.addAll(config.getPanels());
    }

    /**
     * 刷新 target。
     */
    private void refreshTargets(boolean subscribeNew) throws Exception {
        // TODO: 调 Mochat API 拉取最新 session / panel 列表
        // 当前保留 target 结构和 refresh 时机
        if (subscribeNew) {
            // 这里保留扩展点：发现新 session 后可补 worker
        }
    }

    /**
     * watch 某个 session 的新消息。
     */
    private void watchSession(String sessionId) throws Exception {
        long cursor = sessionCursor.getOrDefault(sessionId, 0L);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/api/watch?session=" + sessionId
                        + "&cursor=" + cursor
                        + "&limit=" + config.getWatchLimit()))
                .timeout(java.time.Duration.ofMillis(config.getWatchTimeoutMs()))
                .header("Authorization", "Bearer " + config.getClawToken())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            return;
        }

        Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() {});
        Object eventsObj = body.get("events");
        if (!(eventsObj instanceof List<?> events)) {
            return;
        }

        for (Object eventObj : events) {
            if (eventObj instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = (Map<String, Object>) raw;
                handleRealtimeEvent(event);
            }
        }

        Object nextCursor = body.get("next_cursor");
        if (nextCursor instanceof Number n) {
            sessionCursor.put(sessionId, n.longValue());
            scheduleCursorSave();
        }
    }

    /**
     * 实时 / 轮询事件统一处理入口。
     */
    private void handleRealtimeEvent(Map<String, Object> event) {
        if (event == null) return;
        String type = strField(event, "type");
        if (!"message.add".equals(type)) {
            return;
        }

        Map<String, Object> payload = safeDict(event.get("payload"));
        String messageId = strField(payload, "messageId");
        String converseId = strField(payload, "converseId");
        String groupId = strField(payload, "groupId");
        String author = strField(payload, "author");

        String targetKey = !groupId.isBlank() ? groupId : converseId;
        if (targetKey.isBlank() || messageId.isBlank()) {
            return;
        }

        if (alreadySeen(targetKey, messageId)) {
            return;
        }

        String content = normalizeMochatContent(payload.get("content"));
        Map<String, Object> meta = safeDict(payload.get("meta"));
        Map<String, Object> authorInfo = safeDict(payload.get("authorInfo"));

        String senderName = strField(authorInfo, "name", "displayName");
        String senderUsername = strField(authorInfo, "username", "userName");

        boolean isGroup = !groupId.isBlank();
        boolean requireMention = resolveRequireMention(converseId, groupId);
        boolean mentioned = resolveWasMentioned(payload, config.getAgentUserId());

        if (isGroup && requireMention && !mentioned) {
            if ("non-mention".equalsIgnoreCase(config.getReplyDelayMode())) {
                bufferDelayed(targetKey, buildBufferedEntry(content, author, senderName, senderUsername, messageId, groupId, event));
            }
            return;
        }

        flushOrDispatch(targetKey, buildBufferedEntry(content, author, senderName, senderUsername, messageId, groupId, event), isGroup);
    }

    private MochatBufferedEntry buildBufferedEntry(
            String content,
            String author,
            String senderName,
            String senderUsername,
            String messageId,
            String groupId,
            Map<String, Object> event
    ) {
        MochatBufferedEntry entry = new MochatBufferedEntry();
        entry.rawBody = content;
        entry.author = author;
        entry.senderName = senderName;
        entry.senderUsername = senderUsername;
        entry.messageId = messageId;
        entry.groupId = groupId;
        entry.timestamp = parseTimestamp(event.get("timestamp"));
        return entry;
    }

    private void flushOrDispatch(String targetKey, MochatBufferedEntry entry, boolean isGroup) {
        DelayState state = delayStates.remove(targetKey);
        if (state != null && state.timer != null) {
            state.timer.cancel(false);
        }

        List<MochatBufferedEntry> entries = new ArrayList<>();
        if (state != null) {
            entries.addAll(state.entries);
        }
        entries.add(entry);

        String body = buildBufferedBody(entries, isGroup);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("message_ids", entries.stream().map(e -> e.messageId).toList());

        try {
            handleMessage(
                    entry.author,
                    targetKey,
                    body,
                    new ArrayList<>(),
                    metadata
            );
        } catch (Exception ignored) {
        }
    }

    private void bufferDelayed(String targetKey, MochatBufferedEntry entry) {
        DelayState state = delayStates.computeIfAbsent(targetKey, k -> new DelayState());
        state.lock.lock();
        try {
            state.entries.add(entry);
            if (state.timer != null) {
                state.timer.cancel(false);
            }
            state.timer = scheduler.schedule(
                    () -> flushDelayed(targetKey),
                    config.getReplyDelayMs(),
                    TimeUnit.MILLISECONDS
            );
        } finally {
            state.lock.unlock();
        }
    }

    private void flushDelayed(String targetKey) {
        DelayState state = delayStates.remove(targetKey);
        if (state == null) {
            return;
        }

        state.lock.lock();
        try {
            if (state.entries.isEmpty()) {
                return;
            }

            MochatBufferedEntry last = state.entries.get(state.entries.size() - 1);
            String body = buildBufferedBody(state.entries, !last.groupId.isBlank());

            try {
                handleMessage(
                        last.author,
                        targetKey,
                        body,
                        new ArrayList<>(),
                        Map.of("delayed", true)
                );
            } catch (Exception ignored) {
            }
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * 保存 cursor。
     */
    private void scheduleCursorSave() {
        if (cursorSaveTask != null) {
            cursorSaveTask.cancel(false);
        }
        cursorSaveTask = scheduler.schedule(this::saveSessionCursors, CURSOR_SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void loadSessionCursors() {
        if (!Files.exists(cursorPath)) {
            return;
        }
        try {
            Map<String, Long> loaded = mapper.readValue(cursorPath.toFile(), new TypeReference<>() {});
            sessionCursor.putAll(loaded);
        } catch (Exception ignored) {
        }
    }

    private void saveSessionCursors() {
        try {
            Files.createDirectories(cursorPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(cursorPath.toFile(), sessionCursor);
        } catch (IOException ignored) {
        }
    }

    private void sendSessionMessage(String sessionId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/api/sessions/" + sessionId + "/messages"))
                .header("Authorization", "Bearer " + config.getClawToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("content", content))))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private void sendPanelMessage(String panelId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/api/panels/" + panelId + "/messages"))
                .header("Authorization", "Bearer " + config.getClawToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("content", content))))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private boolean alreadySeen(String targetKey, String messageId) {
        Set<String> set = seenSet.computeIfAbsent(targetKey, k -> ConcurrentHashMap.newKeySet());
        Deque<String> queue = seenQueue.computeIfAbsent(targetKey, k -> new ArrayDeque<>());

        synchronized (queue) {
            if (set.contains(messageId)) {
                return true;
            }
            set.add(messageId);
            queue.addLast(messageId);

            while (queue.size() > MAX_SEEN_MESSAGE_IDS) {
                String old = queue.removeFirst();
                set.remove(old);
            }
            return false;
        }
    }

    private boolean resolveWasMentioned(Map<String, Object> payload, String agentUserId) {
        Map<String, Object> meta = safeDict(payload.get("meta"));
        Object mentioned = meta.get("mentioned");
        Object wasMentioned = meta.get("wasMentioned");

        if (Boolean.TRUE.equals(mentioned) || Boolean.TRUE.equals(wasMentioned)) {
            return true;
        }

        if (agentUserId == null || agentUserId.isBlank()) {
            return false;
        }

        String content = payload.get("content") instanceof String s ? s : "";
        return content.contains("<@" + agentUserId + ">") || content.contains("@" + agentUserId);
    }

    private boolean resolveRequireMention(String sessionId, String groupId) {
        Map<String, GroupRule> groups = config.getGroups() != null ? config.getGroups() : Collections.emptyMap();

        for (String key : List.of(groupId, sessionId, "*")) {
            if (key != null && !key.isBlank() && groups.containsKey(key)) {
                GroupRule rule = groups.get(key);
                return rule != null && rule.isRequireMention();
            }
        }
        return config.getMention() != null && config.getMention().isRequireInGroups();
    }

    private static Map<String, Object> safeDict(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return new HashMap<>();
    }

    private static String strField(Map<String, Object> src, String... keys) {
        for (String k : keys) {
            Object v = src.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return "";
    }

    private static String normalizeMochatContent(Object content) {
        if (content instanceof String s) {
            return s.trim();
        }
        if (content == null) {
            return "";
        }
        try {
            return new ObjectMapper().writeValueAsString(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }

    private static MochatTarget resolveMochatTarget(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isBlank()) {
            return new MochatTarget("", false);
        }

        String lowered = trimmed.toLowerCase(Locale.ROOT);
        String cleaned = trimmed;
        boolean forcedPanel = false;

        for (String prefix : List.of("mochat:", "group:", "channel:", "panel:")) {
            if (lowered.startsWith(prefix)) {
                cleaned = trimmed.substring(prefix.length()).trim();
                forcedPanel = Set.of("group:", "channel:", "panel:").contains(prefix);
                break;
            }
        }

        if (cleaned.isBlank()) {
            return new MochatTarget("", false);
        }

        return new MochatTarget(cleaned, forcedPanel || !cleaned.startsWith("session_"));
    }

    private static String buildBufferedBody(List<MochatBufferedEntry> entries, boolean isGroup) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        if (entries.size() == 1) {
            return entries.get(0).rawBody;
        }

        List<String> lines = new ArrayList<>();
        for (MochatBufferedEntry entry : entries) {
            if (entry.rawBody == null || entry.rawBody.isBlank()) {
                continue;
            }
            if (isGroup) {
                String label = !entry.senderName.isBlank()
                        ? entry.senderName
                        : (!entry.senderUsername.isBlank() ? entry.senderUsername : entry.author);
                if (!label.isBlank()) {
                    lines.add(label + ": " + entry.rawBody);
                    continue;
                }
            }
            lines.add(entry.rawBody);
        }
        return String.join("\n", lines).trim();
    }

    private static Long parseTimestamp(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.replace(" ", "T").replace("Z", "Z")).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}