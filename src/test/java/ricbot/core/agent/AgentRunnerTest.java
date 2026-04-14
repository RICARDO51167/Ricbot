package ricbot.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.ToolCallRequest;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.ReadFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgentRunnerTest {

    @Test
    void runner_executesToolCallsAndAppendsToolResults(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("hello.txt"), "hello");

        ToolRegistry tools = new ToolRegistry();
        tools.register(new ReadFileTool(workspace, workspace, List.of()));

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
                                    new ToolCallRequest("call_1", "read_file", Map.of(
                                            "path", "hello.txt",
                                            "offset", 1,
                                            "limit", 10
                                    ))
                            ))
                            .setFinishReason("tool_calls");
                }

                boolean sawTool = messages.stream().anyMatch(m -> "tool".equals(String.valueOf(m.get("role"))));
                assertTrue(sawTool);

                return new LLMResponse()
                        .setContent("done")
                        .setFinishReason("stop");
            }
        };

        AgentRunner runner = new AgentRunner(provider);
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "read it")))
                .setTools(tools)
                .setModel("gpt-4o-mini")
                .setMaxIterations(5)
                .setMaxToolResultChars(10_000)
                .setConcurrentTools(false);

        AgentRunResult result = runner.run(spec);
        assertEquals("done", result.getFinalContent());
        assertTrue(result.getMessages().stream().anyMatch(m -> "tool".equals(String.valueOf(m.get("role")))));
        assertTrue(result.getToolsUsed().contains("read_file"));
    }
}

