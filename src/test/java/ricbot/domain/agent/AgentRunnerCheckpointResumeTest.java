package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerCheckpointResumeTest {
    @Test
    void executesPendingReadOnlyToolsBeforeRequestingTheModel() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        LLMProvider provider = provider(messages -> {
            modelCalls.incrementAndGet();
            assertEquals("tool", messages.get(messages.size() - 1).get("role"));
            return new LLMResponse().setContent("continued").setFinishReason("stop");
        });
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String getName() { return "lookup"; }
            public String getDescription() { return "lookup"; }
            public boolean isReadOnly() { return true; }
            public Object execute(Map<String, Object> params) {
                toolCalls.incrementAndGet();
                return Map.of("ok", true, "value", params.get("key"));
            }
        });
        RunCheckpoint checkpoint = checkpoint("lookup");
        RunState child = childState();
        List<RunEvent> events = new ArrayList<>();

        AgentRunResult result = new AgentRunner(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(
                        Map.of("role", "user", "content", "find it"),
                        checkpoint.assistantMessage()
                ))
                .setTools(tools)
                .setModel("test")
                .setSessionKey("child-session")
                .setInitialRunState(child)
                .setResumeCheckpoint(checkpoint)
                .setRunEventSink(events::add));

        assertEquals("continued", result.getFinalContent());
        assertEquals(1, toolCalls.get());
        assertEquals(1, modelCalls.get());
        assertEquals(RunEventType.CHECKPOINT_RESTORED, events.get(0).type());
        assertTrue(events.stream().map(RunEvent::type).toList().indexOf(RunEventType.TOOL_BATCH_COMPLETED)
                < events.stream().map(RunEvent::type).toList().indexOf(RunEventType.MODEL_REQUESTED));
        assertEquals(2, result.getIterations());
    }

    @Test
    void refusesHistoricalSideEffectReplayBeforeAnyToolOrModelCall() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        LLMProvider provider = provider(messages -> {
            modelCalls.incrementAndGet();
            return new LLMResponse().setContent("unexpected");
        });
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String getName() { return "write"; }
            public String getDescription() { return "write"; }
            public Object execute(Map<String, Object> params) { toolCalls.incrementAndGet(); return "done"; }
        });

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new AgentRunner(provider).run(new AgentRunSpec()
                        .setInitialMessages(List.of(Map.of("role", "user", "content", "write")))
                        .setTools(tools).setModel("test").setSessionKey("child-session")
                        .setInitialRunState(childState()).setResumeCheckpoint(checkpoint("write"))));

        assertTrue(error.getMessage().contains("side-effect"));
        assertEquals(0, toolCalls.get());
        assertEquals(0, modelCalls.get());
    }

    private static RunCheckpoint checkpoint(String toolName) {
        Map<String, Object> pending = Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of("name", toolName, "arguments", "{\"key\":\"k1\"}")
        );
        Map<String, Object> assistant = new java.util.LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(pending));
        return new RunCheckpoint(
                1, "parent:1:MODEL_RESPONSE_RECEIVED", "parent", "parent", "source",
                4, 1, 1, RunCheckpointPhase.MODEL_RESPONSE_RECEIVED,
                new AgentNodeState(1, AgentNodeType.MODEL, 1, 1, false, Instant.now()),
                List.of(assistant), assistant, List.of(), List.of(pending), Map.of(), "", Instant.now());
    }

    private static RunState childState() {
        return RunState.from(RunEvent.create(
                1, "child-run", "child-session", 1,
                RunEventType.RUN_FORKED, RunStatus.CREATED, null, Map.of()));
    }

    private static LLMProvider provider(Chat chat) {
        return new LLMProvider("key", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) throws Exception {
                return chat.apply(messages);
            }
        };
    }

    @FunctionalInterface
    private interface Chat { LLMResponse apply(List<Map<String, Object>> messages) throws Exception; }
}
