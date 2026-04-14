package ricbot.transport.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket server 渠道。
 *
 * 对应 Python: websocket.py
 *
 * 主要职责：
 * 1. nanobot 自己作为 WebSocket 服务端
 * 2. 支持 client_id / token 握手校验
 * 3. 支持 token issue route
 * 4. 每个连接映射独立 session
 * 5. 支持 streaming outbound
 *
 * 说明：
 * 这里为了不绑定特定第三方 Java WebSocket 库，先通过接口抽象掉 server/connection。
 */
public class WebSocketChannel extends BaseChannel {

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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = normalizeConfigPath(path); }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getTokenIssuePath() { return tokenIssuePath; }
        public void setTokenIssuePath(String tokenIssuePath) { this.tokenIssuePath = normalizeConfigPath(tokenIssuePath); }
        public String getTokenIssueSecret() { return tokenIssueSecret; }
        public void setTokenIssueSecret(String tokenIssueSecret) { this.tokenIssueSecret = tokenIssueSecret; }
        public int getTokenTtlS() { return tokenTtlS; }
        public void setTokenTtlS(int tokenTtlS) { this.tokenTtlS = tokenTtlS; }
        public boolean isWebsocketRequiresToken() { return websocketRequiresToken; }
        public void setWebsocketRequiresToken(boolean websocketRequiresToken) { this.websocketRequiresToken = websocketRequiresToken; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public boolean isStreaming() { return streaming; }
        public void setStreaming(boolean streaming) { this.streaming = streaming; }
        public int getMaxMessageBytes() { return maxMessageBytes; }
        public void setMaxMessageBytes(int maxMessageBytes) { this.maxMessageBytes = maxMessageBytes; }
        public double getPingIntervalS() { return pingIntervalS; }
        public void setPingIntervalS(double pingIntervalS) { this.pingIntervalS = pingIntervalS; }
        public double getPingTimeoutS() { return pingTimeoutS; }
        public void setPingTimeoutS(double pingTimeoutS) { this.pingTimeoutS = pingTimeoutS; }
        public String getSslCertfile() { return sslCertfile; }
        public void setSslCertfile(String sslCertfile) { this.sslCertfile = sslCertfile; }
        public String getSslKeyfile() { return sslKeyfile; }
        public void setSslKeyfile(String sslKeyfile) { this.sslKeyfile = sslKeyfile; }
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

    private static class IssuedToken {
        String token;
        long expiresAtMillis;
    }

    private final WebSocketConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private WsServer server;

    /**
     * client_id -> connection
     */
    private final Map<String, WsConnection> connections = new ConcurrentHashMap<>();

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
        if (server == null) {
            throw new IllegalStateException("WsServer not injected");
        }
        running = true;
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
                connections.values().removeIf(c -> Objects.equals(c.id(), connection.id()));
            }

            @Override
            public HttpResponseData onHttpGet(String pathWithQuery, Map<String, String> headers) {
                return handleHttpGet(pathWithQuery, headers);
            }
        });
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (server != null) {
            server.stop();
        }
        connections.clear();
        issuedTokens.clear();
    }

    public void send(OutboundMessage msg) throws Exception {
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

        connection.sendText(mapper.writeValueAsString(payload));
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

        connection.sendText(mapper.writeValueAsString(payload));
    }

    private void handleOpen(WsConnection connection, String pathWithQuery, Map<String, String> headers) {
        ParsedRequest req = parseRequestPath(pathWithQuery);

        if (!expectedPath().equals(req.path)) {
            try {
                connection.close(1008, "Invalid path");
            } catch (Exception ignored) {
            }
            return;
        }

        String clientId = firstQuery(req.query, "client_id");
        if (clientId == null || clientId.isBlank()) {
            clientId = "client-" + UUID.randomUUID().toString().substring(0, 8);
        }

        if (!isAllowed(clientId)) {
            try {
                connection.close(1008, "Not allowed");
            } catch (Exception ignored) {
            }
            return;
        }

        String tokenValue = firstQuery(req.query, "token");
        if (!validateConnectionToken(tokenValue)) {
            try {
                connection.close(1008, "Invalid token");
            } catch (Exception ignored) {
            }
            return;
        }

        connections.put(clientId, connection);
    }

    private void handleInbound(WsConnection connection, String raw) {
        try {
            String text = parseInboundPayload(raw);
            if (text == null || text.isBlank()) {
                return;
            }

            String clientId = connection.clientId();
            if (clientId == null || clientId.isBlank()) {
                clientId = "anonymous";
            }

            String sessionKey = "websocket:" + clientId;

            handleMessage(
                    clientId,
                    clientId,
                    text,
                    new ArrayList<>(),
                    new HashMap<>(),
                    sessionKey
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private HttpResponseData handleHttpGet(String pathWithQuery, Map<String, String> headers) {
        ParsedRequest parsed = parseRequestPath(pathWithQuery);

        if (config.getTokenIssuePath() != null
                && !config.getTokenIssuePath().isBlank()
                && config.getTokenIssuePath().equals(parsed.path)) {

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
            return true;
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
                Map<String, Object> data = new ObjectMapper().readValue(text, new TypeReference<>() {});
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
            res.body = new ObjectMapper().writeValueAsString(data);
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
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
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
        return s.replace("+", " ");
    }

    private static class ParsedRequest {
        final String path;
        final Map<String, List<String>> query;

        ParsedRequest(String path, Map<String, List<String>> query) {
            this.path = path;
            this.query = query;
        }
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}