package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.note.NoteService;
import ricbot.domain.rag.WorkspaceRagService;
import ricbot.domain.session.Session;

import java.nio.file.Files;
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

    @Test
    void select_addsProjectNotesAndWorkspaceKnowledge(@TempDir Path workspace) throws Exception {
        MemoryStore memoryStore = new MemoryStore(workspace);
        NoteService noteService = new NoteService(workspace);
        noteService.create(
                "MCP timeout blocker",
                "blockers",
                "blocker",
                "MCP streamable HTTP timeout is blocked by transport retry behavior.",
                List.of("mcp", "timeout")
        );
        Files.createDirectories(workspace.resolve("src/main/java/ricbot/integration/mcp"));
        Files.writeString(workspace.resolve("src/main/java/ricbot/integration/mcp/MCPAdapters.java"), """
                package ricbot.integration.mcp;

                public class MCPAdapters {
                    public void handleStreamableHttpTimeout() {
                    }
                }
                """);
        WorkspaceRagService ragService = new WorkspaceRagService(workspace);
        ragService.indexWorkspace();

        ContextSelectionService service = new ContextSelectionService(
                memoryStore,
                new ToolTraceSummarizer(),
                32_000,
                noteService,
                ragService
        );

        ContextSelectionService.SelectionResult result = service.select(
                new ContextSelectionService.SessionPreparedInputs(null, TaskState.fromSession(new Session("test")), List.of()),
                List.of(),
                "继续处理 MCP timeout",
                6
        );

        String rendered = result.bundle().render();
        assertTrue(rendered.contains("## project_notes"), rendered);
        assertTrue(rendered.contains("MCP timeout blocker"), rendered);
        assertTrue(rendered.contains("## workspace_knowledge"), rendered);
        assertTrue(rendered.contains("MCPAdapters.java"), rendered);
        Map<String, Object> budgetTrace = result.bundle().budgetTrace();
        assertTrue(String.valueOf(budgetTrace).contains("project_notes"), String.valueOf(budgetTrace));
        assertTrue(String.valueOf(budgetTrace).contains("workspace_knowledge"), String.valueOf(budgetTrace));
    }
}
