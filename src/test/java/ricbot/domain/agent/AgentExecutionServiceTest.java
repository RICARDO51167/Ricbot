package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionServiceTest {

    @Test
    void executeInteractive_retriesToolLoopWhenNotStreaming(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "")))
                        .setStopReason("tool_loop"),
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "done")))
                        .setStopReason("stop")
                        .setFinalContent("done")
        );
        AgentExecutionService service = new AgentExecutionService(
                runner,
                new ToolRegistry(),
                workspace,
                "test-model",
                4,
                4000,
                "standard",
                8000,
                24
        );

        ExecutionOutcome outcome = service.executeInteractive(requestContext(null), payload -> {});

        assertEquals("done", outcome.finalContent());
        assertEquals(2, runner.specs().size());
        assertEquals(4, runner.specs().get(0).getMaxIterations());
        assertEquals(10, runner.specs().get(1).getMaxIterations());
    }

    @Test
    void executeInteractive_skipsRetryWhenStreamingHookOwnsTheTurn(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "partial")))
                        .setStopReason("tool_loop")
                        .setFinalContent("partial")
        );
        AgentExecutionService service = new AgentExecutionService(
                runner,
                new ToolRegistry(),
                workspace,
                "test-model",
                4,
                4000,
                "standard",
                8000,
                24
        );
        AgentHook streamingHook = new AgentHook() {
            @Override
            public boolean wantsStreaming() {
                return true;
            }
        };

        ExecutionOutcome outcome = service.executeInteractive(requestContext(streamingHook), payload -> {});

        assertEquals("partial", outcome.finalContent());
        assertEquals(1, runner.specs().size());
    }

    private AgentRequestContext requestContext(AgentHook hook) {
        InboundMessage msg = new InboundMessage("cli", "user", "direct", "hello");
        Session session = new Session("cli:direct");
        return new AgentRequestContext(
                msg,
                "cli:direct",
                session,
                "",
                new PromptContextBundle(),
                List.of(),
                List.of(Map.of("role", "user", "content", "hello")),
                hook,
                false
        );
    }

    private static class StubRunner extends AgentRunner {
        private final Deque<AgentRunResult> results = new ArrayDeque<>();
        private final List<AgentRunSpec> specs = new ArrayList<>();

        StubRunner(AgentRunResult... results) {
            super(new LLMProvider("k", "http://localhost") {
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
            });
            this.results.addAll(List.of(results));
        }

        @Override
        public AgentRunResult run(AgentRunSpec spec) {
            specs.add(spec);
            return results.removeFirst();
        }

        List<AgentRunSpec> specs() {
            return specs;
        }
    }
}
