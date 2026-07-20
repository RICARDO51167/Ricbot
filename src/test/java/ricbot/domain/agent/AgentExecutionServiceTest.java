package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals("tool_loop", runner.specs().get(1).getMetadata().get("retryReason"));
        assertEquals(1, runner.specs().get(1).getMetadata().get("retryCount"));
    }

    @Test
    void executeInteractive_retriesToolErrorLoopWhenNotStreaming(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "")))
                        .setStopReason("tool_error_loop"),
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "recovered")))
                        .setStopReason("stop")
                        .setFinalContent("recovered")
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

        assertEquals("recovered", outcome.finalContent());
        assertEquals(2, runner.specs().size());
        assertEquals(10, runner.specs().get(1).getMaxIterations());
        assertEquals("tool_error_loop", runner.specs().get(1).getMetadata().get("retryReason"));
    }

    @Test
    void executeInteractive_toolLoopRetry_recordsRunRetryEvent(@TempDir Path workspace) throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("echo", "ok"));
        AtomicInteger calls = new AtomicInteger(0);
        AgentExecutionService service = new AgentExecutionService(
                new AgentRunner(loopThenDoneProvider(calls, "echo")),
                tools,
                workspace,
                "test-model",
                1,
                4000,
                "standard",
                8000,
                24
        );
        List<Map<String, Object>> checkpoints = new ArrayList<>();

        ExecutionOutcome outcome = service.executeInteractive(requestContext(null), checkpoints::add);

        assertEquals("done", outcome.finalContent());
        assertEquals(2, calls.get());
        assertTrue(outcome.runResult().getRunEvents().stream().anyMatch(event ->
                "run_retry".equals(event.get("type"))
                        && "tool_loop".equals(event.get("retry_reason"))
                        && Integer.valueOf(1).equals(event.get("retry_count"))));
        Map<String, Object> lastCheckpoint = checkpoints.get(checkpoints.size() - 1);
        assertEquals(3, ((List<?>) lastCheckpoint.get("run_messages")).size());
    }

    @Test
    void executeInteractive_toolErrorLoopRetry_recordsRunRetryEvent(@TempDir Path workspace) throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("failing", Map.of("error", "failed")));
        AtomicInteger calls = new AtomicInteger(0);
        AgentExecutionService service = new AgentExecutionService(
                new AgentRunner(loopThenDoneProvider(calls, "failing")),
                tools,
                workspace,
                "test-model",
                1,
                4000,
                "standard",
                8000,
                24
        );

        ExecutionOutcome outcome = service.executeInteractive(requestContext(null), payload -> {});

        assertEquals("done", outcome.finalContent());
        assertEquals(2, calls.get());
        assertTrue(outcome.runResult().getRunEvents().stream().anyMatch(event ->
                "run_retry".equals(event.get("type"))
                        && "tool_error_loop".equals(event.get("retry_reason"))
                        && Integer.valueOf(1).equals(event.get("retry_count"))));
    }

    @Test
    void executeInteractive_retryLimitExceededDoesNotInfiniteLoop(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "")))
                        .setStopReason("tool_loop"),
                new AgentRunResult()
                        .setMessages(List.of(Map.of("role", "assistant", "content", "")))
                        .setStopReason("tool_loop")
                        .setFinalContent("still looping")
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

        assertEquals("still looping", outcome.finalContent());
        assertEquals(2, runner.specs().size());
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

    @Test
    void executeSystem_forwardsDurableCheckpointCallback(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(
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
        java.util.function.Consumer<Map<String, Object>> callback = payload -> {};

        ExecutionOutcome outcome = service.executeSystem(requestContext(null), callback);

        assertEquals("done", outcome.finalContent());
        assertSame(callback, runner.specs().get(0).getCheckpointCallback());
    }

    @Test
    void executeInteractive_consoleRetryUsesDistinctJournalAttempt(@TempDir Path workspace) throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("echo", "ok"));
        AtomicInteger calls = new AtomicInteger();
        FileRunJournalStore journalStore = new FileRunJournalStore(workspace);
        AgentExecutionService service = new AgentExecutionService(
                new AgentRunner(loopThenDoneProvider(calls, "echo")),
                tools,
                workspace,
                "test-model",
                1,
                4000,
                "standard",
                8000,
                24,
                null,
                journalStore
        );
        AgentRequestContext request = requestContext(null);
        request.message().getMetadata().put("consoleRunId", "console-run-1");
        List<Map<String, Object>> checkpoints = new ArrayList<>();

        ExecutionOutcome outcome = service.executeInteractive(request, checkpoints::add);

        assertEquals("done", outcome.finalContent());
        assertEquals(RunStatus.PAUSED, journalStore.load("cli:direct", "console-run-1").orElseThrow().status());
        assertEquals(
                RunStatus.COMPLETED,
                journalStore.load("cli:direct", "console-run-1:attempt:1").orElseThrow().status()
        );
        Map<String, Object> lastCheckpoint = checkpoints.get(checkpoints.size() - 1);
        assertEquals("console-run-1", lastCheckpoint.get("run_id"));
        assertEquals("console-run-1:attempt:1", lastCheckpoint.get("journal_run_id"));
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

    private LLMProvider loopThenDoneProvider(AtomicInteger calls, String toolName) {
        return new LLMProvider("k", "http://localhost") {
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
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("working")
                            .setToolCalls(List.of(new ToolCallRequest("call_1", toolName, Map.of())))
                            .setFinishReason("tool_calls");
                }
                return new LLMResponse().setContent("done").setFinishReason("stop");
            }
        };
    }

    private Tool tool(String name, Object result) {
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
                return result;
            }
        };
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
