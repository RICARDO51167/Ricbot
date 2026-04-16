package ricbot.integration.mcp;

import ricbot.infra.config.Config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Transport 工厂类。
 */
public final class MCPTransportFactory {

    private MCPTransportFactory() {
    }

    public static MCPServerConnection connectStdio(Config.MCPServerConfig cfg) {
        if (cfg == null || cfg.getCommand() == null || cfg.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio MCP 需要配置命令");
        }
        return new StdioMcpServerConnection(cfg);
    }

    public static MCPServerConnection connectSse(Config.MCPServerConfig cfg) {
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new IllegalArgumentException("sse MCP 需要配置 URL");
        }
        return new SseMcpServerConnection(cfg);
    }

    public static MCPServerConnection connectStreamableHttp(Config.MCPServerConfig cfg) {
        throw new UnsupportedOperationException("streamableHttp 传输方式尚未实现");
    }

    private static final class StdioMcpServerConnection implements MCPServerConnection {
        private final StdioMcpClientSession session;

        private StdioMcpServerConnection(Config.MCPServerConfig cfg) {
            this.session = new StdioMcpClientSession(cfg);
        }

        @Override
        public MCPClientSession getSession() {
            return session;
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    private static final class StdioMcpClientSession implements MCPClientSession, AutoCloseable {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Config.MCPServerConfig cfg;
        private final AtomicLong idGen = new AtomicLong(1);

        private Process process;
        private BufferedWriter writer;
        private BufferedReader reader;

        private StdioMcpClientSession(Config.MCPServerConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public synchronized void initialize() throws Exception {
            ensureProcess();
            Map<String, Object> params = new HashMap<>();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", Collections.emptyMap());
            params.put("clientInfo", Map.of("name", "ricbot-java", "version", "0.1.0"));

            call("initialize", params, cfg.getToolTimeout());
            sendNotification("notifications/initialized", Collections.emptyMap());
        }

        @Override
        public MCPToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            Map<String, Object> result = call("tools/call", params, cfg.getToolTimeout());
            return MAPPER.convertValue(result, MCPToolResult.class);
        }

        @Override
        public MCPResourceResult readResource(String uri) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("uri", uri);
            Map<String, Object> result = call("resources/read", params, cfg.getToolTimeout());
            return MAPPER.convertValue(result, MCPResourceResult.class);
        }

        @Override
        public MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("name", promptName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            Map<String, Object> result = call("prompts/get", params, cfg.getToolTimeout());
            return MAPPER.convertValue(result, MCPPromptResult.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<MCPToolDefinition> listTools() throws Exception {
            Map<String, Object> result = call("tools/list", Collections.emptyMap(), cfg.getToolTimeout());
            Object tools = result.get("tools");
            if (!(tools instanceof List<?> list)) {
                return List.of();
            }
            List<MCPToolDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPToolDefinition.class));
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<MCPResourceDefinition> listResources() throws Exception {
            Map<String, Object> result = call("resources/list", Collections.emptyMap(), cfg.getToolTimeout());
            Object resources = result.get("resources");
            if (!(resources instanceof List<?> list)) {
                return List.of();
            }
            List<MCPResourceDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPResourceDefinition.class));
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<MCPPromptDefinition> listPrompts() throws Exception {
            Map<String, Object> result = call("prompts/list", Collections.emptyMap(), cfg.getToolTimeout());
            Object prompts = result.get("prompts");
            if (!(prompts instanceof List<?> list)) {
                return List.of();
            }
            List<MCPPromptDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPPromptDefinition.class));
                }
            }
            return out;
        }

        private synchronized void ensureProcess() throws Exception {
            if (process != null && process.isAlive()) {
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.getCommand());
            cmd.addAll(cfg.getArgs() != null ? cfg.getArgs() : List.of());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
                pb.environment().putAll(cfg.getEnv());
            }
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            this.process = pb.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        private synchronized void sendNotification(String method, Map<String, Object> params) throws Exception {
            ensureProcess();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            request.put("params", params);

            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();
        }

        @SuppressWarnings("unchecked")
        private synchronized Map<String, Object> call(String method, Map<String, Object> params, int timeoutSeconds) throws Exception {
            ensureProcess();
            long id = idGen.getAndIncrement();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new java.util.concurrent.TimeoutException("MCP stdio 调用超时: " + method);
                }

                if (!reader.ready()) {
                    Thread.sleep(Math.min(50, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    continue;
                }

                String line = reader.readLine();
                if (line == null) {
                    throw new IllegalStateException("等待 id=" + id + " 时 MCP 服务器关闭了标准输出");
                }
                if (line.trim().isEmpty()) {
                    continue;
                }

                Map<String, Object> response;
                try {
                    response = MAPPER.readValue(line, new TypeReference<>() {});
                } catch (Exception ignored) {
                    continue;
                }

                if (!response.containsKey("id")) {
                    continue;
                }

                Object respId = response.get("id");
                if (respId != null && Long.parseLong(String.valueOf(respId)) == id) {
                    if (response.containsKey("error")) {
                        throw new IllegalStateException("MCP 错误: " + response.get("error"));
                    }
                    Object result = response.get("result");
                    if (result instanceof Map<?, ?> map) {
                        return (Map<String, Object>) map;
                    }
                    return new LinkedHashMap<>();
                }
            }
        }

        @Override
        public synchronized void close() throws Exception {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static final class SseMcpServerConnection implements MCPServerConnection {
        private final SseMcpClientSession session;

        private SseMcpServerConnection(Config.MCPServerConfig cfg) {
            this.session = new SseMcpClientSession(cfg);
        }

        @Override
        public MCPClientSession getSession() {
            return session;
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    private static final class SseMcpClientSession implements MCPClientSession, AutoCloseable {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Config.MCPServerConfig cfg;
        private final HttpClient httpClient;
        private final AtomicLong idGen = new AtomicLong(1);
        private final BlockingQueue<String> inboundJsonLines = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile URI postEndpoint;
        private volatile String endpointWaitError;
        private Thread sseThread;

        private SseMcpClientSession(Config.MCPServerConfig cfg) {
            this.cfg = cfg;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(5, cfg.getToolTimeout())))
                    .build();
        }

        @Override
        public synchronized void initialize() throws Exception {
            ensureSseLoop();
            Map<String, Object> params = new HashMap<>();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", Collections.emptyMap());
            params.put("clientInfo", Map.of("name", "ricbot-java", "version", "0.1.0"));

            call("initialize", params, cfg.getToolTimeout());
            sendNotification("notifications/initialized", Collections.emptyMap());
        }

        @Override
        public MCPToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            Map<String, Object> result = call("tools/call", params, cfg.getToolTimeout());
            return MAPPER.convertValue(result, MCPToolResult.class);
        }

        @Override
        public MCPResourceResult readResource(String uri) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("uri", uri);
            Map<String, Object> result = call("resources/read", params, cfg.getToolTimeout());
            return MAPPER.convertValue(result, MCPResourceResult.class);
        }

        @Override
        public MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("name", promptName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            Map<String, Object> result = call("prompts/get", params, cfg.getToolTimeout());
            return MAPPER.convertValue(result, MCPPromptResult.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<MCPToolDefinition> listTools() throws Exception {
            Map<String, Object> result = call("tools/list", Collections.emptyMap(), cfg.getToolTimeout());
            Object tools = result.get("tools");
            if (!(tools instanceof List<?> list)) {
                return List.of();
            }
            List<MCPToolDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPToolDefinition.class));
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<MCPResourceDefinition> listResources() throws Exception {
            Map<String, Object> result = call("resources/list", Collections.emptyMap(), cfg.getToolTimeout());
            Object resources = result.get("resources");
            if (!(resources instanceof List<?> list)) {
                return List.of();
            }
            List<MCPResourceDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPResourceDefinition.class));
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<MCPPromptDefinition> listPrompts() throws Exception {
            Map<String, Object> result = call("prompts/list", Collections.emptyMap(), cfg.getToolTimeout());
            Object prompts = result.get("prompts");
            if (!(prompts instanceof List<?> list)) {
                return List.of();
            }
            List<MCPPromptDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPPromptDefinition.class));
                }
            }
            return out;
        }

        private synchronized void ensureSseLoop() {
            if (sseThread != null && sseThread.isAlive()) {
                return;
            }
            URI sseUri = parseUri(cfg.getUrl());
            sseThread = new Thread(() -> runSseLoop(sseUri), "mcp-sse-" + System.identityHashCode(this));
            sseThread.setDaemon(true);
            sseThread.start();
        }

        private void runSseLoop(URI sseUri) {
            try {
                HttpRequest req = HttpRequest.newBuilder(sseUri)
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(Math.max(10, cfg.getToolTimeout())))
                        .GET()
                        .build();

                HttpResponse<InputStream> res = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    String snippet;
                    try (InputStream in = res.body()) {
                        byte[] bytes = in.readNBytes(2048);
                        snippet = new String(bytes, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        snippet = "";
                    }
                    endpointWaitError = "MCP SSE 连接失败：status=" + res.statusCode() + " url=" + sseUri + (snippet.isBlank() ? "" : " body=" + snippet);
                    return;
                }
                try (BufferedReader br = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                    String event = null;
                    StringBuilder data = new StringBuilder();
                    while (!closed.get()) {
                        String line = br.readLine();
                        if (line == null) {
                            break;
                        }
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                String payload = data.toString();
                                if ("endpoint".equals(event)) {
                                    try {
                                        postEndpoint = parseEndpointPayload(payload, sseUri);
                                    } catch (Exception e) {
                                        endpointWaitError = "解析 MCP SSE endpoint 失败：payload=" + payload + " url=" + sseUri + " error=" + e.getMessage();
                                    }
                                } else {
                                    inboundJsonLines.offer(payload);
                                }
                            }
                            event = null;
                            data.setLength(0);
                            continue;
                        }
                        if (line.startsWith("event:")) {
                            event = line.substring("event:".length()).trim();
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring("data:".length()).trim());
                        }
                    }
                }
            } catch (Exception e) {
                endpointWaitError = "MCP SSE 连接异常：url=" + sseUri + " error=" + e.getMessage();
            }
        }

        private static URI parseEndpointPayload(String payload, URI sseUri) {
            String p = payload != null ? payload.trim() : "";
            if (p.isBlank()) {
                throw new IllegalArgumentException("empty payload");
            }
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
                p = p.substring(1, p.length() - 1).trim();
            }
            if (p.startsWith("{") && p.endsWith("}")) {
                try {
                    Map<?, ?> map = MAPPER.readValue(p, Map.class);
                    Object v = map.get("endpoint");
                    if (v == null) v = map.get("url");
                    if (v == null) v = map.get("uri");
                    if (v != null) {
                        return resolveEndpointUri(String.valueOf(v), sseUri);
                    }
                } catch (Exception ignored) {
                }
            }
            return resolveEndpointUri(p, sseUri);
        }

        private static URI resolveEndpointUri(String raw, URI sseUri) {
            String normalized = normalizeUriString(raw);
            URI uri;
            try {
                uri = URI.create(normalized);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid endpoint URI: raw=" + raw + ", normalized=" + normalized, e);
            }
            if (uri.getScheme() != null && !uri.getScheme().isBlank()) {
                return uri;
            }
            return sseUri.resolve(uri);
        }

        private static URI parseUri(String raw) {
            String normalized = normalizeUriString(raw);
            URI uri;
            try {
                uri = URI.create(normalized);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid URI: raw=" + raw + ", normalized=" + normalized, e);
            }
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                throw new IllegalArgumentException("URI with undefined scheme: raw=" + raw + ", normalized=" + normalized);
            }
            return uri;
        }

        private static String normalizeUriString(String raw) {
            if (raw == null) {
                return "";
            }
            String s = normalizeWhitespace(raw);
            if (s.length() >= 2) {
                char first = s.charAt(0);
                char last = s.charAt(s.length() - 1);
                if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    s = s.substring(1, s.length() - 1).trim();
                }
            }
            s = normalizeWhitespace(s.replace("`", ""));
            return s;
        }

        private static String normalizeWhitespace(String raw) {
            if (raw == null) {
                return "";
            }
            String s = raw
                    .replace('\u00A0', ' ')
                    .replace("\u200B", "")
                    .replace("\uFEFF", "");
            return s.trim();
        }


        private void sendNotification(String method, Map<String, Object> params) throws Exception {
            postJson(Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params
            ), cfg.getToolTimeout());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> call(String method, Map<String, Object> params, int timeoutSeconds) throws Exception {
            ensureSseLoop();
            long id = idGen.getAndIncrement();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            postJson(request, timeoutSeconds);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new java.util.concurrent.TimeoutException("MCP sse 调用超时: " + method);
                }

                String line = inboundJsonLines.poll(Math.min(500, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }

                Map<String, Object> response;
                try {
                    response = MAPPER.readValue(line, new TypeReference<>() {});
                } catch (Exception ignored) {
                    continue;
                }

                if (!response.containsKey("id")) {
                    continue;
                }

                Object respId = response.get("id");
                if (respId != null && Long.parseLong(String.valueOf(respId)) == id) {
                    if (response.containsKey("error")) {
                        throw new IllegalStateException("MCP 错误: " + response.get("error"));
                    }
                    Object result = response.get("result");
                    if (result instanceof Map<?, ?> map) {
                        return (Map<String, Object>) map;
                    }
                    return new LinkedHashMap<>();
                }
            }
        }

        private void postJson(Object request, int timeoutSeconds) throws Exception {
            URI endpoint = waitForPostEndpoint(timeoutSeconds);
            byte[] body = MAPPER.writeValueAsBytes(request);
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        }

        private URI waitForPostEndpoint(int timeoutSeconds) throws Exception {
            URI endpoint = postEndpoint;
            if (endpoint != null) {
                return endpoint;
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (endpoint == null) {
                String err = endpointWaitError;
                if (err != null && !err.isBlank()) {
                    throw new IllegalStateException(err);
                }
                if (System.nanoTime() > deadline) {
                    throw new java.util.concurrent.TimeoutException("未收到 MCP SSE 端点");
                }
                Thread.sleep(50);
                endpoint = postEndpoint;
            }
            return endpoint;
        }

        @Override
        public void close() {
            closed.set(true);
            if (sseThread != null) {
                try {
                    sseThread.interrupt();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
