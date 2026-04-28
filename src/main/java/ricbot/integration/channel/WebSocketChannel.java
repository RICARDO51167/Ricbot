package ricbot.integration.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.integration.channel.event.CommandEvent;
import ricbot.integration.channel.event.IncomingMessageEvent;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket server 渠道。
 *
 * 对应 Python: websocket.py
 *
 * 主要职责：
 * 1. ricbot 自己作为 WebSocket 服务端
 * 2. 支持 client_id / token 握手校验
 * 3. 支持 token issue route
 * 4. 每个连接映射独立 session
 * 5. 支持 streaming outbound
 *
 * 说明：
 * 这里为了不绑定特定第三方 Java WebSocket 库，先通过接口抽象掉 server/connection。
 */
@Slf4j
public class WebSocketChannel extends BaseChannel {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Data
    public static class WebSocketConfig {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 8765;
        private String path = "/";
        private String token = "";
        private String tokenIssuePath = "";
        private String tokenIssueSecret = "";
        private int tokenTtlS = 300;
        private boolean websocketRequiresToken = true;
        private List<String> allowFrom = new ArrayList<>(List.of("*"));
        private boolean streaming = true;
        private int maxMessageBytes = 1_048_576;
        private double pingIntervalS = 20.0;
        private double pingTimeoutS = 20.0;
        private String sslCertfile = "";
        private String sslKeyfile = "";

        public void setPath(String path) {
            this.path = normalizeConfigPath(path);
        }

        public void setTokenIssuePath(String tokenIssuePath) {
            this.tokenIssuePath = normalizeConfigPath(tokenIssuePath);
        }
    }

    public interface WsConnection {
        String id();
        String clientId();
        void sendText(String text) throws Exception;
        void close(int code, String reason) throws Exception;
    }

    public interface WsServer {
        void start(String host, int port, String path, WsServerListener listener) throws Exception;
        void stop() throws Exception;

        default boolean supportsHttpGet() {
            return false;
        }
    }

    public interface WsServerListener {
        void onOpen(WsConnection connection, String pathWithQuery, Map<String, String> headers);
        void onMessage(WsConnection connection, String text);
        void onClose(WsConnection connection, int code, String reason);
        HttpResponseData onHttpGet(String pathWithQuery, Map<String, String> headers);
    }

    public static class HttpResponseData {
        public int status;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String body;
    }

    private final WebSocketConfig config;
    private WsServer server;

    /**
     * client_id -> connection
     */
    private final Map<String, WsConnection> connections = new ConcurrentHashMap<>();
    /**
     * connection_id -> client_id
     */
    private final Map<String, String> connectionClientIds = new ConcurrentHashMap<>();

    /**
     * token -> expiryMillis
     */
    private final Map<String, Long> issuedTokens = new ConcurrentHashMap<>();

