package ricbot.transport.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.integration.api.RicbotApiServer;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RicbotApiServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void chatCompletions_supportsMultiRoleMessages(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "session_id", "t1",
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are a helper."),
                            Map.of("role", "user", "content", "Earlier question"),
                            Map.of("role", "assistant", "content", "Earlier answer"),
                            Map.of("role", "tool", "tool_call_id", "call_1", "name", "read_file", "content", "tool output"),
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            handler.handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            List<?> choices = (List<?>) json.get("choices");
            assertNotNull(choices);
            Map<?, ?> choice0 = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice0.get("message");
            assertEquals("assistant", String.valueOf(message.get("role")));
            assertEquals("pong", String.valueOf(message.get("content")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_streamTrue_returnsSsePayload(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "session_id", "t2",
                    "stream", true,
                    "messages", List.of(
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            handler.handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            String body = exchange.responseText();
            assertTrue(body.contains("data: "), body);
            assertTrue(body.contains("\"object\":\"chat.completion.chunk\""), body);
            assertTrue(body.contains("[DONE]"), body);
            assertTrue(body.contains("\"finish_reason\":\"stop\""), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_invalidRole_returnsError(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(
                            Map.of("role", "developer", "content", "x"),
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            handler.handle(exchange);

            assertEquals(400, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            assertEquals("invalid_request_error", String.valueOf(err.get("type")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_timeout_returnsTimeoutError(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 200);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 50, "127.0.0.1", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            handler.handle(exchange);

            assertEquals(504, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            assertEquals("timeout_error", String.valueOf(err.get("type")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_blankResponseFallsBackAfterRetry(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildBlankLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));

            handler.handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            List<?> choices = (List<?>) json.get("choices");
            Map<?, ?> choice0 = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice0.get("message");
            assertEquals("assistant", String.valueOf(message.get("role")));
            assertEquals("处理已完成，但无响应可提供。", String.valueOf(message.get("content")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_nonLoopbackRequiresBearerToken(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "0.0.0.0", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));

            handler.handle(exchange);

            assertEquals(401, exchange.getResponseCode(), exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_bearerTokenProtectsLocalhostWhenConfigured(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "demo-secret");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange unauthorized = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));
            handler.handle(unauthorized);
            assertEquals(401, unauthorized.getResponseCode(), unauthorized.responseText());

            TestExchange authorized = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));
            authorized.getRequestHeaders().set("Authorization", "Bearer demo-secret");
            handler.handle(authorized);
            assertEquals(200, authorized.getResponseCode(), authorized.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void createAndStart_rejectsPublicBindWithoutBearerToken(@TempDir Path workspace) {
        AgentLoop loop = buildLoop(workspace);
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    RicbotApiServer.createAndStart("0.0.0.0", 0, loop, "gpt-4o-mini", 20_000, "")
            );
            assertTrue(ex.getMessage().contains("api.bearer_token"), ex.getMessage());
        } finally {
            loop.stop();
        }
    }

    @Test
    void webUiHandler_servesIndexAndRejectsUnknownPaths() throws Exception {
        var handler = new RicbotApiServer.WebUiHandler();

        TestExchange index = getExchange("/");
        handler.handle(index);
        assertEquals(200, index.getResponseCode(), index.responseText());
        assertTrue(index.getResponseHeaders().getFirst("Content-Type").startsWith("text/html"), index.getResponseHeaders().toString());
        assertTrue(index.responseText().contains("<title>Ricbot</title>"), index.responseText());
        assertTrue(index.responseText().contains("/v1/chat/completions"), index.responseText());

        TestExchange missing = getExchange("/missing.js");
        handler.handle(missing);
        assertEquals(404, missing.getResponseCode(), missing.responseText());
    }

    @Test
    void sessionsTrace_returnsLastRunTrace(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var chatHandler = new RicbotApiServer.ChatCompletionsHandler(app);
        var sessionsHandler = new RicbotApiServer.SessionsHandler(app);

        try {
            TestExchange chat = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "session_id", "trace-test",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));
            chatHandler.handle(chat);
            assertEquals(200, chat.getResponseCode(), chat.responseText());

            TestExchange trace = getExchange("/v1/sessions/trace-test/trace");
            sessionsHandler.handle(trace);
            assertEquals(200, trace.getResponseCode(), trace.responseText());
            Map<String, Object> json = MAPPER.readValue(trace.responseText(), new TypeReference<>() {});
            assertEquals("trace-test", String.valueOf(json.get("session_id")));
            Map<String, Object> runTrace = (Map<String, Object>) json.get("run_trace");
            assertEquals("stop", String.valueOf(runTrace.get("stop_reason")));
            List<?> events = (List<?>) runTrace.get("events");
            assertFalse(events.isEmpty(), trace.responseText());
            Map<String, Object> contextTrace = (Map<String, Object>) json.get("context_trace");
            assertEquals("interactive", String.valueOf(contextTrace.get("mode")));
            assertTrue(contextTrace.containsKey("prompt_context_budget"), trace.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void mcpHandler_returnsDashboard(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.McpHandler(app);

        try {
            TestExchange exchange = getExchange("/v1/mcp");
            handler.handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            assertTrue(json.containsKey("configured_count"), exchange.responseText());
            assertTrue(json.containsKey("servers"), exchange.responseText());
            assertTrue(json.containsKey("tools"), exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void memoryHandler_returnsGovernanceReport(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiServer.ApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.MemoryHandler(app);

        try {
            TestExchange exchange = getExchange("/v1/memory");
            handler.handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            assertTrue(json.containsKey("entries_count"), exchange.responseText());
            assertTrue(json.containsKey("candidates"), exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    private static TestExchange postExchange(String path, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        return new TestExchange("POST", URI.create("http://localhost" + path), json);
    }

    private static TestExchange getExchange(String path) {
        return new TestExchange("GET", URI.create("http://localhost" + path), "");
    }

    private static AgentLoop buildLoop(Path workspace) {
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(workspace);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse().setContent("pong").setFinishReason("stop");
            }
        };

        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        Config.DreamConfig dreamConfig = new Config.DreamConfig();
        dreamConfig.setEnabled(false);

        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                2000,
                50,
                10_000,
                "standard",
                web,
                exec,
                Map.of(),
                true,
                sessionManager,
                "UTC",
                false,
                List.of(),
                0,
                dreamConfig
        );
        loop.start();
        return loop;
    }

    private static AgentLoop buildSlowLoop(Path workspace, long sleepMs) {
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(workspace);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new LLMResponse().setContent("pong").setFinishReason("stop");
            }
        };

        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        Config.DreamConfig dreamConfig = new Config.DreamConfig();
        dreamConfig.setEnabled(false);

        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                2000,
                50,
                10_000,
                "standard",
                web,
                exec,
                Map.of(),
                true,
                sessionManager,
                "UTC",
                false,
                List.of(),
                0,
                dreamConfig
        );
        loop.start();
        return loop;
    }

    private static AgentLoop buildBlankLoop(Path workspace) {
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(workspace);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse().setContent("").setFinishReason("stop");
            }
        };

        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        Config.DreamConfig dreamConfig = new Config.DreamConfig();
        dreamConfig.setEnabled(false);

        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                2000,
                50,
                10_000,
                "standard",
                web,
                exec,
                Map.of(),
                true,
                sessionManager,
                "UTC",
                false,
                List.of(),
                0,
                dreamConfig
        ) {
            @Override
            public OutboundMessage processDirect(String content, String sessionKey, String channel, String chatId) {
                return new OutboundMessage(channel, chatId, "");
            }

            @Override
            public OutboundMessage processDirect(
                    String content,
                    String sessionKey,
                    String channel,
                    String chatId,
                    Map<String, Object> metadata,
                    List<AgentHook> requestHooks
            ) {
                return new OutboundMessage(channel, chatId, "");
            }
        };
        loop.start();
        return loop;
    }

    private static final class TestExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final URI requestUri;
        private final String method;
        private InputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Map<String, Object> attributes = new HashMap<>();
        private int responseCode = -1;
        private long responseLength = -1;

        private TestExchange(String method, URI requestUri, String body) {
            this.method = method;
            this.requestUri = requestUri;
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        String responseText() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return requestUri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
            this.responseLength = responseLength;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            this.requestBody = i;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
