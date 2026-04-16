package ricbot.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.AgentRunner;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class AgentRunnerTest {

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
}