    public WebSocketChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "websocket";
        this.displayName = "WebSocket";
        this.config = (config instanceof WebSocketConfig c) ? c : new WebSocketConfig();
    }

    public void setServer(WsServer server) {
        this.server = server;
    }
    @Override
    public void start() throws Exception {
        if (running) {
            return;
        }
        if (server == null) {
            server = new JavaWebSocketServer(new InetSocketAddress(config.getHost(), config.getPort()));
        }
        validateTokenIssueRoute(server);
        try {
            server.start(config.getHost(), config.getPort(), expectedPath(), new WsServerListener() {
                @Override
                public void onOpen(WsConnection connection, String pathWithQuery, Map<String, String> headers) {
                    handleOpen(connection, pathWithQuery, headers);
                }

                @Override
                public void onMessage(WsConnection connection, String text) {
                    handleInbound(connection, text);
                }

                @Override
                public void onClose(WsConnection connection, int code, String reason) {
                    unregisterConnection(connection);
                }

                @Override
                public HttpResponseData onHttpGet(String pathWithQuery, Map<String, String> headers) {
                    return handleHttpGet(pathWithQuery, headers);
                }
            });
            running = true;
        } catch (Exception e) {
            running = false;
            throw e;
        }
    }

    private void validateTokenIssueRoute(WsServer server) {
        if (!hasTokenIssueRoute()) {
            return;
        }
        if (config.getTokenIssueSecret() == null || config.getTokenIssueSecret().isBlank()) {
            throw new IllegalStateException("websocket.token_issue_secret 不能为空");
        }
        if (server == null || !server.supportsHttpGet()) {
            throw new IllegalStateException("当前 WebSocket server 不支持 token_issue_path 的 HTTP GET 路由");
        }
    }

    @Override
    public void stop() throws Exception {
        if (!running) {
            return;
        }
        running = false;
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        connections.clear();
        connectionClientIds.clear();
        issuedTokens.clear();
    }

    public void send(OutboundMessage msg) throws Exception {
        if (msg == null) {
            return;
        }
        WsConnection connection = connections.get(msg.getChatId());
        if (connection == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("content", msg.getContent());
        payload.put("media", msg.getMedia() != null ? msg.getMedia() : new ArrayList<>());
        payload.put("metadata", msg.getMetadata() != null ? msg.getMetadata() : new HashMap<>());
        payload.put("timestamp", Instant.now().toString());

        connection.sendText(JSON.writeValueAsString(payload));
    }

    @Override
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        WsConnection connection = connections.get(chatId);
        if (connection == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "delta");
        payload.put("delta", delta);
        payload.put("metadata", metadata != null ? metadata : new HashMap<>());
        payload.put("timestamp", Instant.now().toString());

        connection.sendText(JSON.writeValueAsString(payload));
    }

    private boolean isAllowed(String clientId) {
        List<String> allow = config.getAllowFrom();
        if (allow == null || allow.isEmpty() || allow.contains("*")) {
            return true;
        }
        return allow.contains(clientId);
    }

    private void handleOpen(WsConnection connection, String pathWithQuery, Map<String, String> headers) {
        ParsedRequest req = parseRequestPath(pathWithQuery);

        if (!expectedPath().equals(req.path)) {
            rejectConnection(connection, "路径无效");
            return;
        }

        String clientId = firstQuery(req.query, "client_id");
        if (clientId == null || clientId.isBlank()) {
            clientId = "client-" + UUID.randomUUID().toString().substring(0, 8);
        }

        if (!isAllowed(clientId)) {
            rejectConnection(connection, "不允许");
            return;
        }

        String tokenValue = firstQuery(req.query, "token");
        if (!validateConnectionToken(tokenValue)) {
            rejectConnection(connection, "Token 无效");
            return;
        }

        registerConnection(clientId, connection);
    }

    private void handleInbound(WsConnection connection, String raw) {
        try {
            String text = parseInboundPayload(raw);
            if (text == null || text.isBlank()) {
                return;
            }

            String clientId = resolveClientId(connection);
            if (clientId == null || clientId.isBlank()) {
                clientId = "anonymous";
            }

            String sessionKey = "websocket:" + clientId;

            ChannelParsedCommand parsed = parseCommand(text);
            if (parsed != null) {
                publishEvent(new CommandEvent(
                        getName(),
                        clientId,
                        clientId,
                        clientId,
                        parsed.command(),
                        parsed.args(),
                        Map.of(),
                        sessionKey,
                        null,
                        LocalDateTime.now()
                ));
            } else {
                publishEvent(new IncomingMessageEvent(
                        getName(),
                        clientId,
                        clientId,
                        clientId,
                        text,
                        List.of(),
                        Map.of(),
                        sessionKey,
                        null,
                        LocalDateTime.now()
                ));
            }
        } catch (Exception e) {
            log.error("处理 WebSocket 入站消息失败: connectionId={}, raw={}", connection != null ? connection.id() : "null", raw, e);
        }
    }

    private static ChannelParsedCommand parseCommand(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (!s.startsWith("/")) {
            return null;
        }
        s = s.substring(1).trim();
        if (s.isEmpty()) {
            return null;
        }
        int idx = s.indexOf(' ');
        if (idx < 0) {
            return new ChannelParsedCommand(s, "");
        }
        return new ChannelParsedCommand(s.substring(0, idx).trim(), s.substring(idx + 1).trim());
    }

    private record ChannelParsedCommand(String command, String args) {
    }

    private void registerConnection(String clientId, WsConnection connection) {
        WsConnection previous = connections.put(clientId, connection);
        if (previous != null) {
            connectionClientIds.remove(previous.id());
        }
        connectionClientIds.put(connection.id(), clientId);
    }

    private void unregisterConnection(WsConnection connection) {
        if (connection == null) {
            return;
        }
        String clientId = connectionClientIds.remove(connection.id());
        if (clientId != null) {
            connections.remove(clientId, connection);
            return;
        }
        connections.values().removeIf(c -> Objects.equals(c.id(), connection.id()));
    }

    private String resolveClientId(WsConnection connection) {
        if (connection == null) {
            return null;
        }
        String clientId = connection.clientId();
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        return connectionClientIds.get(connection.id());
    }

    private void rejectConnection(WsConnection connection, String reason) {
        if (connection == null) {
            return;
        }
        try {
            connection.close(1008, reason);
        } catch (Exception e) {
            log.debug("关闭 WebSocket 连接失败: connectionId={}, reason={}", connection.id(), reason, e);
        }
    }

    private HttpResponseData handleHttpGet(String pathWithQuery, Map<String, String> headers) {
        ParsedRequest parsed = parseRequestPath(pathWithQuery);

        if (isTokenIssuePath(parsed.path)) {

            if (!issueRouteSecretMatches(headers, config.getTokenIssueSecret())) {
                return httpJsonResponse(Map.of("error", "unauthorized"), 401);
            }

            purgeExpiredIssuedTokens();

            String token = UUID.randomUUID().toString().replace("-", "");
            long expiresAt = System.currentTimeMillis() + config.getTokenTtlS() * 1000L;
            issuedTokens.put(token, expiresAt);

            return httpJsonResponse(Map.of(
                    "token", token,
                    "expires_in", config.getTokenTtlS()
            ), 200);
        }

        return httpJsonResponse(Map.of("error", "not_found"), 404);
    }

    private boolean isTokenIssuePath(String path) {
        return config.getTokenIssuePath() != null
                && !config.getTokenIssuePath().isBlank()
                && config.getTokenIssuePath().equals(path);
    }

    private boolean hasTokenIssueRoute() {
        return config.getTokenIssuePath() != null && !config.getTokenIssuePath().isBlank();
    }

    private boolean validateConnectionToken(String tokenValue) {
        if (!config.isWebsocketRequiresToken()) {
            return true;
        }

        if (config.getToken() != null && !config.getToken().isBlank()
                && config.getToken().equals(tokenValue)) {
            return true;
        }

        return takeIssuedTokenIfValid(tokenValue);
    }

    private void purgeExpiredIssuedTokens() {
        long now = System.currentTimeMillis();
        issuedTokens.entrySet().removeIf(e -> now > e.getValue());
    }

    private boolean takeIssuedTokenIfValid(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return false;
        }

        purgeExpiredIssuedTokens();
        Long expiry = issuedTokens.remove(tokenValue);
        return expiry != null && System.currentTimeMillis() <= expiry;
    }

    private static boolean issueRouteSecretMatches(Map<String, String> headers, String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            return false;
        }

        String authorization = header(headers, "Authorization");
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            String supplied = authorization.substring(7).trim();
            return configuredSecret.equals(supplied);
        }

        String alt = header(headers, "X-Nanobot-Auth");
        return alt != null && configuredSecret.equals(alt.trim());
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String parseInboundPayload(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isBlank()) return null;

        if (text.startsWith("{")) {
            try {
                Map<String, Object> data = JSON.readValue(text, new TypeReference<>() {});
                for (String key : List.of("content", "text", "message")) {
                    Object value = data.get(key);
                    if (value instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
                return null;
            } catch (Exception e) {
                return text;
            }
        }
        return text;
    }

    private static HttpResponseData httpJsonResponse(Map<String, Object> data, int status) {
        HttpResponseData res = new HttpResponseData();
        res.status = status;
        res.headers.put("Content-Type", "application/json; charset=utf-8");
        try {
            res.body = JSON.writeValueAsString(data);
        } catch (Exception e) {
            res.body = "{\"error\":\"serialization_failed\"}";
        }
        return res;
    }

    private String expectedPath() {
        return normalizeConfigPath(config.getPath());
    }

    private static String normalizeConfigPath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static ParsedRequest parseRequestPath(String pathWithQuery) {
        try {
            URI uri = URI.create("ws://x" + pathWithQuery);
            String path = uri.getPath();
            path = normalizeConfigPath(path);

            Map<String, List<String>> query = new LinkedHashMap<>();
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (String part : rawQuery.split("&")) {
                    int idx = part.indexOf('=');
                    String key = idx >= 0 ? decode(part.substring(0, idx)) : decode(part);
                    String value = idx >= 0 ? decode(part.substring(idx + 1)) : "";
                    query.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }

            return new ParsedRequest(path, query);
        } catch (Exception e) {
            return new ParsedRequest("/", new LinkedHashMap<>());
        }
    }

    private static String firstQuery(Map<String, List<String>> query, String key) {
        List<String> values = query.get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private record ParsedRequest(String path, Map<String, List<String>> query) {
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}
