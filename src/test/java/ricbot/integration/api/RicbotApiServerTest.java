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
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceSkillPromoter;
import ricbot.domain.experience.ExperienceStatus;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceType;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.session.SessionManager;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.infra.config.Config;
import ricbot.integration.api.console.ConsoleActionAuditService;
import ricbot.integration.api.console.ConsoleController;
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
