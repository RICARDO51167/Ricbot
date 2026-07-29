package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import ricbot.domain.agent.dto.ContextSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptContextBundleTest {

    @Test
    void budgetTrace_includesSectionTokensAndSourceMetadata() {
        PromptContextBundle bundle = new PromptContextBundle();
        bundle.addItem(
                "memory_recall",
                "[semantic] user prefers concise replies",
                0.8d,
                ContextSource.of("memory", "mem-1", "", "concise replies", 0.8d)
        );
        bundle.addItem(
                "project_notes",
                "notes/project/decisions.md [project/decision] Use GSSC",
                0.7d,
                ContextSource.of("note", "note-1", "notes/project/decisions.md", "Use GSSC", 0.7d)
        );

        Map<String, Object> trace = bundle.budgetTrace();

        assertTrue((Integer) trace.get("estimated_rendered_tokens") > 0);
        List<?> sections = (List<?>) trace.get("sections");
        Map<?, ?> memory = sections.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(row -> "memory_recall".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertTrue((Integer) memory.get("estimated_tokens") > 0);

        Map<?, ?> sources = (Map<?, ?>) trace.get("sources");
        List<?> memorySources = (List<?>) sources.get("memory_recall");
        assertEquals("mem-1", ((Map<?, ?>) memorySources.get(0)).get("id"));
        List<?> noteSources = (List<?>) sources.get("project_notes");
        assertEquals("notes/project/decisions.md", ((Map<?, ?>) noteSources.get(0)).get("path"));
    }
}
