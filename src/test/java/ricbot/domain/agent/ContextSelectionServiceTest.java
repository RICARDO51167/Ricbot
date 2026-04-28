package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.MemoryStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSelectionServiceTest {

    @Test
    void selectHistory_usesChineseOverlapAndDoesNotKeepUnrelatedOlderMessages(@TempDir Path workspace) {
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "我喜欢蓝色主题"),
                Map.of("role", "assistant", "content", "记住了"),
                Map.of("role", "user", "content", "飞书审批流程需要继续完善"),
                Map.of("role", "assistant", "content", "我会处理飞书审批"),
                Map.of("role", "user", "content", "最近的无关消息 1"),
                Map.of("role", "assistant", "content", "最近的无关回复 1"),
                Map.of("role", "user", "content", "最近的无关消息 2"),
                Map.of("role", "assistant", "content", "最近的无关回复 2")
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs(null, null, List.of()),
                messages,
                "继续飞书审批",
                6
        );

        String rendered = result.history().toString();
        assertEquals(6, result.history().size());
        assertTrue(rendered.contains("飞书审批流程需要继续完善"), rendered);
        assertFalse(rendered.contains("我喜欢蓝色主题"), rendered);
    }
}
