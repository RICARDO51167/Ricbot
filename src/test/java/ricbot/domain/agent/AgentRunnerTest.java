package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.config.ModelCapability;
import ricbot.domain.config.ProviderCapability;
import ricbot.domain.config.ProviderCapabilityResolver;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.AgentRunner;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.OpenAIResponsesSupport;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.ReadFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class AgentRunnerTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void runControllerTracksTurnsCancellationAndStopReason() {
        AgentRunController controller = AgentRunController.withMaxTurns(2);

        assertTrue(controller.canContinue());
        controller.recordTurn();
        assertTrue(controller.canContinue());
        controller.recordTurn();

        assertFalse(controller.canContinue());
        assertEquals("max_turns", controller.stopReason().orElseThrow());

        AgentRunController cancelled = AgentRunController.withMaxTurns(5);
        cancelled.cancel("user stop");

        assertFalse(cancelled.canContinue());
        assertTrue(cancelled.cancelled());
        assertEquals("cancelled: user stop", cancelled.stopReason().orElseThrow());
    }

    @Test
    void runControllerChecksDeadlineAndTimeoutStopReason() {
        AgentRunController beforeDeadline = AgentRunController.withMaxTurnsAndTimeout(
                3,
                Duration.ofSeconds(5),
                FIXED_CLOCK
        );

        assertTrue(beforeDeadline.canContinue());
        assertFalse(beforeDeadline.timedOut());
        assertEquals(Instant.parse("2026-06-03T00:00:05Z"), beforeDeadline.deadline().orElseThrow());

        AgentRunController timedOut = AgentRunController.withMaxTurnsAndTimeout(
                3,
                Duration.ZERO,
                FIXED_CLOCK
        );

        assertTrue(timedOut.timedOut());
        assertFalse(timedOut.canContinue());
        assertEquals("timeout", timedOut.stopReason().orElseThrow());
    }

    @Test
    void runner_maxIterationsBehaviorDoesNotRegress() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());
        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                return new LLMResponse()
                        .setContent("")
                        .setToolCalls(List.of(new ToolCallRequest("call_" + calls.get(), "echo", Map.of("value", "loop"))))
                        .setFinishReason("tool_calls");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "loop")))
                .setTools(tools)
                .setModel("gpt-4o-mini")
                .setMaxIterations(2)
                .setMaxIterationsMessage("max reached"));

        assertEquals(2, calls.get());
        assertEquals(2, result.getIterations());
        assertEquals("empty_spin", result.getStopReason());
        assertEquals("max reached", result.getFinalContent());
        assertTrue(result.getRunEvents().stream().anyMatch(event ->
                "run_stop".equals(event.get("type"))
                        && "empty_spin".equals(event.get("stop_reason"))));
    }

    @Test
    void runner_withoutTimeout_behaviorDoesNotRegress() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                return new LLMResponse().setContent("done").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "hello")))
                .setModel("gpt-4o-mini")
                .setMaxIterations(2));

        assertEquals("done", result.getFinalContent());
        assertEquals("stop", result.getStopReason());
        assertEquals(1, result.getIterations());
        assertEquals(1, calls.get());
        assertEventOrder(result.getRunEvents(), "run_start", "model_request", "model_response", "run_stop", "run_finish");
        assertTrue(result.getRunEvents().stream().anyMatch(event ->
                "run_stop".equals(event.get("type"))
                        && "stop".equals(event.get("stop_reason"))));
    }

    @Test
    void runner_timeoutStopsBeforeNextTurn() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());
        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                return new LLMResponse()
                        .setContent("")
                        .setToolCalls(List.of(new ToolCallRequest("call_1", "echo", Map.of("value", "one"))))
                        .setFinishReason("tool_calls");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "timeout")))
                .setTools(tools)
                .setModel("gpt-4o-mini")
                .setMaxIterations(5)
                .setRunTimeout(Duration.ZERO));

        assertEquals(0, calls.get());
        assertEquals(0, result.getIterations());
        assertEquals("timeout", result.getStopReason());
        assertTrue(result.getFinalContent().contains("运行超时"), result.getFinalContent());
        assertTrue(result.getRunEvents().stream().anyMatch(event -> "run_timeout".equals(event.get("type"))));
        assertTrue(result.getRunEvents().stream().anyMatch(event ->
                "run_stop".equals(event.get("type"))
                        && "timeout".equals(event.get("stop_reason"))));
    }

    @Test
    void runner_recordsRetryMetadataAsRunEvent() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                return new LLMResponse().setContent("recovered").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "assistant", "content", "")))
                .setModel("gpt-4o-mini")
                .setMaxIterations(2)
                .setMetadata(Map.of(
                        "retryReason", "tool_loop",
                        "retryCount", 1
                )));

        assertEquals("recovered", result.getFinalContent());
        assertEquals(1, calls.get());
        assertTrue(result.getRunEvents().stream().anyMatch(event ->
                "run_retry".equals(event.get("type"))
                        && "tool_loop".equals(event.get("retry_reason"))
                        && Integer.valueOf(1).equals(event.get("retry_count"))));
    }

    @Test
    // 测试 AgentRunner 执行工具调用并追加工具结果的功能
    void runner_executesToolCallsAndAppendsToolResults(@TempDir Path workspace) throws Exception {
        // 在临时工作区创建一个名为 hello.txt 的文件，内容为 "hello"
        Files.writeString(workspace.resolve("hello.txt"), "hello");

        // 创建工具注册表
        ToolRegistry tools = new ToolRegistry();
        // 注册读取文件工具，限制在工作区范围内
        tools.register(new ReadFileTool(workspace, workspace, List.of()));

        // 用于跟踪 LLM 调用次数的原子整数
        AtomicInteger calls = new AtomicInteger(0);
        // 创建模拟的 LLM 提供者
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                // 增加调用计数
                int n = calls.incrementAndGet();
                // 第一次调用时，返回一个工具调用请求
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("") // 无直接内容
                            .setToolCalls(List.of(
                                    // 请求读取 hello.txt 文件
                                    new ToolCallRequest("call_1", "read_file", Map.of(
                                            "path", "hello.txt",
                                            "offset", 1,
                                            "limit", 10
                                    ))
                            ))
                            .setFinishReason("tool_calls"); // 结束原因为工具调用
                }

                // 第二次调用时，验证消息中是否包含工具角色
                boolean sawTool = messages.stream().anyMatch(m -> "tool".equals(String.valueOf(m.get("role"))));
                assertTrue(sawTool); // 断言确实看到了工具角色

                // 返回最终响应
                return new LLMResponse()
                        .setContent("done") // 内容为 "done"
                        .setFinishReason("stop"); // 结束原因为停止
            }
        };

        // 创建 AgentRunner 实例
        AgentRunner runner = new AgentRunner(provider);
        // 配置运行规格
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "read it"))) // 初始用户消息
                .setTools(tools) // 设置工具
                .setModel("gpt-4o-mini") // 设置模型
                .setMaxIterations(5) // 最大迭代次数
                .setMaxToolResultChars(10_000) // 最大工具结果字符数
                .setConcurrentTools(false); // 禁用并发工具调用

        // 执行运行
        AgentRunResult result = runner.run(spec);
        // 验证最终内容为 "done"
        assertEquals("done", result.getFinalContent());
        // 验证消息中包含工具角色
        assertTrue(result.getMessages().stream().anyMatch(m -> "tool".equals(String.valueOf(m.get("role")))));
        // 验证使用了 read_file 工具
        assertTrue(result.getToolsUsed().contains("read_file"));
    }

    @Test
    // 测试 AgentRunner 支持并发工具调用且无数据竞争
    void runner_supportsConcurrentToolCalls_withoutDataRaces(@TempDir Path workspace) throws Exception {
        // 在临时工作区创建两个文件 a.txt 和 b.txt
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");

        // 创建工具注册表
        ToolRegistry tools = new ToolRegistry();
        // 注册读取文件工具
        tools.register(new ReadFileTool(workspace, workspace, List.of()));

        // 用于跟踪 LLM 调用次数的原子整数
        AtomicInteger calls = new AtomicInteger(0);
        // 创建模拟的 LLM 提供者
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                // 增加调用计数
                int n = calls.incrementAndGet();
                // 第一次调用时，返回两个并发的工具调用请求
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("") // 无直接内容
                            .setToolCalls(List.of(
                                    // 请求读取 a.txt
                                    new ToolCallRequest("call_1", "read_file", Map.of("path", "a.txt", "offset", 1, "limit", 10)),
                                    // 请求读取 b.txt
                                    new ToolCallRequest("call_2", "read_file", Map.of("path", "b.txt", "offset", 1, "limit", 10))
                            ))
                            .setFinishReason("tool_calls"); // 结束原因为工具调用
                }

                // 第二次调用时，验证消息中工具角色的数量
                long toolCount = messages.stream().filter(m -> "tool".equals(String.valueOf(m.get("role")))).count();
                assertEquals(2, toolCount); // 断言有两个工具结果

                // 返回最终响应
                return new LLMResponse()
                        .setContent("ok") // 内容为 "ok"
                        .setFinishReason("stop"); // 结束原因为停止
            }
        };

        // 创建 AgentRunner 实例
        AgentRunner runner = new AgentRunner(provider);
        // 配置运行规格
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "read both"))) // 初始用户消息
                .setTools(tools) // 设置工具
                .setModel("gpt-4o-mini") // 设置模型
                .setMaxIterations(5) // 最大迭代次数
                .setMaxToolResultChars(10_000) // 最大工具结果字符数
                .setConcurrentTools(true); // 启用并发工具调用

        // 执行运行
        AgentRunResult result = runner.run(spec);
        // 验证最终内容为 "ok"
        assertEquals("ok", result.getFinalContent());
        // 验证使用了两个工具
        assertEquals(2, result.getToolsUsed().size());
        // 验证有两个工具事件
        assertEquals(2, result.getToolEvents().size());
    }

    @Test
    void runner_serializesSideEffectTools_evenWhenConcurrentModeIsEnabled() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override
            public String getName() {
                return "mutate";
            }

            @Override
            public String getDescription() {
                return "side effect";
            }

            @Override
            public List<ToolParam> getParams() {
                return List.of(new ToolParam("value", "string", "value", true));
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return "ok:" + params.get("value");
            }
        });

        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("")
                            .setToolCalls(List.of(
                                    new ToolCallRequest("call_1", "mutate", Map.of("value", "a")),
                                    new ToolCallRequest("call_2", "mutate", Map.of("value", "b"))
                            ))
                            .setFinishReason("tool_calls");
                }
                return new LLMResponse().setContent("ok").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "go")))
                .setTools(tools)
                .setModel("gpt-4o-mini")
                .setMaxIterations(3)
                .setConcurrentTools(true));

        Map<String, Object> batch = result.getRunEvents().stream()
                .filter(e -> "tool_batch".equals(e.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals("sequential", String.valueOf(batch.get("execution_mode")));
        assertEquals("side_effect", String.valueOf(result.getToolEvents().get(0).get("risk")));
    }

    @Test
    void openaiSse_toolCalls_areParsed() throws Exception {
        Stream<String> lines = Stream.of(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]"
        );

        LLMResponse res = OpenAIResponsesSupport.consumeSSE(lines, delta -> {}, r -> {});
        assertEquals("tool_calls", res.getFinishReason());
        assertNotNull(res.getToolCalls());
        assertEquals(1, res.getToolCalls().size());
        assertEquals("list_dir", res.getToolCalls().get(0).getName());
        assertEquals(".", String.valueOf(res.getToolCalls().get(0).getArguments().get("path")));
    }

    @Test
    void runner_stopsImmediately_whenToolErrorIsFatal() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override
            public String getName() {
                return "explode";
            }

            @Override
            public String getDescription() {
                return "Always fails";
            }

            @Override
            public List<ToolParam> getParams() {
                return List.of(new ToolParam("input", "string", "input", false));
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return Map.of("ok", false, "error", "boom");
            }
        });

        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                assertEquals(1, calls.incrementAndGet());
                return new LLMResponse()
                        .setContent("")
                        .setToolCalls(List.of(new ToolCallRequest("call_1", "explode", Map.of("input", "x"))))
                        .setFinishReason("tool_calls");
            }
        };

        AgentRunner runner = new AgentRunner(provider);
        AgentRunResult result = runner.run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "go")))
                .setTools(tools)
                .setModel("gpt-4o-mini")
                .setMaxIterations(3)
                .setFailOnToolError(true)
                .setErrorMessage("tool failed"));

        assertEquals("tool failed", result.getFinalContent());
        assertEquals("tool_error", result.getStopReason());
        assertEquals(1, calls.get());
        assertEquals("error", result.getToolEvents().get(0).get("status"));
    }

    @Test
    void runner_truncatesOversizedToolResults(@TempDir Path workspace) throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ReadFileTool(workspace, workspace, List.of()));
        Files.writeString(workspace.resolve("large.txt"), "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("")
                            .setToolCalls(List.of(new ToolCallRequest("call_1", "read_file", Map.of(
                                    "path", "large.txt",
                                    "offset", 1,
                                    "limit", 200
                            ))))
                            .setFinishReason("tool_calls");
                }

                Map<String, Object> toolMessage = messages.stream()
                        .filter(m -> "tool".equals(String.valueOf(m.get("role"))))
                        .findFirst()
                        .orElseThrow();
                String content = String.valueOf(toolMessage.get("content"));
                assertTrue(content.contains("\"truncated\":true"), content);
                assertTrue(content.contains("\"preview\""), content);

                return new LLMResponse().setContent("ok").setFinishReason("stop");
            }
        };

        AgentRunner runner = new AgentRunner(provider);
        AgentRunResult result = runner.run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "read")))
                .setTools(tools)
                .setModel("gpt-4o-mini")
                .setMaxIterations(3)
                .setMaxToolResultChars(20)
                .setConcurrentTools(false));

        assertEquals("ok", result.getFinalContent());
        assertEquals(2, calls.get());
    }

    @Test
    void runner_usesDirectChatWhenProviderRetryIsDisabled() throws Exception {
        AtomicInteger directCalls = new AtomicInteger(0);
        AtomicInteger retryCalls = new AtomicInteger(0);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                directCalls.incrementAndGet();
                return new LLMResponse().setContent("ok").setFinishReason("stop");
            }

            @Override
            public LLMResponse chatWithRetry(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model
            ) throws Exception {
                retryCalls.incrementAndGet();
                return super.chatWithRetry(messages, tools, model);
            }
        };

        AgentRunner runner = new AgentRunner(provider);
        AgentRunResult result = runner.run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "hello")))
                .setModel("gpt-4o-mini")
                .setMaxIterations(2)
                .setProviderRetryMode("none"));

        assertEquals("ok", result.getFinalContent());
        assertEquals(1, directCalls.get());
        assertEquals(0, retryCalls.get());
    }

    @Test
    void runner_doesNotExposeTools_whenCapabilityDisablesToolCalling() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());
        AtomicInteger calls = new AtomicInteger(0);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                assertTrue(toolsDef.isEmpty());
                return new LLMResponse().setContent("no tools").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "read file")))
                .setTools(tools)
                .setModel("text-embedding-3-small")
                .setProviderCapability(capability("false", "true", "UNKNOWN"))
                .setMaxIterations(2));

        assertEquals("no tools", result.getFinalContent());
        assertEquals(1, calls.get());
        assertTrue(result.getRunEvents().stream().anyMatch(e ->
                "capability_warning".equals(e.get("type"))
                        && "TOOLS_NOT_EXPOSED".equals(e.get("decision"))));
    }

    @Test
    void runner_keepsTools_whenToolCallingCapabilityIsUnknown() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                assertFalse(toolsDef.isEmpty());
                return new LLMResponse().setContent("ok").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "use tool if needed")))
                .setTools(tools)
                .setModel("private-chat")
                .setProviderCapability(capability("UNKNOWN", "UNKNOWN", "UNKNOWN"))
                .setMaxIterations(2));

        assertEquals("ok", result.getFinalContent());
        assertTrue(result.getRunEvents().stream().anyMatch(e ->
                "capability_warning".equals(e.get("type"))
                        && "KEEP_EXISTING_BEHAVIOR".equals(e.get("decision"))));
    }

    @Test
    void runner_fallsBackToNonStreaming_whenCapabilityDisablesStreaming() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger(0);
        AtomicInteger streamCalls = new AtomicInteger(0);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                chatCalls.incrementAndGet();
                return new LLMResponse().setContent("fallback").setFinishReason("stop");
            }

            @Override
            public LLMResponse chatStream(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice,
                    StreamDeltaHandler onDelta,
                    StreamEndHandler onEnd
            ) {
                streamCalls.incrementAndGet();
                return new LLMResponse().setContent("stream").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "hello")))
                .setModel("no-stream-model")
                .setProviderCapability(capability("UNKNOWN", "false", "UNKNOWN"))
                .setHook(new AgentHook() {
                    @Override
                    public boolean wantsStreaming() {
                        return true;
                    }
                })
                .setMaxIterations(2));

        assertEquals("fallback", result.getFinalContent());
        assertEquals(1, chatCalls.get());
        assertEquals(0, streamCalls.get());
        assertTrue(result.getRunEvents().stream().anyMatch(e ->
                "STREAMING_DISABLED_FALLBACK_TO_CHAT".equals(e.get("decision"))));
    }

    @Test
    void runner_rejectsImageInput_whenVisionCapabilityIsFalse() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                return new LLMResponse().setContent("should not call").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "describe"),
                                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AA=="))
                        )
                )))
                .setModel("text-only")
                .setProviderCapability(capability("UNKNOWN", "true", "false"))
                .setMaxIterations(2));

        assertEquals("unsupported_capability", result.getStopReason());
        assertTrue(result.getFinalContent().contains("不支持图片输入"));
        assertEquals(0, calls.get());
    }

    @Test
    void runner_usesCapabilityOverrideToHideTools() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());
        AtomicInteger calls = new AtomicInteger(0);
        Config config = new Config();
        config.getAgents().getDefaults().setModel("qwen-plus");
        Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
        override.setSupportsToolCalling("false");
        config.getModelCapabilities().put("qwen-plus", override);
        ProviderCapability capability = new ProviderCapabilityResolver().resolve(config, "dashscope", "qwen-plus");

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                assertTrue(toolsDef.isEmpty());
                return new LLMResponse().setContent("override no tools").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "use tool")))
                .setTools(tools)
                .setModel("qwen-plus")
                .setProviderCapability(capability)
                .setMaxIterations(2));

        assertEquals("override no tools", result.getFinalContent());
        assertEquals(1, calls.get());
        assertTrue(result.getRunEvents().stream().anyMatch(e ->
                "TOOLS_NOT_EXPOSED".equals(e.get("decision"))));
    }

    @Test
    void runner_filtersModelToolsByAllowedTools() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());
        tools.register(namedTool("write_file"));
        AtomicInteger calls = new AtomicInteger(0);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                assertEquals(List.of("write_file"), toolsDef.stream().map(AgentRunnerTest::schemaName).toList());
                return new LLMResponse().setContent("filtered").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "edit")))
                .setTools(tools)
                .setAllowedTools(List.of("write_file", "missing_tool"))
                .setModel("model")
                .setMaxIterations(2));

        assertEquals("filtered", result.getFinalContent());
        assertEquals(1, calls.get());
        assertTrue(result.getRunEvents().stream().anyMatch(event ->
                "tool_exposure".equals(event.get("type"))
                        && event.toString().contains("write_file")
                        && event.toString().contains("missing_tool")));
    }

    @Test
    void runnerFailsBeforeModelWhenAllowedToolsExposeNothing() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(echoTool());
        AtomicInteger calls = new AtomicInteger(0);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                calls.incrementAndGet();
                return new LLMResponse().setContent("should not call").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "edit")))
                .setTools(tools)
                .setAllowedTools(List.of("write_file"))
                .setModel("model")
                .setMaxIterations(2));

        assertEquals("no_exposed_tools", result.getStopReason());
        assertTrue(result.getError().contains("no tools exposed"), result.getError());
        assertEquals(0, calls.get());
    }

    @Test
    void runner_usesCapabilityOverrideToDisableStreaming() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger(0);
        AtomicInteger streamCalls = new AtomicInteger(0);
        Config config = new Config();
        config.getAgents().getDefaults().setModel("qwen-plus");
        Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
        override.setSupportsStreaming("false");
        config.getModelCapabilities().put("qwen-plus", override);
        ProviderCapability capability = new ProviderCapabilityResolver().resolve(config, "dashscope", "qwen-plus");

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> toolsDef,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                chatCalls.incrementAndGet();
                return new LLMResponse().setContent("override fallback").setFinishReason("stop");
            }

            @Override
            public LLMResponse chatStream(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice,
                    StreamDeltaHandler onDelta,
                    StreamEndHandler onEnd
            ) {
                streamCalls.incrementAndGet();
                return new LLMResponse().setContent("stream").setFinishReason("stop");
            }
        };

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "hello")))
                .setModel("qwen-plus")
                .setProviderCapability(capability)
                .setHook(new AgentHook() {
                    @Override
                    public boolean wantsStreaming() {
                        return true;
                    }
                })
                .setMaxIterations(2));

        assertEquals("override fallback", result.getFinalContent());
        assertEquals(1, chatCalls.get());
        assertEquals(0, streamCalls.get());
        assertTrue(result.getRunEvents().stream().anyMatch(e ->
                "STREAMING_DISABLED_FALLBACK_TO_CHAT".equals(e.get("decision"))));
    }

    private static ProviderCapability capability(String tools, String streaming, String vision) {
        return new ProviderCapability("test", new ModelCapability(
                "test-model",
                tools,
                streaming,
                vision,
                "UNKNOWN",
                "UNKNOWN",
                -1,
                -1,
                "test"
        ));
    }

    private static Tool echoTool() {
        return new Tool() {
            @Override
            public String getName() {
                return "echo";
            }

            @Override
            public String getDescription() {
                return "echo";
            }

            @Override
            public List<ToolParam> getParams() {
                return List.of(new ToolParam("text", "string", "text", false));
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return params != null ? params.get("text") : "";
            }
        };
    }

    private static Tool namedTool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return "ok";
            }
        };
    }

    private static String schemaName(Map<String, Object> schema) {
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object name = fnMap.get("name");
            if (name instanceof String s) {
                return s;
            }
        }
        return "";
    }

    private static void assertEventOrder(List<Map<String, Object>> events, String... types) {
        int last = -1;
        for (String type : types) {
            int index = -1;
            for (int i = last + 1; i < events.size(); i++) {
                if (type.equals(events.get(i).get("type"))) {
                    index = i;
                    break;
                }
            }
            assertTrue(index > last, "missing event type in order: " + type + " events=" + events);
            last = index;
        }
    }
}
