package ricbot.domain.agent.context;

import org.junit.jupiter.api.Test;
import ricbot.domain.agent.context.dto.ContextCompactionResult;
import ricbot.domain.agent.context.dto.StructuredContextSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {
    @Test
    void marksOriginalsWithoutDeletingAndKeepsToolPairsTogether() {
        List<Map<String, Object>> messages = longHistory();
        ContextCompactionResult result = new ContextCompactor().compact(messages, 180, "compact-model",
                (source, prompt) -> new StructuredContextSummary("task", "state", List.of("found"),
                        List.of("next"), List.of("tool evidence")));

        assertTrue(result.compacted());
        assertEquals(messages.size() + 1, result.eventMessages().size());
        assertTrue(result.eventMessages().stream().anyMatch(ContextCompactor::compressed));
        assertFalse(result.activeMessages().stream().anyMatch(ContextCompactor::compressed));
        boolean hasCall = result.activeMessages().stream().anyMatch(message -> message.containsKey("tool_calls"));
        boolean hasResult = result.activeMessages().stream().anyMatch(message -> message.containsKey("tool_call_id"));
        assertEquals(hasCall, hasResult);
        assertEquals(5, result.sourceMessageIds().stream().distinct().limit(5).count());
    }

    @Test
    void retriesSummaryTwiceThenRecordsConservativeDegradation() {
        AtomicInteger attempts = new AtomicInteger();
        ContextCompactionResult result = new ContextCompactor().compact(longHistory(), 180, "compact-model",
                (source, prompt) -> { attempts.incrementAndGet(); throw new IllegalStateException("offline"); });

        assertEquals(2, attempts.get());
        assertTrue(result.compacted());
        assertTrue(result.degraded());
        assertFalse(result.promptDigest().isBlank());
        assertFalse(result.resultDigest().isBlank());
    }

    private static List<Map<String, Object>> longHistory() {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            messages.add(new LinkedHashMap<>(Map.of("role", index % 2 == 0 ? "user" : "assistant",
                    "content", "message-" + index + "-" + "x".repeat(80))));
        }
        messages.set(5, new LinkedHashMap<>(Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", "call-1", "function", Map.of("name", "read"))))));
        messages.set(6, new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", "call-1",
                "content", "tool result")));
        return messages;
    }
}
