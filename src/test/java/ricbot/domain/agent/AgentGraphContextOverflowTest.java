package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMFailureKind;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentGraphContextOverflowTest {
    @Test
    void firstOverflowCompactsThenModelCompletes(@TempDir Path workspace) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentRunResult result = new AgentGraphFactory(provider(calls, false)).runForTest(spec(workspace));

        assertEquals("done after compact", result.getFinalContent());
        assertEquals("stop", result.getStopReason());
        assertEquals(3, calls.get());
        assertTrue(result.getRunEvents().stream()
                .filter(event -> "context_compacted".equals(event.get("type")))
                .flatMap(event -> event.get("messages") instanceof List<?> messages ? messages.stream()
                        : java.util.stream.Stream.empty())
                .filter(Map.class::isInstance).map(Map.class::cast)
                .anyMatch(message -> String.valueOf(message.get("_ricbot_marks")).contains("COMPRESSED")));
    }

    @Test
    void secondOverflowFailsWithoutOrdinaryRetry(@TempDir Path workspace) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentRunResult result = new AgentGraphFactory(provider(calls, true)).runForTest(spec(workspace));

        assertNotEquals("stop", result.getStopReason());
        assertTrue(result.getError().contains("context overflow after compaction"), result.getError());
        assertEquals(3, calls.get(), "second overflow must not enter the three-attempt transient retry path");
    }

    private static AgentRunSpec spec(Path workspace) {
        return new AgentRunSpec().setInitialMessages(history())
                .setTools(new ToolRegistry()).setModel("model").setCompactModel("compact-model")
                .setWorkspace(workspace).setSessionKey("overflow-session")
                .setContextWindowTokens(16_000).setMaxIterations(4);
    }

    private static List<Map<String, Object>> history() {
        java.util.ArrayList<Map<String, Object>> messages = new java.util.ArrayList<>();
        for (int index = 0; index < 11; index++) {
            messages.add(Map.of("role", index % 2 == 0 ? "user" : "assistant",
                    "content", "history-" + index + " " + "x".repeat(80)));
        }
        messages.add(Map.of("role", "user", "content", "finish the task"));
        return List.copyOf(messages);
    }

    private static LLMProvider provider(AtomicInteger calls, boolean overflowTwice) {
        return new LLMProvider("test", "local") {
            { setDefaultModel("model"); }

            @Override public LLMResponse chat(List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools, String model,
                                               Integer maxTokens, Double temperature,
                                               String reasoningEffort, Object toolChoice) throws Exception {
                int call = calls.incrementAndGet();
                if (call == 1 || overflowTwice && call == 3) {
                    throw new LLMFailureException(LLMFailureKind.CONTEXT_OVERFLOW,
                            "context_length_exceeded", 400, null, null);
                }
                if (call == 2) {
                    return new LLMResponse("""
                            {"taskOverview":"finish task","currentState":"compacted","importantDiscoveries":["context was large"],"nextSteps":["answer"],"contextToPreserve":["finish the task"]}
                            """).setFinishReason("stop");
                }
                return new LLMResponse("done after compact").setFinishReason("stop");
            }
        };
    }
}
