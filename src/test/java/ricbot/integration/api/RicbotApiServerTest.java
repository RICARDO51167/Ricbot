package ricbot.integration.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceSkillPromoter;
import ricbot.domain.experience.ExperienceStatus;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceType;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.security.ApprovalApplicationService;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.session.SessionManager;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.infra.config.Config;
import ricbot.integration.api.console.ConsoleActionAuditService;
import ricbot.integration.api.console.ConsoleEvent;
import ricbot.integration.api.console.ConsoleController;
import ricbot.integration.api.console.JsonlConsoleEventStore;
import ricbot.integration.api.webhook.ChannelWebhookController;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class RicbotApiServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void chatCompletions_supportsMultiRoleMessages(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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
            Map<?, ?> err = (Map<?, ?>) json.get("error");
            assertEquals("invalid_request_error", String.valueOf(err.get("type")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_rejectsOversizedRequestBody(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
        var handler = new RicbotApiServer.ChatCompletionsHandler(app);

        try {
            TestExchange exchange = postExchangeRaw("/v1/chat/completions", " ".repeat(2 * 1024 * 1024 + 1));

            handler.handle(exchange);

            assertEquals(413, exchange.getResponseCode(), exchange.responseText());
            Map<String, Object> json = MAPPER.readValue(exchange.responseText(), new TypeReference<>() {});
            Map<?, ?> err = (Map<?, ?>) json.get("error");
            assertEquals("invalid_request_error", String.valueOf(err.get("type")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_timeout_returnsTimeoutError(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 200);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 50, "127.0.0.1", "");
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
            Map<?, ?> err = (Map<?, ?>) json.get("error");
            assertEquals("timeout_error", String.valueOf(err.get("type")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void chatCompletions_blankResponseFallsBackAfterRetry(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildBlankLoop(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "0.0.0.0", "");
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
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "demo-secret");
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
        var handler = new RicbotWebUiHandler();

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
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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
            Map<?, ?> runTrace = (Map<?, ?>) json.get("run_trace");
            assertEquals("stop", String.valueOf(runTrace.get("stop_reason")));
            List<?> events = (List<?>) runTrace.get("events");
            assertFalse(events.isEmpty(), trace.responseText());
            Map<?, ?> contextTrace = (Map<?, ?>) json.get("context_trace");
            assertEquals("interactive", String.valueOf(contextTrace.get("mode")));
            assertTrue(contextTrace.containsKey("prompt_context_budget"), trace.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void mcpHandler_returnsDashboard(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "");
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

    @Test
    void consoleEndpoints_returnReadOnlyDashboardDataAndKeepApiRoutesWorking(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("gpt-4o-mini");
        config.getProviders().getOpenai().setApiKey("super-secret-key");
        config.getTools().setMcpServers(Map.of(
                "demo", Map.of(
                        "type", "stdio",
                        "command", "node",
                        "args", List.of("--token", "secret-token-value"),
                        "env", Map.of("API_TOKEN", "secret-env-token", "PLAIN_ENV", "visible-value"),
                        "enabled_tools", List.of("echo")
                )
        ));
        Path configPath = workspace.resolve("ricbot.config.json");
        Files.writeString(configPath, """
                {
                  "agents": {"defaults": {"workspace": "%s", "model": "gpt-4o-mini"}},
                  "providers": {"openai": {"api_key": "super-secret-key"}},
                  "tools": {"restrictToWorkspace": true, "web": {"enable": false}, "exec": {"enable": false}}
                }
                """.formatted(workspace.toString().replace("\\", "\\\\")));

        try {
            var app = new RicbotApiAppContext(
                    loop,
                    "gpt-4o-mini",
                    20_000,
                    "",
                    "",
                    config,
                    configPath,
                    workspace
            );

            TestExchange consoleExchange = getExchange("/console");
            ConsoleController.pageHandler(app).handle(consoleExchange);
            assertEquals(200, consoleExchange.getResponseCode(), consoleExchange.responseText());
            String console = consoleExchange.responseText();
            assertTrue(console.contains("Ricbot 控制台"), console);
            assertTrue(console.contains("中文"), console);
            assertTrue(console.contains("English"), console);
            assertTrue(console.contains("ricbot_console_lang"), console);
            assertTrue(console.contains("演示流程"), console);
            assertTrue(console.contains("/console/api/config-doctor"), console);
            assertTrue(console.contains("/console/api/release-check"), console);

            String health = handleGet(ConsoleController.healthHandler(app), "/console/api/health");
            assertTrue(health.contains("\"status\":\"ok\""), health);
            assertTrue(health.contains("\"readonly\":true"), health);

            String runtime = handleGet(ConsoleController.runtimeHandler(app), "/api/console/runtime");
            assertTrue(runtime.contains("\"mode\":\"backend\""), runtime);
            assertTrue(runtime.contains("\"modelConfigured\":true"), runtime);
            assertTrue(runtime.contains("\"provider\":\"openai\""), runtime);
            assertTrue(runtime.contains("\"model\":\"gpt-4o-mini\""), runtime);
            assertFalse(runtime.contains("super-secret-key"), runtime);

            String sessions = handleGet(ConsoleController.sessionsHandler(app), "/api/console/sessions");
            assertTrue(sessions.contains("\"items\""), sessions);
            assertTrue(sessions.contains("\"mode\":\"backend\""), sessions);

            String pendingApprovals = handleGet(ConsoleController.approvalsPendingHandler(app), "/api/console/approvals/pending");
            assertTrue(pendingApprovals.contains("\"items\""), pendingApprovals);

            String recentChangeSets = handleGet(ConsoleController.recentChangeSetsHandler(app), "/api/console/changesets/recent");
            assertTrue(recentChangeSets.contains("\"items\""), recentChangeSets);
            assertTrue(recentChangeSets.contains("\"mode\":\"backend\""), recentChangeSets);

            String doctor = handleGet(ConsoleController.configDoctorHandler(app), "/console/api/config-doctor");
            assertTrue(doctor.contains("\"apiKeyPresent\":true"), doctor);
            assertFalse(doctor.contains("super-secret-key"), doctor);

            String traces = handleGet(ConsoleController.tracesHandler(app), "/console/api/traces");
            assertTrue(traces.contains("\"latest\""), traces);

            String workspaces = handleGet(ConsoleController.workspacesHandler(app), "/console/api/workspaces");
            assertTrue(workspaces.contains("\"items\""), workspaces);

            String tools = handleGet(ConsoleController.toolsHandler(app), "/console/api/tools");
            assertTrue(tools.contains("\"items\""), tools);
            assertTrue(tools.contains("\"builtinCount\""), tools);
            assertTrue(tools.contains("\"read_file\"") || tools.contains("\"list_dir\""), tools);

            String mcp = handleGet(ConsoleController.mcpHandler(app), "/console/api/mcp");
            assertTrue(mcp.contains("\"servers\""), mcp);
            assertTrue(mcp.contains("\"name\":\"demo\""), mcp);
            assertTrue(mcp.contains("\"transportType\":\"stdio\""), mcp);
            assertTrue(mcp.contains("[REDACTED]"), mcp);
            assertFalse(mcp.contains("secret-token-value"), mcp);
            assertFalse(mcp.contains("secret-env-token"), mcp);

            String mcpDiagnostics = handleGet(ConsoleController.mcpDiagnosticsHandler(app), "/console/api/mcp/diagnostics");
            assertTrue(mcpDiagnostics.contains("\"schemaHash\""), mcpDiagnostics);
            assertTrue(mcpDiagnostics.contains("\"registeredToolNames\""), mcpDiagnostics);
            assertTrue(mcpDiagnostics.contains("\"disabledReason\""), mcpDiagnostics);
            assertTrue(mcpDiagnostics.contains("[REDACTED]"), mcpDiagnostics);
            assertFalse(mcpDiagnostics.contains("secret-token-value"), mcpDiagnostics);
            assertFalse(mcpDiagnostics.contains("secret-env-token"), mcpDiagnostics);
            assertFalse(mcp.contains("visible-value"), mcp);

            String experiences = handleGet(ConsoleController.experiencesHandler(app), "/console/api/experiences");
            assertTrue(experiences.contains("\"candidates\""), experiences);
            assertTrue(experiences.contains("\"verified\""), experiences);

            String releaseCheck = handleGet(ConsoleController.releaseCheckHandler(app), "/console/api/release-check");
            assertTrue(releaseCheck.contains("\"reportPath\":\"target/release-check-report.md\""), releaseCheck);

            Path evalRun = workspace.resolve(".ricbot").resolve("evals").resolve("run-console");
            Files.createDirectories(evalRun);
            Files.writeString(evalRun.resolve("summary.json"), """
                    {"run_id":"run-console","started_at":"2026-05-22T01:00:00Z","total":1,"passed":0,"failed":1,"failures_by_kind":{"assertion":1}}
                    """);
            Files.writeString(evalRun.resolve("manifest.json"), """
                    {"provider_mode":"smoke","model":"gpt-4o-mini","api_key":"eval-secret-key"}
                    """);
            Files.writeString(evalRun.resolve("cases.jsonl"), """
                    {"id":"case-1","status":"fail","failure_kind":"assertion","duration_ms":12,"tools_used":["web_search"]}
                    """);
            Files.writeString(evalRun.resolve("report.md"), "# Eval report\n<script>blocked</script>\n");

            String evals = handleGet(ConsoleController.evalsHandler(app), "/console/api/evals");
            assertTrue(evals.contains("\"runId\":\"run-console\""), evals);
            assertTrue(evals.contains("\"failed\":1"), evals);
            assertFalse(evals.contains("eval-secret-key"), evals);

            String evalDetail = handleGet(ConsoleController.evalsHandler(app), "/console/api/evals/run-console");
            assertTrue(evalDetail.contains("\"cases\""), evalDetail);
            assertTrue(evalDetail.contains("\"failureKind\":\"assertion\""), evalDetail);
            assertTrue(evalDetail.contains("\"api_key\":\"[REDACTED]\""), evalDetail);
            assertFalse(evalDetail.contains("eval-secret-key"), evalDetail);

            TestExchange missingEval = getExchange("/console/api/evals/../secret");
            ConsoleController.evalsHandler(app).handle(missingEval);
            assertEquals(404, missingEval.getResponseCode(), missingEval.responseText());

            String models = handleGet(new RicbotApiServer.ModelsHandler(app), "/v1/models");
            assertTrue(models.contains("\"id\":\"gpt-4o-mini\""), models);

            String apiHealth = handleGet(new RicbotApiServer.HealthHandler(app), "/health");
            assertTrue(apiHealth.contains("\"status\":\"ok\""), apiHealth);

            TestExchange chatExchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));
            new RicbotApiServer.ChatCompletionsHandler(app).handle(chatExchange);
            assertEquals(200, chatExchange.getResponseCode(), chatExchange.responseText());
            String chat = chatExchange.responseText();
            assertTrue(chat.contains("\"object\":\"chat.completion\""), chat);
            assertTrue(chat.contains("\"content\":\"pong\""), chat);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleRuntime_reportsUnconfiguredModelWithoutLeakingDefaults(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("qwen-plus");
        var app = new RicbotApiAppContext(loop, "qwen-plus", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            String runtime = handleGet(ConsoleController.runtimeHandler(app), "/api/console/runtime");

            assertTrue(runtime.contains("\"appName\":\"Ricbot\""), runtime);
            assertTrue(runtime.contains("\"mode\":\"backend\""), runtime);
            assertTrue(runtime.contains("\"modelConfigured\":false"), runtime);
            assertTrue(runtime.contains("\"provider\":null"), runtime);
            assertTrue(runtime.contains("\"model\":null"), runtime);
            assertFalse(runtime.contains("\"model\":\"qwen-plus\""), runtime);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleSessionDetail_returnsReadOnlySessionTimelineForEncodedSessionIds(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("gpt-4o-mini");
        config.getProviders().getOpenai().setApiKey("super-secret-key");
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            String sessionId = "api:default";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.addMessage("user", "Read package metadata");
            session.addAssistantMessage("I will inspect the package file.", List.of(Map.of(
                    "id", "call_read_package",
                    "type", "function",
                    "function", Map.of("name", "read_file", "arguments", "{\"path\":\"package.json\"}")
            )));
            session.addToolMessage("call_read_package", "read_file", Map.of("path", "package.json", "content", "tool output"));
            loop.getSessions().save(session);

            String encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
            String detailBody = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + encoded
            );
            Map<String, Object> detail = MAPPER.readValue(detailBody, new TypeReference<>() {});

            assertEquals(sessionId, detail.get("sessionId"));
            assertEquals("gpt-4o-mini", detail.get("model"));
            assertEquals("openai", detail.get("provider"));
            assertTrue(detailBody.contains("\"messages\""), detailBody);
            assertTrue(detailBody.contains("\"toolCalls\""), detailBody);
            assertTrue(detailBody.contains("\"traceEvents\""), detailBody);
            assertTrue(detailBody.contains("\"approvalEvents\""), detailBody);
            assertTrue(detailBody.contains("\"changeSets\""), detailBody);
            assertFalse(detailBody.contains("super-secret-key"), detailBody);

            List<Map<String, Object>> toolCalls = MAPPER.convertValue(detail.get("toolCalls"), new TypeReference<>() {});
            assertEquals(1, toolCalls.size());
            assertEquals("read_file", toolCalls.get(0).get("toolName"));
            assertTrue(String.valueOf(toolCalls.get(0).get("result")).contains("tool output"));

            String timelineBody = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + encoded + "/timeline"
            );
            List<Map<String, Object>> timeline = MAPPER.readValue(timelineBody, new TypeReference<>() {});
            assertFalse(timeline.isEmpty(), timelineBody);
            assertTrue(timeline.stream().anyMatch(event -> "user_message".equals(event.get("type"))), timelineBody);
            assertTrue(timeline.stream().anyMatch(event -> "tool_call".equals(event.get("type"))), timelineBody);
            assertTrue(timeline.stream().allMatch(event -> event.containsKey("payload")), timelineBody);
            String cursor = String.valueOf(timeline.get(timeline.size() - 1).get("id"));

            session.addMessage("user", "Follow-up after cursor");
            loop.getSessions().save(session);

            String eventsBody = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + encoded + "/events?after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)
            );
            Map<String, Object> eventsResponse = MAPPER.readValue(eventsBody, new TypeReference<>() {});
            assertEquals(sessionId, eventsResponse.get("sessionId"));
            assertTrue(eventsResponse.containsKey("nextCursor"), eventsBody);
            List<Map<String, Object>> incrementalEvents = MAPPER.convertValue(eventsResponse.get("events"), new TypeReference<>() {});
            assertFalse(incrementalEvents.isEmpty(), eventsBody);
            assertTrue(incrementalEvents.stream().anyMatch(event -> String.valueOf(event.get("summary")).contains("Follow-up after cursor")), eventsBody);
            assertFalse(incrementalEvents.stream().anyMatch(event -> cursor.equals(event.get("id"))), eventsBody);

            TestExchange post = postExchangeRaw("/api/console/sessions/" + encoded, "");
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(post);
            assertEquals(405, post.getResponseCode(), post.responseText());

            TestExchange traversal = getExchange("/api/console/sessions/..%2Fsecret");
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(traversal);
            assertEquals(404, traversal.getResponseCode(), traversal.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void sessionDetail_includesRunEventsWhenPresent(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:run-events";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.addMessage("user", "run event detail");
            session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, runTrace(List.of(
                    runEvent("run_start", "2026-06-04T00:00:00Z", Map.of("run_id", "run-1")),
                    runEvent("capability_warning", "2026-06-04T00:00:01Z", Map.of("capability", "supportsToolCalling", "status", "WARN")),
                    runEvent("run_finish", "2026-06-04T00:00:02Z", Map.of("run_id", "run-1", "duration_ms", 12))
            )));
            loop.getSessions().save(session);

            String body = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
            );
            Map<String, Object> detail = MAPPER.readValue(body, new TypeReference<>() {});
            List<Map<String, Object>> runEvents = MAPPER.convertValue(detail.get("runEvents"), new TypeReference<>() {});

            assertEquals(3, runEvents.size(), body);
            assertTrue(runEvents.stream().anyMatch(event -> "run_start".equals(event.get("type"))), body);
            assertTrue(runEvents.stream().anyMatch(event -> "capability_warning".equals(event.get("type"))), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void sessionTimeline_includesRunEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:timeline-run-events";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, runTrace(List.of(
                    runEvent("run_start", "2026-06-04T00:01:00Z", Map.of("run_id", "run-2")),
                    runEvent("model_error", "2026-06-04T00:01:01Z", Map.of("error", "model failed")),
                    runEvent("run_stop", "2026-06-04T00:01:02Z", Map.of("stop_reason", "error"))
            )));
            loop.getSessions().save(session);

            String body = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline"
            );
            List<Map<String, Object>> timeline = MAPPER.readValue(body, new TypeReference<>() {});

            assertTrue(timeline.stream().anyMatch(event ->
                    "run_event".equals(event.get("type")) && String.valueOf(event.get("title")).contains("run_start")), body);
            assertTrue(timeline.stream().anyMatch(event ->
                    "run_event".equals(event.get("type")) && "ERROR".equals(event.get("status"))), body);
            assertTrue(timeline.stream().allMatch(event -> event.containsKey("id") && event.containsKey("payload")), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void sessionEventsPolling_returnsRunEventsAfterCursor(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:poll-run-events";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, runTrace(List.of(
                    runEvent("run_start", "2026-06-04T00:02:00Z", Map.of("run_id", "run-3")),
                    runEvent("run_retry", "2026-06-04T00:02:01Z", Map.of("retry_reason", "tool_loop")),
                    runEvent("run_finish", "2026-06-04T00:02:02Z", Map.of("run_id", "run-3"))
            )));
            loop.getSessions().save(session);
            String encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
            List<Map<String, Object>> timeline = MAPPER.readValue(handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + encoded + "/timeline"
            ), new TypeReference<>() {});
            String cursor = String.valueOf(timeline.stream()
                    .filter(event -> String.valueOf(event.get("title")).contains("run_start"))
                    .findFirst()
                    .orElseThrow()
                    .get("id"));

            String body = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + encoded + "/events?after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)
            );
            Map<String, Object> response = MAPPER.readValue(body, new TypeReference<>() {});
            List<Map<String, Object>> events = MAPPER.convertValue(response.get("events"), new TypeReference<>() {});

            assertTrue(events.stream().anyMatch(event -> String.valueOf(event.get("title")).contains("run_retry")), body);
            assertTrue(events.stream().anyMatch(event -> String.valueOf(event.get("title")).contains("run_finish")), body);
            assertFalse(events.stream().anyMatch(event -> cursor.equals(event.get("id"))), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void missingRunEvents_keepsBackwardCompatible(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:no-run-events";
            loop.getSessions().getOrCreate(sessionId).addMessage("user", "legacy session");
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));

            String body = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
            );
            Map<String, Object> detail = MAPPER.readValue(body, new TypeReference<>() {});
            List<Map<String, Object>> runEvents = MAPPER.convertValue(detail.get("runEvents"), new TypeReference<>() {});

            assertTrue(runEvents.isEmpty(), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void duplicateTraceAndRunEvent_doesNotDuplicateTimeline(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:dedupe-run-events";
            String at = "2026-06-04T00:03:00Z";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, runTrace(List.of(
                    runEvent("run_start", at, Map.of("run_id", "run-4"))
            )));
            loop.getSessions().save(session);
            String traceId = new TraceStore(workspace).traceIdForSession(sessionId);
            new TraceStore(workspace).append(new TraceEvent(
                    traceId,
                    "trace-run-start",
                    "",
                    sessionId,
                    "",
                    "",
                    "",
                    TraceEventType.TEAM_EVENT,
                    "agent",
                    "run_start",
                    Map.of("type", "run_start", "run_id", "run-4"),
                    at,
                    null
            ));

            String body = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline"
            );
            List<Map<String, Object>> timeline = MAPPER.readValue(body, new TypeReference<>() {});
            long runStartCount = timeline.stream()
                    .filter(event -> String.valueOf(event.get("title")).contains("run_start"))
                    .count();

            assertEquals(1, runStartCount, body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventsStream_endpointExists(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:sse-endpoint";
            loop.getSessions().getOrCreate(sessionId).addMessage("user", "stream endpoint");
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));

            TestExchange exchange = getExchange("/api/console/sessions/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/events/stream?once=true");
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(exchange.getResponseHeaders().getFirst("Content-Type").startsWith("text/event-stream"),
                    exchange.getResponseHeaders().toString());
            assertTrue(exchange.responseText().contains("event: "), exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventsStream_sendsHeartbeatOrTimelineEvent(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:sse-heartbeat";
            loop.getSessions().getOrCreate(sessionId);
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));

            TestExchange exchange = getExchange("/console/api/sessions/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/events/stream?once=true");
            ConsoleController.sessionDetailHandler(app, "/console/api/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(exchange.responseText().contains("event: heartbeat")
                    || exchange.responseText().contains("event: timeline_batch"), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"sessionId\":\"" + sessionId + "\""), exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventsStream_doesNotTriggerAgentExecution(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:sse-readonly";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.addMessage("user", "existing message");
            loop.getSessions().save(session);
            int beforeMessages = loop.getSessions().find(sessionId).orElseThrow().getMessages().size();

            TestExchange exchange = getExchange("/api/console/sessions/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/events/stream?once=true");
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            int afterMessages = loop.getSessions().find(sessionId).orElseThrow().getMessages().size();
            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertEquals(beforeMessages, afterMessages, exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventsStream_reusesTimelineCursorLogic(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", new Config(), null, workspace);
        try {
            String sessionId = "api:sse-cursor";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, runTrace(List.of(
                    runEvent("run_start", "2026-06-04T00:04:00Z", Map.of("run_id", "run-5")),
                    runEvent("run_finish", "2026-06-04T00:04:01Z", Map.of("run_id", "run-5"))
            )));
            loop.getSessions().save(session);
            String encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
            List<Map<String, Object>> timeline = MAPPER.readValue(handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + encoded + "/timeline"
            ), new TypeReference<>() {});
            String cursor = String.valueOf(timeline.stream()
                    .filter(event -> String.valueOf(event.get("title")).contains("run_start"))
                    .findFirst()
                    .orElseThrow()
                    .get("id"));

            TestExchange exchange = getExchange("/api/console/sessions/" + encoded
                    + "/events/stream?once=true&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(exchange.responseText().contains("run_finish"), exchange.responseText());
            assertFalse(exchange.responseText().contains("run_start"), exchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void startRun_returnsModelNotConfiguredWhenProviderMissing(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("qwen-plus");
        var app = new RicbotApiAppContext(loop, "qwen-plus", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            TestExchange exchange = postExchange("/api/console/sessions/api%3Aconsole/runs", Map.of(
                    "input", "请分析当前项目结构"
            ));
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"status\":\"failed\""), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"code\":\"model_not_configured\""), exchange.responseText());
            assertFalse(loop.getSessions().find("api:console").isPresent());
        } finally {
            loop.stop();
        }
    }

    @Test
    void startRun_rejectsBlankInput(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            TestExchange exchange = postExchange("/api/console/sessions/api%3Aconsole/runs", Map.of(
                    "input", "   "
            ));
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"status\":\"failed\""), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"code\":\"blank_input\""), exchange.responseText());
            assertFalse(loop.getSessions().find("api:console").isPresent());
        } finally {
            loop.stop();
        }
    }

    @Test
    void startRun_returnsImmediatelyWithRunId(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 250);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            long startedAt = System.nanoTime();
            TestExchange exchange = postExchange("/api/console/sessions/api%3Aasync-console/runs", Map.of(
                    "input", "ping async"
            ));
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(elapsedMillis < 200, "POST should not wait for slow model, elapsedMillis=" + elapsedMillis);
            assertTrue(exchange.responseText().contains("\"runId\""), exchange.responseText());
            waitForRunStatus(app, runIdFromStart(exchange.responseText()), "finished");
        } finally {
            loop.stop();
        }
    }

    @Test
    void startRun_setsStatusQueuedOrRunning(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            TestExchange exchange = postExchange("/console/api/sessions/api%3Anew-console/runs", Map.of(
                    "input", "ping from console"
            ));
            ConsoleController.sessionDetailHandler(app, "/console/api/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"status\":\"queued\"")
                    || exchange.responseText().contains("\"status\":\"running\""), exchange.responseText());
            assertTrue(exchange.responseText().contains("\"runId\""), exchange.responseText());
            waitForRunStatus(app, runIdFromStart(exchange.responseText()), "finished");
        } finally {
            loop.stop();
        }
    }

    @Test
    void runStatus_returnsRunRecord(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            Map<String, Object> start = MAPPER.readValue(postRun(app, "/api/console/sessions/api%3Arun-status/runs", "ping"), new TypeReference<>() {});
            String runId = String.valueOf(start.get("runId"));

            String body = handleGet(ConsoleController.runStatusHandler(app, "/api/console/runs/"), "/api/console/runs/" + runId);

            assertTrue(body.contains("\"runId\":\"" + runId + "\""), body);
            assertTrue(body.contains("\"sessionId\":\"api:run-status\""), body);
            assertTrue(body.contains("\"inputPreview\":\"ping\""), body);
            waitForRunStatus(app, runId, "finished");
        } finally {
            loop.stop();
        }
    }

    @Test
    void runStatus_transitionsToFinishedOnSuccess(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            Map<String, Object> start = MAPPER.readValue(postRun(app, "/api/console/sessions/api%3Arun-finish/runs", "ping"), new TypeReference<>() {});
            String runId = String.valueOf(start.get("runId"));

            String body = waitForRunStatus(app, runId, "finished");

            assertTrue(body.contains("\"status\":\"finished\""), body);
            assertTrue(body.contains("\"finishedAt\""), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void runStatus_transitionsToFailedOnException(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildThrowingLoop(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            Map<String, Object> start = MAPPER.readValue(postRun(app, "/api/console/sessions/api%3Arun-fail/runs", "ping"), new TypeReference<>() {});
            String runId = String.valueOf(start.get("runId"));

            String body = waitForRunStatus(app, runId, "failed");

            assertTrue(body.contains("\"status\":\"failed\""), body);
            assertTrue(body.contains("forced console failure"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void startRun_doesNotChangeApprovalSemantics(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            ApprovalRequest request = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("approval remains pending"), "", "write_file", List.of("console.txt")),
                    "write_file",
                    Map.of("path", "console.txt", "content", "console\n"),
                    "api:approval-run"
            );

            TestExchange exchange = postExchange("/api/console/sessions/api%3Aapproval-run/runs", Map.of(
                    "input", "ping while approval exists"
            ));
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            waitForRunStatus(app, runIdFromStart(exchange.responseText()), "finished");
            ApprovalRequest after = loop.getApprovalService().find(request.requestId());
            assertEquals(ApprovalRequest.ApprovalStatus.PENDING, after.status());
            assertFalse(after.consumed());
        } finally {
            loop.stop();
        }
    }

    @Test
    void startRun_recordsRunStartWhenExecutionBegins(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String sessionId = "api:run-start-console";
            TestExchange exchange = postExchange("/api/console/sessions/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/runs", Map.of("input", "please run"));
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(exchange);

            assertEquals(200, exchange.getResponseCode(), exchange.responseText());
            waitForRunStatus(app, runIdFromStart(exchange.responseText()), "finished");
            String timeline = handleGet(
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline"
            );
            assertTrue(timeline.contains("run_start"), timeline);
        } finally {
            loop.stop();
        }
    }

    @Test
    void asyncRun_stillUsesAgentLoopProcessDirect(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            Map<String, Object> start = MAPPER.readValue(postRun(app, "/api/console/sessions/api%3Adirect-chain/runs", "direct chain"), new TypeReference<>() {});
            waitForRunStatus(app, String.valueOf(start.get("runId")), "finished");

            assertTrue(loop.getSessions().find("api:direct-chain").isPresent());
            assertTrue(loop.getSessions().find("api:direct-chain").orElseThrow().getMessages().stream()
                    .anyMatch(message -> String.valueOf(message.get("content")).contains("direct chain")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_returnsNotFoundForUnknownRun(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            TestExchange cancel = postExchangeRaw("/api/console/runs/missing-run/cancel", "");
            ConsoleController.runStatusHandler(app, "/api/console/runs/").handle(cancel);

            assertEquals(200, cancel.getResponseCode(), cancel.responseText());
            assertTrue(cancel.responseText().contains("\"status\":\"failed\""), cancel.responseText());
            assertTrue(cancel.responseText().contains("\"code\":\"run_not_found\""), cancel.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_cancelsQueuedRun(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 500);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-queued/runs", "cancel queued"));

            String body = cancelRun(app, runId);

            assertTrue(body.contains("\"status\":\"cancelled\""), body);
            assertTrue(body.contains("Run cancellation requested"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_cancelsRunningRun(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 1000);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-running/runs", "cancel running"));
            waitForRunStatus(app, runId, "running");

            String body = cancelRun(app, runId);

            assertTrue(body.contains("\"status\":\"cancelled\""), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_isIdempotentWhenAlreadyCancelled(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 1000);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-idempotent/runs", "cancel twice"));
            cancelRun(app, runId);

            String second = cancelRun(app, runId);

            assertTrue(second.contains("\"status\":\"cancelled\""), second);
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_doesNotCancelFinishedRun(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-finished/runs", "finish first"));
            waitForRunStatus(app, runId, "finished");

            String body = cancelRun(app, runId);

            assertTrue(body.contains("\"status\":\"finished\""), body);
            assertTrue(body.contains("Run already finished"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelledRun_isNotOverwrittenByFinishedState(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 300);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-not-overwritten/runs", "cancel stable"));
            cancelRun(app, runId);
            Thread.sleep(450L);

            String body = handleGet(ConsoleController.runStatusHandler(app, "/api/console/runs/"), "/api/console/runs/" + runId);

            assertTrue(body.contains("\"status\":\"cancelled\""), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_doesNotChangeApprovalSemantics(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 1000);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            ApprovalRequest request = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("approval remains pending"), "", "write_file", List.of("cancel.txt")),
                    "write_file",
                    Map.of("path", "cancel.txt", "content", "cancel\n"),
                    "api:cancel-approval"
            );
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-approval/runs", "cancel approval run"));

            cancelRun(app, runId);

            ApprovalRequest after = loop.getApprovalService().find(request.requestId());
            assertEquals(ApprovalRequest.ApprovalStatus.PENDING, after.status());
            assertFalse(after.consumed());
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_recordsCancelledTimelineEvent(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 1000);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        String sessionId = "api:cancel-timeline";
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs", "cancel timeline"));

            String cancelBody = cancelRun(app, runId);
            String timeline = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline");
            String events = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/events");

            assertTrue(cancelBody.contains("\"status\":\"cancelled\""), cancelBody);
            assertTrue(timeline.contains("run_cancelled"), timeline);
            assertTrue(timeline.contains(runId), timeline);
            assertTrue(events.contains("run_cancelled"), events);
            List<ConsoleEvent> stored = consoleEvents(workspace, sessionId);
            assertTrue(stored.stream().anyMatch(event -> "run_cancel_requested".equals(event.name())), stored.toString());
            assertTrue(stored.stream().anyMatch(event -> "run_cancelled".equals(event.name())), stored.toString());
        } finally {
            loop.stop();
        }
    }

    @Test
    void cancelRun_callsAgentRunControllerCancelWhenAvailable(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildSlowLoop(workspace, 5000);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/api%3Acancel-controller/runs", "cancel controller"));
            waitForRunStatus(app, runId, "running");
            waitForRunStatusField(app, runId, "\"controllerAttached\":true");

            String body = cancelRun(app, runId);

            assertTrue(body.contains("\"controllerCancelled\":true"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void approvalApproveOnly_usesApplicationService(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            ApprovalRequest request = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("write requires approval"), "", "write_file", List.of("a.txt")),
                    "write_file",
                    Map.of("path", "a.txt", "content", "approved\n"),
                    "api:approval-only"
            );

            TestExchange approve = postExchangeRaw("/api/console/approvals/" + request.requestId() + "/approve-only", "");
            ConsoleController.approvalsHandler(app).handle(approve);

            assertEquals(200, approve.getResponseCode(), approve.responseText());
            assertTrue(approve.responseText().contains("\"executed\":false"), approve.responseText());
            assertEquals(ApprovalRequest.ApprovalStatus.APPROVED, loop.getApprovalService().find(request.requestId()).status());
            assertFalse(loop.getApprovalService().find(request.requestId()).consumed());
            assertTrue(consoleEvents(workspace).stream()
                    .anyMatch(event -> "approval_approve_only".equals(event.name())), approve.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void approvalApproveExecute_usesApplicationService(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            ApprovalRequest request = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("write requires approval"), "", "write_file", List.of("approved.txt")),
                    "write_file",
                    Map.of("path", "approved.txt", "content", "approved\n"),
                    "api:approval-execute"
            );

            TestExchange approve = postExchangeRaw("/api/console/approvals/" + request.requestId() + "/approve-execute", "");
            ConsoleController.approvalsHandler(app).handle(approve);

            assertEquals(200, approve.getResponseCode(), approve.responseText());
            assertTrue(approve.responseText().contains("\"executed\":true"), approve.responseText());
            assertTrue(Files.exists(workspace.resolve("approved.txt")));
            assertTrue(loop.getApprovalService().find(request.requestId()).consumed());
        } finally {
            loop.stop();
        }
    }

    @Test
    void approvalReject_usesApplicationService(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = configuredOpenAiConfig(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        try {
            ApprovalRequest request = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.HIGH, List.of("reject"), "rm a.txt", "exec", List.of("a.txt")),
                    "exec",
                    Map.of("command", "rm a.txt"),
                    "api:approval-reject"
            );

            TestExchange reject = postExchangeRaw("/api/console/approvals/" + request.requestId() + "/reject", "");
            ConsoleController.approvalsHandler(app).handle(reject);

            assertEquals(200, reject.getResponseCode(), reject.responseText());
            assertEquals(ApprovalRequest.ApprovalStatus.REJECTED, loop.getApprovalService().find(request.requestId()).status());
            assertTrue(consoleEvents(workspace).stream()
                    .anyMatch(event -> "approval_reject".equals(event.name())), reject.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void changeSetDetail_returnsRealDiffWhenAvailable(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "before\n");
        git(workspace, "add", "README.md");
        git(workspace, "commit", "-m", "Initial");
        Files.writeString(workspace.resolve("README.md"), "before\nafter\n");
        GitChangeSet changeSet = new ChangeSetService(workspace).createFromWorkingTree("api:changeset-diff", "", "");
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String body = handleGet(ConsoleController.changeSetHandler(app, "/api/console/changesets/"),
                    "/api/console/changesets/" + changeSet.id() + "/files/" + URLEncoder.encode("README.md", StandardCharsets.UTF_8) + "/diff");

            assertTrue(body.contains("\"changeSetId\":\"" + changeSet.id() + "\""), body);
            assertTrue(body.contains("after"), body);
            assertTrue(consoleEvents(workspace).stream()
                    .anyMatch(event -> "changeset_file_diff_view".equals(event.name())), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void changeSetDiff_missingReturnsEmptyOrClearMessage(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String body = handleGet(ConsoleController.changeSetHandler(app, "/api/console/changesets/"),
                    "/api/console/changesets/missing/files/README.md/diff");

            assertTrue(body.contains("\"diff\":\"\""), body);
            assertTrue(body.contains("暂无真实 diff"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void timelineEvents_haveUnifiedFields(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:unified-fields";
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs", "unified fields"));
            waitForRunStatusField(app, runId, "\"status\":\"finished\"");

            String timeline = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline");

            assertTrue(timeline.contains("\"name\""), timeline);
            assertTrue(timeline.contains("\"category\""), timeline);
            assertTrue(timeline.contains("\"actor\""), timeline);
            assertTrue(timeline.contains("\"source\""), timeline);
        } finally {
            loop.stop();
        }
    }

    @Test
    void timelineCategoryFilter_returnsExpectedEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:category-filter";
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs", "filter run"));
            waitForRunStatusField(app, runId, "\"status\":\"finished\"");

            String runTimeline = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline?category=run");

            assertTrue(runTimeline.contains("\"category\":\"run\""), runTimeline);
            assertFalse(runTimeline.contains("\"category\":\"system\""), runTimeline);
        } finally {
            loop.stop();
        }
    }

    @Test
    void timeline_supportsRunIdFilter(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:run-filter";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("run-filter-1", sessionId, "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of("inputPreview", "alpha")));
            store.append(consoleEvent("run-filter-2", sessionId, "run-b", "run", "run_submit", "INFO", "2026-06-04T00:00:01Z", Map.of("inputPreview", "beta")));

            String byRun = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline?category=run&runId=run-a");
            String missingRun = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/events?runId=missing-run");

            assertTrue(byRun.contains("\"runId\":\"run-a\""), byRun);
            assertFalse(byRun.contains("\"runId\":\"run-b\""), byRun);
            assertTrue(missingRun.contains("\"events\":[]"), missingRun);
        } finally {
            loop.stop();
        }
    }

    @Test
    void timeline_supportsRunIdAndCategoryFilter(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:run-category-filter";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("run-category-1", sessionId, "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of()));
            store.append(consoleEvent("run-category-2", sessionId, "run-a", "tool", "tool_call", "INFO", "2026-06-04T00:00:01Z", Map.of()));
            store.append(consoleEvent("run-category-3", sessionId, "run-b", "run", "run_submit", "INFO", "2026-06-04T00:00:02Z", Map.of()));

            String body = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline?category=run&runId=run-a");

            assertTrue(body.contains("run-category-1"), body);
            assertFalse(body.contains("run-category-2"), body);
            assertFalse(body.contains("run-category-3"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void events_supportsRunIdFilter(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:events-run-filter";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("events-run-1", sessionId, "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of()));
            store.append(consoleEvent("events-run-2", sessionId, "run-b", "run", "run_submit", "INFO", "2026-06-04T00:00:01Z", Map.of()));

            String body = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/events?runId=run-b");

            assertFalse(body.contains("events-run-1"), body);
            assertTrue(body.contains("events-run-2"), body);
            assertTrue(body.contains("\"nextCursor\":\"events-run-2\""), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void runIdFilter_returnsEmptyForUnknownRun(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:unknown-run-filter";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            new JsonlConsoleEventStore(workspace).append(consoleEvent("unknown-run-1", sessionId, "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of()));

            String timeline = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline?runId=missing-run");
            String events = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/events?runId=missing-run");

            assertEquals("[]", timeline);
            assertTrue(events.contains("\"events\":[]"), events);
        } finally {
            loop.stop();
        }
    }

    @Test
    void runIdFilter_preservesLegacyEventsWithoutCrash(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:legacy-run-filter";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("legacy-run-1", sessionId, "", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of("runId", "payload-run")));
            store.append(consoleEvent("legacy-run-2", sessionId, "", "run", "run_submit", "INFO", "2026-06-04T00:00:01Z", Map.of("run_id", "payload-run-2")));

            String body = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline?runId=payload-run");

            assertTrue(body.contains("legacy-run-1"), body);
            assertTrue(body.contains("\"runId\":\"payload-run\""), body);
            assertFalse(body.contains("legacy-run-2"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventsStream_usesUnifiedTimelineEventShape(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:stream-unified";
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs", "stream unified"));
            waitForRunStatusField(app, runId, "\"status\":\"finished\"");

            TestExchange stream = getExchange("/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/events/stream?once=true&maxTicks=1");
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(stream);

            assertEquals(200, stream.getResponseCode(), stream.responseText());
            assertTrue(stream.responseText().contains("\"category\""), stream.responseText());
            assertTrue(stream.responseText().contains("\"name\""), stream.responseText());
            assertTrue(stream.responseText().contains("\"actor\""), stream.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void sseStream_replaysStoredEventsAfterCursor(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:stored-replay";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("evt-replay-1", sessionId, "run", "run_submit"));
            store.append(consoleEvent("evt-replay-2", sessionId, "approval", "approval_reject"));

            TestExchange stream = getExchange("/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/events/stream?once=true&after=evt-replay-1");
            ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(stream);

            assertEquals(200, stream.getResponseCode(), stream.responseText());
            assertFalse(stream.responseText().contains("evt-replay-1"), stream.responseText());
            assertTrue(stream.responseText().contains("evt-replay-2"), stream.responseText());
            assertTrue(stream.responseText().contains("\"source\":\"console_event_store\""), stream.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void sseStream_receivesPublishedEventWithoutPolling(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:live-store-stream";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            TestExchange stream = getExchange("/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                    + "/events/stream?maxTicks=2");
            Thread thread = new Thread(() -> {
                try {
                    ConsoleController.sessionDetailHandler(app, "/api/console/sessions/").handle(stream);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            thread.start();
            Thread.sleep(150L);

            String runId = runIdFromStart(postRun(app, "/api/console/sessions/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs", "live event"));
            thread.join(4_000L);

            assertEquals(200, stream.getResponseCode(), stream.responseText());
            assertTrue(stream.responseText().contains("run_submit")
                    || stream.responseText().contains("run_queued")
                    || stream.responseText().contains(runId), stream.responseText());
            assertTrue(stream.responseText().contains("timeline_batch"), stream.responseText());
            waitForRunStatus(app, runId, "finished");
        } finally {
            loop.stop();
        }
    }

    @Test
    void runSubmit_recordsConsoleEvent(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:run-events-store";
            String runId = runIdFromStart(postRun(app, "/api/console/sessions/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs", "store run"));
            waitForRunStatus(app, runId, "finished");

            List<ConsoleEvent> events = consoleEvents(workspace, sessionId);
            assertTrue(events.stream().anyMatch(event -> "run_submit".equals(event.name())), events.toString());
            assertTrue(events.stream().anyMatch(event -> "run_queued".equals(event.name())), events.toString());
            assertTrue(events.stream().anyMatch(event -> "run_started".equals(event.name())), events.toString());
            assertTrue(events.stream().anyMatch(event -> "run_finished".equals(event.name())), events.toString());
        } finally {
            loop.stop();
        }
    }

    @Test
    void timelineMergesConsoleEventStoreAndRunTraceWithoutDuplicates(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:merge-store-run-trace";
            String runId = "run-dedupe";
            var session = loop.getSessions().getOrCreate(sessionId);
            session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, runTrace(List.of(
                    runEvent("run_cancelled", "2026-06-04T00:01:00Z", Map.of("run_id", runId, "reason", "trace"))
            )));
            loop.getSessions().save(session);
            new JsonlConsoleEventStore(workspace).append(new ConsoleEvent(
                    "evt-store-cancelled",
                    sessionId,
                    runId,
                    "run_event",
                    "run_cancelled",
                    "run",
                    "CANCELLED",
                    "2026-06-04T00:01:01Z",
                    "run_cancelled",
                    "store",
                    "console",
                    "console_event_store",
                    Map.of("runId", runId)
            ));

            String timeline = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/timeline");

            assertEquals(1, occurrences(timeline, "\"name\":\"run_cancelled\""), timeline);
        } finally {
            loop.stop();
        }
    }

    @Test
    void runHistory_returnsEmptyWhenNoEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:history-empty";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));

            String body = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs/history");

            assertTrue(body.contains("\"sessionId\":\"" + sessionId + "\""), body);
            assertTrue(body.contains("\"runs\":[]"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void runHistory_aggregatesRunLifecycle(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:history-lifecycle";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("hist-1", sessionId, "run-1", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of("inputPreview", "hello history")));
            store.append(consoleEvent("hist-2", sessionId, "run-1", "run", "run_started", "INFO", "2026-06-04T00:00:01Z", Map.of()));
            store.append(consoleEvent("hist-3", sessionId, "run-1", "run", "run_finished", "SUCCESS", "2026-06-04T00:00:06Z", Map.of("model", "qwen-plus")));

            String body = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs/history");

            assertTrue(body.contains("\"runId\":\"run-1\""), body);
            assertTrue(body.contains("\"status\":\"finished\""), body);
            assertTrue(body.contains("\"inputPreview\":\"hello history\""), body);
            assertTrue(body.contains("\"durationMs\":5000"), body);
            assertTrue(body.contains("\"lastEventName\":\"run_finished\""), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void runHistory_countsToolApprovalChangeSetErrorEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String sessionId = "api:history-counts";
            loop.getSessions().save(loop.getSessions().getOrCreate(sessionId));
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("cnt-1", sessionId, "run-2", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of()));
            store.append(consoleEvent("cnt-2", sessionId, "run-2", "tool", "tool_call", "INFO", "2026-06-04T00:00:01Z", Map.of()));
            store.append(consoleEvent("cnt-3", sessionId, "run-2", "approval", "approval_reject", "SUCCESS", "2026-06-04T00:00:02Z", Map.of()));
            store.append(consoleEvent("cnt-4", sessionId, "run-2", "changeset", "changeset_diff_view", "SUCCESS", "2026-06-04T00:00:03Z", Map.of()));
            store.append(consoleEvent("cnt-5", sessionId, "run-2", "error", "model_error", "ERROR", "2026-06-04T00:00:04Z", Map.of()));

            String body = handleGet(ConsoleController.sessionDetailHandler(app, "/api/console/sessions/"),
                    "/api/console/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/runs/history");

            assertTrue(body.contains("\"toolCallCount\":1"), body);
            assertTrue(body.contains("\"approvalCount\":1"), body);
            assertTrue(body.contains("\"changeSetCount\":1"), body);
            assertTrue(body.contains("\"errorCount\":1"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventSearch_filtersAndPaginatesEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("search-1", "session-a", "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of("inputPreview", "alpha task")));
            store.append(consoleEvent("search-2", "session-a", "run-a", "tool", "tool_call", "SUCCESS", "2026-06-04T00:00:01Z", Map.of("toolName", "ReadFile")));
            store.append(consoleEvent("search-3", "session-b", "run-b", "error", "model_error", "ERROR", "2026-06-04T00:00:02Z", Map.of("error", "beta failure")));

            String bySession = handleGet(ConsoleController.eventSearchHandler(app),
                    "/api/console/events/search?sessionId=session-a");
            String byRun = handleGet(ConsoleController.eventSearchHandler(app),
                    "/api/console/events/search?runId=run-b");
            String byCategoryStatusKeyword = handleGet(ConsoleController.eventSearchHandler(app),
                    "/api/console/events/search?category=error&status=ERROR&keyword=beta");
            String afterLimit = handleGet(ConsoleController.eventSearchHandler(app),
                    "/api/console/events/search?limit=1&after=search-1");

            assertTrue(bySession.contains("search-1"), bySession);
            assertFalse(bySession.contains("search-3"), bySession);
            assertTrue(byRun.contains("search-3"), byRun);
            assertTrue(byCategoryStatusKeyword.contains("model_error"), byCategoryStatusKeyword);
            assertFalse(byCategoryStatusKeyword.contains("tool_call"), byCategoryStatusKeyword);
            assertFalse(afterLimit.contains("search-1"), afterLimit);
            assertTrue(afterLimit.contains("\"nextCursor\":\"search-2\""), afterLimit);
        } finally {
            loop.stop();
        }
    }

    @Test
    void metricsSummary_aggregatesConsoleEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
            store.append(consoleEvent("metric-1", "session-a", "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of("inputPreview", "alpha")));
            store.append(consoleEvent("metric-2", "session-a", "run-a", "run", "run_started", "INFO", "2026-06-04T00:00:01Z", Map.of()));
            store.append(consoleEvent("metric-3", "session-a", "run-a", "tool", "tool_call", "SUCCESS", "2026-06-04T00:00:02Z", Map.of("toolName", "read_file")));
            store.append(consoleEvent("metric-4", "session-a", "run-a", "approval", "approve_execute", "SUCCESS", "2026-06-04T00:00:03Z", Map.of()));
            store.append(consoleEvent("metric-5", "session-a", "run-a", "changeset", "file_diff_view", "SUCCESS", "2026-06-04T00:00:04Z", Map.of()));
            store.append(consoleEvent("metric-6", "session-a", "run-a", "run", "run_finished", "SUCCESS", "2026-06-04T00:00:06Z", Map.of()));
            store.append(consoleEvent("metric-7", "session-b", "run-b", "error", "tool_error", "ERROR", "2026-06-04T00:00:07Z", Map.of("toolName", "write_file")));

            String all = handleGet(ConsoleController.metricsSummaryHandler(app),
                    "/api/console/metrics/summary");
            String scoped = handleGet(ConsoleController.metricsSummaryHandler(app),
                    "/api/console/metrics/summary?sessionId=session-a&since=2026-06-04T00:00:00Z&until=2026-06-04T00:00:06Z");

            assertTrue(all.contains("\"total\":2"), all);
            assertTrue(all.contains("\"finished\":1"), all);
            assertTrue(all.contains("\"failed\":1"), all);
            assertTrue(all.contains("\"successRate\":0.5"), all);
            assertTrue(all.contains("\"topTools\""), all);
            assertTrue(all.contains("read_file"), all);
            assertTrue(all.contains("\"approveExecute\":1"), all);
            assertTrue(all.contains("\"fileDiffViews\":1"), all);
            assertTrue(all.contains("\"recentErrors\""), all);
            assertTrue(all.contains("tool_error"), all);
            assertTrue(all.contains("\"activeSessions\""), all);
            assertTrue(scoped.contains("\"sessionId\":\"session-a\""), scoped);
            assertFalse(scoped.contains("tool_error"), scoped);
        } finally {
            loop.stop();
        }
    }

    @Test
    void metricsSummary_returnsEmptyWhenNoConsoleEvents(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            String body = handleGet(ConsoleController.metricsSummaryHandler(app),
                    "/api/console/metrics/summary");

            assertTrue(body.contains("\"runs\":{\"total\":0"), body);
            assertTrue(body.contains("\"events\":{\"total\":0"), body);
            assertTrue(body.contains("\"recentErrors\":[]"), body);
            assertTrue(body.contains("\"activeSessions\":[]"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void eventSearch_ignoresMalformedJsonlLines(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", configuredOpenAiConfig(workspace), null, workspace);
        try {
            Path file = workspace.resolve(".ricbot").resolve("console-events.jsonl");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{broken json\n", StandardCharsets.UTF_8);
            new JsonlConsoleEventStore(workspace).append(consoleEvent("valid-after-bad", "session-a", "run-a", "run", "run_submit", "INFO", "2026-06-04T00:00:00Z", Map.of()));

            String body = handleGet(ConsoleController.eventSearchHandler(app),
                    "/api/console/events/search?sessionId=session-a");

            assertTrue(body.contains("valid-after-bad"), body);
            assertFalse(body.contains("broken json"), body);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleReleaseCheckEndpoint_readsOnlyFixedReportAndRedactsSecrets(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        Path report = Path.of("target", "release-check-report.md");
        String original = Files.isRegularFile(report) ? Files.readString(report) : null;
        try {
            var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

            Files.deleteIfExists(report);
            String missing = handleGet(ConsoleController.releaseCheckHandler(app), "/console/api/release-check?path=../../secret");
            assertTrue(missing.contains("\"exists\":false"), missing);
            assertFalse(missing.contains("secret"), missing);

            Files.createDirectories(report.getParent());
            Files.writeString(report, """
                    # Ricbot Release Check Report

                    - generated_at: 2026-05-22T08:00:00Z
                    - final_status: PASS

                    ## Baseline

                    - baseline_status: FOUND

                    ## Steps

                    | Step | Result |
                    | --- | --- |
                    | eval compare | PASS |

                    token: should-not-leak
                    """);

            String found = handleGet(ConsoleController.releaseCheckHandler(app), "/console/api/release-check");
            assertTrue(found.contains("\"exists\":true"), found);
            assertTrue(found.contains("\"finalStatus\":\"PASS\""), found);
            assertTrue(found.contains("\"baselineStatus\":\"FOUND\""), found);
            assertTrue(found.contains("\"evalCompareStatus\":\"PASS\""), found);
            assertTrue(found.contains("[REDACTED]"), found);
            assertFalse(found.contains("should-not-leak"), found);
        } finally {
            if (original != null) {
                Files.createDirectories(report.getParent());
                Files.writeString(report, original);
            } else {
                Files.deleteIfExists(report);
            }
            loop.stop();
        }
    }

    @Test
    void consoleEvalSmokeAction_runsFixedGoldenSmokeAndAppearsInViewer(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            TestExchange smoke = postExchangeRaw("/console/api/evals/smoke", """
                    {"scenarios":"evil.jsonl","workspace":"/tmp/evil-workspace","provider":"real-model"}
                    """);
            ConsoleController.evalsHandler(app).handle(smoke);
            assertEquals(200, smoke.getResponseCode(), smoke.responseText());
            assertTrue(smoke.responseText().contains("\"action\":\"eval.smoke\""), smoke.responseText());
            assertTrue(smoke.responseText().contains("\"runId\""), smoke.responseText());
            assertTrue(smoke.responseText().contains("\"status\""), smoke.responseText());
            assertTrue(smoke.responseText().contains("request body ignored"), smoke.responseText());

            Map<String, Object> response = MAPPER.readValue(smoke.responseText(), new TypeReference<>() {});
            Map<?, ?> data = (Map<?, ?>) response.get("data");
            String runId = String.valueOf(data.get("runId"));
            assertFalse(runId.isBlank());

            String evals = handleGet(ConsoleController.evalsHandler(app), "/console/api/evals");
            assertTrue(evals.contains("\"runId\":\"" + runId + "\""), evals);
            assertTrue(evals.contains("\"providerMode\":\"smoke\""), evals);
            assertTrue(evals.contains("\"model\":\"smoke-model\""), evals);

            String detail = handleGet(ConsoleController.evalsHandler(app), "/console/api/evals/" + runId);
            assertTrue(detail.contains("\"provider_mode\":\"smoke\""), detail);
            assertTrue(detail.contains("\"model\":\"smoke-model\""), detail);
            assertTrue(detail.contains("evals/golden.jsonl") || detail.contains("evals\\\\golden.jsonl"), detail);
            assertFalse(detail.contains("evil.jsonl"), detail);
            assertFalse(detail.contains("/tmp/evil-workspace"), detail);
            assertFalse(detail.contains("real-model"), detail);

            String actions = handleGet(ConsoleController.actionsHandler(app), "/console/api/actions");
            assertTrue(actions.contains("\"action\":\"eval.smoke\""), actions);
            assertTrue(actions.contains("\"targetType\":\"EVAL\""), actions);
            assertTrue(actions.contains("\"result\":\"SUCCESS\""), actions);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleEvalSmokeAction_reusesAuthAndOriginProtection(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        try {
            var tokenApp = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "console-token", config, null, workspace);
            TestExchange unauthorized = postExchangeRaw("/console/api/evals/smoke", "");
            ConsoleController.evalsHandler(tokenApp).handle(unauthorized);
            assertEquals(401, unauthorized.getResponseCode(), unauthorized.responseText());

            var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
            TestExchange invalidOrigin = postExchangeRaw("/console/api/evals/smoke", "");
            invalidOrigin.getRequestHeaders().set("Origin", "https://evil.example");
            ConsoleController.evalsHandler(app).handle(invalidOrigin);
            assertEquals(403, invalidOrigin.getResponseCode(), invalidOrigin.responseText());
            assertFalse(Files.isDirectory(workspace.resolve(".ricbot").resolve("evals")));

            String actions = handleGet(ConsoleController.actionsHandler(app), "/console/api/actions");
            assertTrue(actions.contains("\"action\":\"eval.smoke\""), actions);
            assertTrue(actions.contains("\"result\":\"UNAUTHORIZED\""), actions);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleExperienceActions_updateCandidatesAndPromoteVerifiedSkill(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        ExperienceStore store = new ExperienceStore(workspace);

        try {
            ExperienceEntry verifyCandidate = store.addCandidate(experience("Verify Me"));
            TestExchange verify = postExchangeRaw("/console/api/experiences/" + verifyCandidate.id() + "/verify", "");
            ConsoleController.experienceActionsHandler(app).handle(verify);
            assertEquals(200, verify.getResponseCode(), verify.responseText());
            assertTrue(verify.responseText().contains("\"action\":\"experience.verify\""), verify.responseText());
            assertTrue(verify.responseText().contains("Origin/Referer header missing"), verify.responseText());
            assertEquals(ExperienceStatus.VERIFIED, store.find(verifyCandidate.id()).status());

            TestExchange verifyAgain = postExchangeRaw("/console/api/experiences/" + verifyCandidate.id() + "/verify", "");
            ConsoleController.experienceActionsHandler(app).handle(verifyAgain);
            assertEquals(409, verifyAgain.getResponseCode(), verifyAgain.responseText());
            String auditsAfterConflict = handleGet(ConsoleController.actionsHandler(app), "/console/api/actions");
            assertTrue(auditsAfterConflict.contains("\"action\":\"experience.verify\""), auditsAfterConflict);
            assertTrue(auditsAfterConflict.contains("\"result\":\"SUCCESS\""), auditsAfterConflict);
            assertTrue(auditsAfterConflict.contains("\"result\":\"CONFLICT\""), auditsAfterConflict);

            ExperienceEntry rejectCandidate = store.addCandidate(experience("Reject Me"));
            TestExchange reject = postExchangeRaw("/console/api/experiences/" + rejectCandidate.id() + "/reject", "");
            ConsoleController.experienceActionsHandler(app).handle(reject);
            assertEquals(200, reject.getResponseCode(), reject.responseText());
            assertEquals(ExperienceStatus.REJECTED, store.find(rejectCandidate.id()).status());

            ExperienceEntry skillCandidate = store.addCandidate(experience("Skill Me"));
            TestExchange promoteCandidate = postExchangeRaw("/console/api/experiences/" + skillCandidate.id() + "/promote-skill", "");
            ConsoleController.experienceActionsHandler(app).handle(promoteCandidate);
            assertEquals(409, promoteCandidate.getResponseCode(), promoteCandidate.responseText());

            ExperienceEntry verified = store.verify(skillCandidate.id());
            TestExchange promote = postExchangeRaw("/console/api/experiences/" + verified.id() + "/promote-skill", "");
            ConsoleController.experienceActionsHandler(app).handle(promote);
            assertEquals(200, promote.getResponseCode(), promote.responseText());
            assertTrue(promote.responseText().contains("\"action\":\"experience.promoteSkill\""), promote.responseText());
            assertTrue(promote.responseText().contains("\"skillName\""), promote.responseText());
            assertTrue(Files.exists(workspace.resolve("skills").resolve("generated")));
            Path generatedSkill = Files.list(workspace.resolve("skills").resolve("generated")).findFirst().orElseThrow();
            Files.writeString(generatedSkill, "sentinel");
            TestExchange promoteAgain = postExchangeRaw("/console/api/experiences/" + verified.id() + "/promote-skill", "");
            ConsoleController.experienceActionsHandler(app).handle(promoteAgain);
            assertEquals(200, promoteAgain.getResponseCode(), promoteAgain.responseText());
            assertTrue(promoteAgain.responseText().contains("\"alreadyExists\":true"), promoteAgain.responseText());
            assertEquals("sentinel", Files.readString(generatedSkill));

            TestExchange getWrite = getExchange("/console/api/experiences/" + verified.id() + "/reject");
            ConsoleController.experienceActionsHandler(app).handle(getWrite);
            assertEquals(405, getWrite.getResponseCode(), getWrite.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleApprovalActions_listApproveRejectAndRedactSensitiveArgs(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            ApprovalRequest approveRequest = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("write requires approval"), "", "write_file", List.of("a.txt")),
                    "write_file",
                    Map.of("path", "a.txt", "api_token", "secret-token", "nested", Map.of("password", "secret-password")),
                    "session-1"
            );
            ApprovalRequest rejectRequest = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.HIGH, List.of("exec requires approval"), "deploy", "exec", List.of()),
                    "exec",
                    Map.of("command", "deploy", "secret", "hidden"),
                    "session-2"
            );

            String list = handleGet(ConsoleController.approvalsHandler(app), "/console/api/approvals");
            assertTrue(list.contains(approveRequest.requestId()), list);
            assertTrue(list.contains(rejectRequest.requestId()), list);
            assertTrue(list.contains("[REDACTED]"), list);
            assertFalse(list.contains("secret-token"), list);
            assertFalse(list.contains("secret-password"), list);
            assertFalse(list.contains("\"hidden\""), list);

            TestExchange approve = postExchangeRaw("/console/api/approvals/" + approveRequest.requestId() + "/approve", "");
            ConsoleController.approvalsHandler(app).handle(approve);
            assertEquals(200, approve.getResponseCode(), approve.responseText());
            assertTrue(approve.responseText().contains("approved but not executed"), approve.responseText());
            assertTrue(approve.responseText().contains("\"executed\":false"), approve.responseText());
            assertTrue(approve.responseText().contains("[REDACTED]"), approve.responseText());
            assertFalse(approve.responseText().contains("secret-token"), approve.responseText());
            assertFalse(approve.responseText().contains("secret-password"), approve.responseText());
            assertEquals(ApprovalRequest.ApprovalStatus.APPROVED, loop.getApprovalService().find(approveRequest.requestId()).status());
            assertFalse(loop.getApprovalService().find(approveRequest.requestId()).consumed());

            TestExchange approveAgain = postExchangeRaw("/console/api/approvals/" + approveRequest.requestId() + "/approve", "");
            ConsoleController.approvalsHandler(app).handle(approveAgain);
            assertEquals(409, approveAgain.getResponseCode(), approveAgain.responseText());

            TestExchange reject = postExchangeRaw("/console/api/approvals/" + rejectRequest.requestId() + "/reject", "");
            ConsoleController.approvalsHandler(app).handle(reject);
            assertEquals(200, reject.getResponseCode(), reject.responseText());
            assertEquals(ApprovalRequest.ApprovalStatus.REJECTED, loop.getApprovalService().find(rejectRequest.requestId()).status());

            TestExchange missing = postExchangeRaw("/console/api/approvals/missing/approve", "");
            ConsoleController.approvalsHandler(app).handle(missing);
            assertEquals(404, missing.getResponseCode(), missing.responseText());

            TestExchange getWrite = getExchange("/console/api/approvals/" + rejectRequest.requestId() + "/approve");
            ConsoleController.approvalsHandler(app).handle(getWrite);
            assertEquals(405, getWrite.getResponseCode(), getWrite.responseText());

            String actions = handleGet(ConsoleController.actionsHandler(app), "/console/api/actions");
            assertTrue(actions.contains("\"action\":\"approval.approve\""), actions);
            assertTrue(actions.contains("\"action\":\"approval.reject\""), actions);
            assertTrue(actions.contains("\"result\":\"CONFLICT\""), actions);
            assertFalse(actions.contains("secret-token"), actions);
            assertFalse(actions.contains("secret-password"), actions);
            assertFalse(actions.contains("\"hidden\""), actions);
        } finally {
            loop.stop();
        }
    }

    @Test
    void approvalApplicationService_modesKeepCliAndConsoleApprovalSemanticsConsistent(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            ApprovalRequest consoleRequest = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("write requires approval"), "", "write_file", List.of("console.txt")),
                    "write_file",
                    Map.of("path", "console.txt", "content", "console\n"),
                    "console-session"
            );
            TestExchange approve = postExchangeRaw("/console/api/approvals/" + consoleRequest.requestId() + "/approve", "");
            ConsoleController.approvalsHandler(app).handle(approve);

            assertEquals(200, approve.getResponseCode(), approve.responseText());
            assertTrue(approve.responseText().contains("approved but not executed"), approve.responseText());
            assertFalse(Files.exists(workspace.resolve("console.txt")));
            assertFalse(loop.getApprovalService().find(consoleRequest.requestId()).consumed());

            ApprovalRequest cliRequest = loop.getApprovalService().createRequest(
                    RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("write requires approval"), "", "write_file", List.of("cli.txt")),
                    "write_file",
                    Map.of("path", "cli.txt", "content", "cli\n"),
                    "cli-session"
            );
            OutboundMessage cliApproved = loop.processDirect("/approve " + cliRequest.requestId(), "cli:direct");

            assertTrue(cliApproved.getContent().contains("已批准并恢复执行"), cliApproved.getContent());
            assertEquals("cli\n", Files.readString(workspace.resolve("cli.txt")));
            assertTrue(loop.getApprovalService().find(cliRequest.requestId()).consumed());
        } finally {
            loop.stop();
        }
    }

    @Test
    void approvalApplicationServiceRejectsExpiredRepeatedAndRejectedConsume() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-03T00:00:00Z"), ZoneOffset.UTC);
        ApprovalService expiredService = new ApprovalService(Duration.ZERO, fixedClock);
        ApprovalApplicationService expiredApp = new ApprovalApplicationService(expiredService, null, null);
        ApprovalRequest expired = expiredService.createRequest(
                RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("expired"), "", "write_file", List.of("expired.txt"))
        );
        ApprovalRequest expiredExecutable = expiredService.createRequest(
                RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("expired executable"), "", "write_file", List.of("expired-exec.txt")),
                "write_file",
                Map.of("path", "expired-exec.txt", "content", "expired\n"),
                "session-expired"
        );

        assertThrows(IllegalStateException.class, () -> expiredApp.approveOnly(expired.requestId()));
        assertThrows(IllegalStateException.class, () -> expiredApp.approveAndExecute(expiredExecutable.requestId()));

        ApprovalService service = new ApprovalService(Duration.ofMinutes(30), fixedClock);
        ApprovalApplicationService app = new ApprovalApplicationService(service, null, null);
        ApprovalRequest repeated = service.createRequest(
                RiskAssessment.of(CommandRiskLevel.HIGH, List.of("danger"), "rm file", "exec", List.of("file"))
        );
        app.approveOnly(repeated.requestId());
        assertThrows(IllegalStateException.class, () -> app.approveOnly(repeated.requestId()));

        ApprovalRequest rejected = service.createRequest(
                RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("write"), "", "write_file", List.of("rejected.txt")),
                "write_file",
                Map.of("path", "rejected.txt", "content", "no\n"),
                "session-1"
        );
        app.reject(rejected.requestId());

        assertThrows(IllegalStateException.class, () -> service.consumeApprovedToolCall(rejected.requestId()));
    }

    @Test
    void consolePostActions_requireBearerTokenWhenConfigured(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "console-token", config, null, workspace);
        ExperienceEntry candidate = new ExperienceStore(workspace).addCandidate(experience("Auth Me"));

        try {
            TestExchange unauthorized = postExchangeRaw("/console/api/experiences/" + candidate.id() + "/verify", "");
            ConsoleController.experienceActionsHandler(app).handle(unauthorized);
            assertEquals(401, unauthorized.getResponseCode(), unauthorized.responseText());
            assertTrue(new ConsoleActionAuditService(workspace).recent(5).stream()
                    .anyMatch(record -> "UNAUTHORIZED".equals(record.result()) && "experience.verify".equals(record.action())));

            TestExchange authorized = postExchangeRaw("/console/api/experiences/" + candidate.id() + "/verify", "");
            authorized.getRequestHeaders().set("Authorization", "Bearer console-token");
            ConsoleController.experienceActionsHandler(app).handle(authorized);
            assertEquals(200, authorized.getResponseCode(), authorized.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void consolePostActions_rejectInvalidOriginAndDoNotAffectChatApi(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        ExperienceEntry candidate = new ExperienceStore(workspace).addCandidate(experience("Origin Me"));

        try {
            TestExchange invalidOrigin = postExchangeRaw("/console/api/experiences/" + candidate.id() + "/verify", "");
            invalidOrigin.getRequestHeaders().set("Origin", "https://evil.example");
            ConsoleController.experienceActionsHandler(app).handle(invalidOrigin);
            assertEquals(403, invalidOrigin.getResponseCode(), invalidOrigin.responseText());
            assertEquals(ExperienceStatus.CANDIDATE, new ExperienceStore(workspace).find(candidate.id()).status());
            assertTrue(new ConsoleActionAuditService(workspace).recent(5).stream()
                    .anyMatch(record -> "UNAUTHORIZED".equals(record.result()) && record.message().contains("Origin")));

            TestExchange validOrigin = postExchangeRaw("/console/api/experiences/" + candidate.id() + "/verify", "");
            validOrigin.getRequestHeaders().set("Origin", "http://127.0.0.1:8080");
            ConsoleController.experienceActionsHandler(app).handle(validOrigin);
            assertEquals(200, validOrigin.getResponseCode(), validOrigin.responseText());

            TestExchange chatExchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));
            chatExchange.getRequestHeaders().set("Origin", "https://evil.example");
            new RicbotApiServer.ChatCompletionsHandler(app).handle(chatExchange);
            assertEquals(200, chatExchange.getResponseCode(), chatExchange.responseText());
            assertTrue(chatExchange.responseText().contains("\"content\":\"pong\""), chatExchange.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void consolePostActions_rateLimitRepeatedActions(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        var handler = ConsoleController.approvalsHandler(app);

        try {
            int lastCode = -1;
            String lastBody = "";
            for (int i = 0; i < 21; i++) {
                ApprovalRequest request = loop.getApprovalService().createRequest(
                        RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("rate limit test"), "", "write_file", List.of("file-" + i + ".txt")),
                        "write_file",
                        Map.of("path", "file-" + i + ".txt"),
                        "rate-limit-" + i
                );
                TestExchange exchange = postExchangeRaw("/console/api/approvals/" + request.requestId() + "/reject", "");
                exchange.setRemoteAddress(new InetSocketAddress("127.0.0.2", 12345));
                handler.handle(exchange);
                lastCode = exchange.getResponseCode();
                lastBody = exchange.responseText();
            }

            assertEquals(429, lastCode, lastBody);
            assertTrue(lastBody.contains("rate_limit_exceeded"), lastBody);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleWorkspaceActions_createChangeSetAndDiscardManagedWorktree(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession session = createManagedWorktree(workspace, store, "workspace_console", "task_console");
        Path worktree = Path.of(session.workspacePath());
        Files.writeString(worktree.resolve("README.md"), "initial\nconsole change\n");

        try {
            TestExchange create = postExchangeRaw("/console/api/workspaces/task_console/change-create", "");
            ConsoleController.workspaceActionsHandler(app).handle(create);
            assertEquals(200, create.getResponseCode(), create.responseText());
            assertTrue(create.responseText().contains("\"action\":\"workspace.change_create\""), create.responseText());
            assertTrue(create.responseText().contains("\"changedFiles\""), create.responseText());
            assertTrue(create.responseText().contains("README.md"), create.responseText());
            assertTrue(Files.isDirectory(workspace.resolve(".changesets")));

            TestExchange missingConfirm = postExchangeRaw("/console/api/workspaces/" + session.id() + "/discard", "");
            ConsoleController.workspaceActionsHandler(app).handle(missingConfirm);
            assertEquals(409, missingConfirm.getResponseCode(), missingConfirm.responseText());
            assertTrue(missingConfirm.responseText().contains("confirm=true"), missingConfirm.responseText());
            assertEquals(WorkspaceSessionStatus.ACTIVE, store.load(session.id()).status());

            TestExchange discard = postExchangeRaw("/console/api/workspaces/" + session.id() + "/discard", "{\"confirm\":true}");
            ConsoleController.workspaceActionsHandler(app).handle(discard);
            assertEquals(200, discard.getResponseCode(), discard.responseText());
            assertTrue(discard.responseText().contains("\"action\":\"workspace.discard\""), discard.responseText());
            assertEquals(WorkspaceSessionStatus.DISCARDED, store.load(session.id()).status());

            String actions = handleGet(ConsoleController.actionsHandler(app), "/console/api/actions");
            assertTrue(actions.contains("\"action\":\"workspace.change_create\""), actions);
            assertTrue(actions.contains("\"action\":\"workspace.discard\""), actions);
            assertTrue(actions.contains("\"targetType\":\"WORKSPACE\""), actions);
            assertTrue(actions.contains("workspaceId=" + session.id()), actions);
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleWorkspaceActions_reportNoDiffAndRejectUnsafeTargets(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession clean = createManagedWorktree(workspace, store, "workspace_clean", "task_clean");

        try {
            TestExchange noDiff = postExchangeRaw("/console/api/workspaces/" + clean.id() + "/change-create", "");
            ConsoleController.workspaceActionsHandler(app).handle(noDiff);
            assertEquals(409, noDiff.getResponseCode(), noDiff.responseText());
            assertTrue(noDiff.responseText().contains("no changes"), noDiff.responseText());

            Path unmanagedPath = workspace.resolve(".workspaces").resolve("workspace_unmanaged_console");
            git(workspace, "worktree", "add", "-b", "ricbot/workspace_unmanaged_console", unmanagedPath.toString());
            store.save(new WorkspaceSession(
                    "workspace_unmanaged_console",
                    WorkspaceBackendType.GIT_WORKTREE,
                    workspace.toString(),
                    unmanagedPath.toString(),
                    "ricbot/workspace_unmanaged_console",
                    "unmanaged",
                    WorkspaceSessionStatus.ACTIVE,
                    null,
                    null,
                    Map.of("taskId", "task_unmanaged_console")
            ));
            TestExchange unmanaged = postExchangeRaw("/console/api/workspaces/task_unmanaged_console/discard", "{\"confirm\":true}");
            ConsoleController.workspaceActionsHandler(app).handle(unmanaged);
            assertEquals(409, unmanaged.getResponseCode(), unmanaged.responseText());
            assertTrue(unmanaged.responseText().contains("not managed"), unmanaged.responseText());

            TestExchange traversal = postExchangeRaw("/console/api/workspaces/../discard", "{\"confirm\":true}");
            ConsoleController.workspaceActionsHandler(app).handle(traversal);
            assertEquals(404, traversal.getResponseCode(), traversal.responseText());

            TestExchange getWrite = getExchange("/console/api/workspaces/" + clean.id() + "/discard");
            ConsoleController.workspaceActionsHandler(app).handle(getWrite);
            assertEquals(405, getWrite.getResponseCode(), getWrite.responseText());
            assertEquals(WorkspaceSessionStatus.ACTIVE, store.load(clean.id()).status());
        } finally {
            loop.stop();
        }
    }

    @Test
    void consoleWorkspaceActions_reuseAuthAndOriginProtection(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        AgentLoop loop = buildLoop(workspace);
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession session = createManagedWorktree(workspace, store, "workspace_auth", "task_auth");
        Files.writeString(Path.of(session.workspacePath()).resolve("README.md"), "initial\nauth change\n");

        try {
            var tokenApp = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "console-token", config, null, workspace);
            TestExchange unauthorized = postExchangeRaw("/console/api/workspaces/" + session.id() + "/change-create", "");
            ConsoleController.workspaceActionsHandler(tokenApp).handle(unauthorized);
            assertEquals(401, unauthorized.getResponseCode(), unauthorized.responseText());

            var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
            TestExchange invalidOrigin = postExchangeRaw("/console/api/workspaces/" + session.id() + "/change-create", "");
            invalidOrigin.getRequestHeaders().set("Origin", "https://evil.example");
            ConsoleController.workspaceActionsHandler(app).handle(invalidOrigin);
            assertEquals(403, invalidOrigin.getResponseCode(), invalidOrigin.responseText());
            assertFalse(Files.isDirectory(workspace.resolve(".changesets")));

            String actions = handleGet(ConsoleController.actionsHandler(app), "/console/api/actions");
            assertTrue(actions.contains("\"action\":\"workspace.change_create\""), actions);
            assertTrue(actions.contains("\"result\":\"UNAUTHORIZED\""), actions);
        } finally {
            loop.stop();
        }
    }

    @Test
    void channelWebhooks_handleFeishuChallengeAndTextMessage(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getChannels().getFeishu().setWebhookToken("feishu-token");
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            TestExchange challenge = postExchangeRaw("/webhook/feishu", """
                    {"type":"url_verification","token":"feishu-token","challenge":"challenge-code"}
                    """);
            ChannelWebhookController.handler(app, "feishu").handle(challenge);
            assertEquals(200, challenge.getResponseCode(), challenge.responseText());
            assertTrue(challenge.responseText().contains("\"challenge\":\"challenge-code\""), challenge.responseText());

            TestExchange message = postExchangeRaw("/webhook/feishu", """
                    {
                      "token": "feishu-token",
                      "header": {"event_id": "feishu-evt-1", "event_type": "im.message.receive_v1"},
                      "event": {
                        "sender": {"sender_id": {"user_id": "user-1"}},
                        "message": {
                          "message_id": "msg-1",
                          "chat_id": "chat-1",
                          "message_type": "text",
                          "content": "{\\"text\\":\\"hello feishu\\"}"
                        }
                      }
                    }
                    """);
            ChannelWebhookController.handler(app, "feishu").handle(message);
            assertEquals(200, message.getResponseCode(), message.responseText());

            var inbound = loop.getBus().consumeInbound(1, TimeUnit.SECONDS);
            assertNotNull(inbound);
            assertEquals("feishu", inbound.getChannel());
            assertEquals("user-1", inbound.getSenderId());
            assertEquals("chat-1", inbound.getChatId());
            assertEquals("hello feishu", inbound.getContent());
            assertEquals("feishu:chat-1", inbound.getSessionKey());
            assertEquals("msg-1", inbound.getMetadata().get("feishu_message_id"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void channelWebhooks_handleDingTalkTextSignatureAndDedup(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getChannels().getDingtalk().setWebhookSecret("ding-secret");
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);
        String timestamp = "1710000000000";
        String sign = dingtalkSign(timestamp, "ding-secret");
        String path = "/webhook/dingtalk?timestamp=" + timestamp + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        String body = """
                {
                  "msgId": "ding-msg-1",
                  "msgtype": "text",
                  "senderStaffId": "user-2",
                  "senderNick": "Bob",
                  "conversationId": "conv-1",
                  "text": {"content": "hello dingtalk"}
                }
                """;

        try {
            TestExchange message = postExchangeRaw(path, body);
            ChannelWebhookController.handler(app, "dingtalk").handle(message);
            assertEquals(200, message.getResponseCode(), message.responseText());
            var inbound = loop.getBus().consumeInbound(1, TimeUnit.SECONDS);
            assertNotNull(inbound);
            assertEquals("dingtalk", inbound.getChannel());
            assertEquals("user-2", inbound.getSenderId());
            assertEquals("conv-1", inbound.getChatId());
            assertEquals("hello dingtalk", inbound.getContent());
            assertEquals("dingtalk:conv-1", inbound.getSessionKey());

            TestExchange duplicate = postExchangeRaw(path, body);
            ChannelWebhookController.handler(app, "dingtalk").handle(duplicate);
            assertEquals(200, duplicate.getResponseCode(), duplicate.responseText());
            assertTrue(duplicate.responseText().contains("\"duplicate\":true"), duplicate.responseText());
            assertNull(loop.getBus().consumeInbound(100, TimeUnit.MILLISECONDS));

            TestExchange badSign = postExchangeRaw("/webhook/dingtalk?timestamp=" + timestamp + "&sign=bad", body.replace("ding-msg-1", "ding-msg-2"));
            ChannelWebhookController.handler(app, "dingtalk").handle(badSign);
            assertEquals(403, badSign.getResponseCode(), badSign.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void channelWebhooks_handleWecomTextAndRejectBadToken(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        config.getChannels().getWecom().setToken("wecom-token");
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            TestExchange message = postExchangeRaw("/webhook/wecom?token=wecom-token", """
                    {
                      "msgid": "wecom-msg-1",
                      "msgtype": "text",
                      "from_userid": "external-1",
                      "roomid": "room-1",
                      "content": "hello wecom"
                    }
                    """);
            ChannelWebhookController.handler(app, "wecom").handle(message);
            assertEquals(200, message.getResponseCode(), message.responseText());
            var inbound = loop.getBus().consumeInbound(1, TimeUnit.SECONDS);
            assertNotNull(inbound);
            assertEquals("wecom", inbound.getChannel());
            assertEquals("external-1", inbound.getSenderId());
            assertEquals("room-1", inbound.getChatId());
            assertEquals("hello wecom", inbound.getContent());
            assertEquals("wecom:room-1", inbound.getSessionKey());

            TestExchange badToken = postExchangeRaw("/webhook/wecom?token=bad", """
                    {"msgid":"wecom-msg-2","msgtype":"text","from_userid":"external-1","content":"blocked"}
                    """);
            ChannelWebhookController.handler(app, "wecom").handle(badToken);
            assertEquals(403, badToken.getResponseCode(), badToken.responseText());
        } finally {
            loop.stop();
        }
    }

    @Test
    void channelWebhooks_handleUnsupportedAndBadJsonWithoutBreakingApi(@TempDir Path workspace) throws Exception {
        AgentLoop loop = buildLoopNoStart(workspace);
        Config config = new Config();
        var app = new RicbotApiAppContext(loop, "gpt-4o-mini", 20_000, "127.0.0.1", "", config, null, workspace);

        try {
            TestExchange unsupported = postExchangeRaw("/webhook/feishu", """
                    {
                      "header": {"event_id": "feishu-image-1"},
                      "event": {
                        "sender": {"sender_id": {"user_id": "user-1"}},
                        "message": {"message_id": "img-1", "chat_id": "chat-1", "message_type": "image"}
                      }
                    }
                    """);
            ChannelWebhookController.handler(app, "feishu").handle(unsupported);
            assertEquals(200, unsupported.getResponseCode(), unsupported.responseText());
            assertTrue(unsupported.responseText().contains("unsupported message type"), unsupported.responseText());
            assertNull(loop.getBus().consumeInbound(100, TimeUnit.MILLISECONDS));

            TestExchange badJson = postExchangeRaw("/webhook/feishu", "{bad json");
            ChannelWebhookController.handler(app, "feishu").handle(badJson);
            assertEquals(400, badJson.getResponseCode(), badJson.responseText());

            TestExchange console = getExchange("/console");
            ConsoleController.pageHandler(app).handle(console);
            assertEquals(200, console.getResponseCode(), console.responseText());

            TestExchange chatExchange = postExchange("/v1/chat/completions", Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(Map.of("role", "user", "content", "ping"))
            ));
            new RicbotApiServer.ChatCompletionsHandler(app).handle(chatExchange);
            assertEquals(200, chatExchange.getResponseCode(), chatExchange.responseText());

            String health = handleGet(new RicbotApiServer.HealthHandler(app), "/health");
            assertTrue(health.contains("\"status\":\"ok\""), health);
        } finally {
            loop.stop();
        }
    }

    private static TestExchange postExchange(String path, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        return new TestExchange("POST", URI.create("http://localhost" + path), json);
    }

    private static String dingtalkSign(String timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
    }

    private static WorkspaceSession createManagedWorktree(
            Path workspace,
            WorkspaceSessionStore store,
            String id,
            String taskId
    ) {
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace, store);
        WorkspaceSession created = backend.createSession(workspace, "console workspace", id);
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(created.metadata());
        metadata.put("taskId", taskId);
        metadata.put("teamSessionId", "team_console");
        return store.save(created.withMetadata(metadata));
    }

    private static ExperienceEntry experience(String title) {
        return ExperienceEntry.candidate(
                ExperienceType.PROJECT_CONVENTION,
                title,
                "Use the project convention carefully.",
                "When a similar project convention appears.",
                "test evidence",
                "test",
                title,
                List.of("README.md"),
                List.of("sh ./mvnw -q test"),
                0.8d
        );
    }

    private static String handleGet(com.sun.net.httpserver.HttpHandler handler, String path) throws Exception {
        TestExchange exchange = getExchange(path);
        handler.handle(exchange);
        assertEquals(200, exchange.getResponseCode(), exchange.responseText());
        return exchange.responseText();
    }

    private static List<ConsoleEvent> consoleEvents(Path workspace) {
        return new JsonlConsoleEventStore(workspace).listBySession("", "", "", 500);
    }

    private static List<ConsoleEvent> consoleEvents(Path workspace, String sessionId) {
        return new JsonlConsoleEventStore(workspace).listBySession(sessionId, "", "", 500);
    }

    private static ConsoleEvent consoleEvent(String id, String sessionId, String category, String name) {
        return consoleEvent(id, sessionId, "run-test", category, name, "INFO", "2026-06-04T00:00:00Z", Map.of("name", name));
    }

    private static ConsoleEvent consoleEvent(
            String id,
            String sessionId,
            String runId,
            String category,
            String name,
            String status,
            String time,
            Map<String, Object> payload
    ) {
        return new ConsoleEvent(
                id,
                sessionId,
                runId,
                category + "_event",
                name,
                category,
                status,
                time,
                name,
                name,
                "console",
                "console_event_store",
                payload
        );
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while (text != null && needle != null && !needle.isEmpty()) {
            index = text.indexOf(needle, index);
            if (index < 0) {
                return count;
            }
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String postRun(RicbotApiAppContext app, String path, String input) throws Exception {
        TestExchange exchange = postExchange(path, Map.of("input", input));
        ConsoleController.sessionDetailHandler(app, path.startsWith("/console/api/") ? "/console/api/sessions/" : "/api/console/sessions/").handle(exchange);
        assertEquals(200, exchange.getResponseCode(), exchange.responseText());
        return exchange.responseText();
    }

    private static String cancelRun(RicbotApiAppContext app, String runId) throws Exception {
        TestExchange exchange = postExchangeRaw("/api/console/runs/" + runId + "/cancel", "");
        ConsoleController.runStatusHandler(app, "/api/console/runs/").handle(exchange);
        assertEquals(200, exchange.getResponseCode(), exchange.responseText());
        return exchange.responseText();
    }

    private static String waitForRunStatus(RicbotApiAppContext app, String runId, String expectedStatus) throws Exception {
        String body = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            body = handleGet(ConsoleController.runStatusHandler(app, "/api/console/runs/"), "/api/console/runs/" + runId);
            if (body.contains("\"status\":\"" + expectedStatus + "\"")) {
                return body;
            }
            Thread.sleep(25L);
        }
        fail("run did not reach status " + expectedStatus + ": " + body);
        return body;
    }

    private static String waitForRunStatusField(RicbotApiAppContext app, String runId, String expectedFragment) throws Exception {
        String body = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            body = handleGet(ConsoleController.runStatusHandler(app, "/api/console/runs/"), "/api/console/runs/" + runId);
            if (body.contains(expectedFragment)) {
                return body;
            }
            Thread.sleep(25L);
        }
        fail("run status did not contain " + expectedFragment + ": " + body);
        return body;
    }

    private static String runIdFromStart(String responseText) throws Exception {
        Map<String, Object> start = MAPPER.readValue(responseText, new TypeReference<>() {});
        return String.valueOf(start.get("runId"));
    }

    private static Map<String, Object> runTrace(List<Map<String, Object>> events) {
        return Map.of(
                "run_id", "test-run",
                "started_at", "2026-06-04T00:00:00Z",
                "ended_at", "2026-06-04T00:00:03Z",
                "iterations", 1,
                "stop_reason", "stop",
                "events", events
        );
    }

    private static Map<String, Object> runEvent(String type, String at, Map<String, Object> values) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", type);
        event.put("at", at);
        if (values != null) {
            event.putAll(values);
        }
        return event;
    }

    private static Config configuredOpenAiConfig(Path workspace) {
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("gpt-4o-mini");
        config.getProviders().getOpenai().setApiKey("test-api-key");
        return config;
    }

    private static TestExchange postExchangeRaw(String path, String body) {
        return new TestExchange("POST", URI.create("http://localhost" + path), body);
    }

    private static TestExchange getExchange(String path) {
        return new TestExchange("GET", URI.create("http://localhost" + path), "");
    }

    private static AgentLoop buildLoopNoStart(Path workspace) {
        return buildLoopInternal(workspace, false);
    }

    private static AgentLoop buildLoop(Path workspace) {
        return buildLoopInternal(workspace, true);
    }

    private static AgentLoop buildLoopInternal(Path workspace, boolean start) {
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
        if (start) {
            loop.start();
        }
        return loop;
    }

    private static void initGitRepo(Path workspace) throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.name", "Test");
        git(workspace, "config", "user.email", "test@example.com");
        Files.writeString(workspace.resolve("README.md"), "initial\n");
        git(workspace, "add", "README.md");
        git(workspace, "commit", "-m", "init");
    }

    private static String git(Path workspace, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("git failed: " + String.join(" ", command) + "\n" + stderr + stdout);
        }
        return stdout;
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

    private static AgentLoop buildThrowingLoop(Path workspace) {
        return new AgentLoop(
                new MessageBus(),
                new LLMProvider("k", "http://localhost") {
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
                        return new LLMResponse().setContent("unused").setFinishReason("stop");
                    }
                },
                workspace,
                "gpt-4o-mini",
                5,
                2000,
                50,
                10_000,
                "standard",
                new Config.WebToolsConfig(),
                new Config.ExecToolConfig(),
                Map.of(),
                true,
                new SessionManager(workspace),
                "UTC",
                false,
                List.of(),
                0,
                new Config.DreamConfig()
        ) {
            @Override
            public OutboundMessage processDirect(
                    String content,
                    String sessionKey,
                    String channel,
                    String chatId,
                    Map<String, Object> metadata,
                    List<AgentHook> requestHooks
            ) throws Exception {
                throw new IllegalStateException("forced console failure");
            }
        };
    }

    private static final class TestExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final URI requestUri;
        private final String method;
        private InputStream requestBody;
        private InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 12345);
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

        void setRemoteAddress(InetSocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
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
            return remoteAddress;
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
