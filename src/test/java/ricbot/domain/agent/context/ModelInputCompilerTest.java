package ricbot.domain.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelInputCompilerTest {
    @Test
    void conservativeAccountantIncludesSchemasOverheadMarginAndOutputReserve() {
        TokenEstimate estimate = new ConservativeContextTokenAccountant().count(new ModelRequestShape(
                List.of(Map.of("role", "user", "content", "hello")),
                List.of(tool("read_file")), 256, 8_192, "unknown"));

        assertEquals(TokenEstimate.Mode.ESTIMATED, estimate.mode());
        assertEquals(256, estimate.partitions().get("reservedOutput"));
        assertTrue(estimate.partitions().get("toolSchemas") > 0);
        assertTrue(estimate.partitions().get("messageOverhead") >= 8);
        assertEquals(estimate.inputTokens() + 256, estimate.totalTokens());
    }

    @Test
    void segmentKeepsToolCallAndAllResultsTogetherAndRejectsOrphans() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "tool_calls", List.of(
                        Map.of("id", "a"), Map.of("id", "b"))),
                Map.of("role", "tool", "tool_call_id", "a", "content", "one"),
                Map.of("role", "tool", "tool_call_id", "b", "content", "two"));

        List<ContextSegment> segments = ContextSegment.parse(messages);
        assertEquals(1, segments.size());
        assertEquals(3, segments.get(0).messages().size());
        assertThrows(IllegalArgumentException.class, () -> ContextSegment.parse(List.of(
                Map.of("role", "tool", "tool_call_id", "missing", "content", "orphan"))));
    }

    @Test
    void compilerCountsFinalToolsAndNarrowsToBasicOnOverflow() {
        ModelInputPlan plan = new ModelInputCompiler(new ConservativeContextTokenAccountant()).compile(
                List.of(Map.of("role", "system", "content", "stable"),
                        Map.of("role", "user", "content", "do it")), null,
                List.of(tool("read_file"), tool("very_large_non_basic_tool_with_a_long_schema_name")),
                Set.of("read_file"), Map.of("budget", Map.of("band", "normal")),
                64, 180, "unknown", false);

        assertTrue(plan.toolExposureNarrowed());
        assertEquals(1, plan.tools().size());
        assertEquals("read_file", ((Map<?, ?>) plan.tools().get(0).get("function")).get("name"));
        assertFalse(plan.requestDigest().isBlank());
    }

    private static Map<String, Object> tool(String name) {
        return Map.of("type", "function", "function", Map.of("name", name,
                "description", "A deliberately descriptive tool schema.",
                "parameters", Map.of("type", "object", "additionalProperties", false,
                        "properties", Map.of("path", Map.of("type", "string")))));
    }
}
