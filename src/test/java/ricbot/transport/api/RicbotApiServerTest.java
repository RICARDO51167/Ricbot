package ricbot.transport.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.MessageBus;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.api.RicbotApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RicbotApiServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void chatCompletions_supportsMultiRoleMessages(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        HttpServer server = RicbotApiServer.createAndStart(0, loop, "gpt-4o-mini", 20_000);
        int port = server.getAddress().getPort();

        try {
            String body = MAPPER.writeValueAsString(Map.of(
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

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, res.statusCode(), res.body());

            Map<String, Object> json = MAPPER.readValue(res.body(), new TypeReference<>() {});
            List<?> choices = (List<?>) json.get("choices");
            assertNotNull(choices);
            Map<?, ?> choice0 = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice0.get("message");
            assertEquals("assistant", String.valueOf(message.get("role")));
            assertEquals("pong", String.valueOf(message.get("content")));
        } finally {
            server.stop(0);
            loop.stop();
        }
    }

    @Test
    void chatCompletions_streamTrue_returnsInvalidRequest(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        HttpServer server = RicbotApiServer.createAndStart(0, loop, "gpt-4o-mini", 20_000);
        int port = server.getAddress().getPort();

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", "gpt-4o-mini",
                    "session_id", "t2",
                    "stream", true,
                    "messages", List.of(
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(400, res.statusCode(), res.body());
            Map<String, Object> json = MAPPER.readValue(res.body(), new TypeReference<>() {});
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            assertEquals("invalid_request_error", String.valueOf(err.get("type")));
            assertTrue(String.valueOf(err.get("message")).contains("stream"), String.valueOf(err.get("message")));
        } finally {
            server.stop(0);
            loop.stop();
        }
    }

    @Test
    void chatCompletions_invalidRole_returnsError(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        HttpServer server = RicbotApiServer.createAndStart(0, loop, "gpt-4o-mini", 20_000);
        int port = server.getAddress().getPort();

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(
                            Map.of("role", "developer", "content", "x"),
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(400, res.statusCode(), res.body());

            Map<String, Object> json = MAPPER.readValue(res.body(), new TypeReference<>() {});
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            assertEquals("invalid_request_error", String.valueOf(err.get("type")));
        } finally {
            server.stop(0);
            loop.stop();
        }
    }

    @Test
    void chatCompletions_timeout_returnsTimeoutError(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 200);
        HttpServer server = RicbotApiServer.createAndStart(0, loop, "gpt-4o-mini", 50);
        int port = server.getAddress().getPort();

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(
                            Map.of("role", "user", "content", "ping")
                    )
            ));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(504, res.statusCode(), res.body());

            Map<String, Object> json = MAPPER.readValue(res.body(), new TypeReference<>() {});
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            assertEquals("timeout_error", String.valueOf(err.get("type")));
        } finally {
            server.stop(0);
            loop.stop();
        }
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
}
