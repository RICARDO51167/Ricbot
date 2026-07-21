package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @Test
    void buildBundle_preservesMemoryContext(@TempDir Path workspace) {
        MemoryStore memoryStore = new MemoryStore(workspace);
        memoryStore.mergeMemoryEntries(List.of(new MemoryEntry()
                .setType(MemoryEntry.TYPE_PREFERENCE)
                .setScope(MemoryEntry.SCOPE_LONG_TERM)
                .setSummary("用户偏好简短回答")
                .setDetails("user profile")
                .setImportance(0.9d)
                .setConfidence(0.9d)
                .setTags(List.of("user"))));

        ContextAssembler.AssembledContext assembled = assembler(workspace, memoryStore)
                .buildInteractiveContext(message("请按我的偏好回答"), prepared(new Session("cli:direct")), 20);

        assertTrue(assembled.combinedContext().contains("用户偏好简短回答"), assembled.combinedContext());
        assertTrue(assembled.bundle().render().contains("用户偏好简短回答"), assembled.bundle().render());
    }

    @Test
    void buildBundle_preservesWorkspaceContext(@TempDir Path workspace) {
        MemoryStore memoryStore = new MemoryStore(workspace);
        WorkspaceSession sessionRecord = new WorkspaceSession(
                "workspace_test",
                WorkspaceBackendType.LOCAL,
                workspace.toString(),
                workspace.resolve("feature").toString(),
                "",
                "实现 ContextAssembler",
                WorkspaceSessionStatus.ACTIVE,
                "",
                "",
                Map.of()
        );
        new WorkspaceSessionStore(workspace).save(sessionRecord);
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY, sessionRecord.id());

        ContextAssembler.AssembledContext assembled = assembler(workspace, memoryStore)
                .buildInteractiveContext(message("继续处理工作区"), prepared(session), 20);

        assertTrue(assembled.bundle().render().contains("workspace_test"), assembled.bundle().render());
        assertTrue(assembled.combinedContext().contains("实现 ContextAssembler"), assembled.combinedContext());
    }

    @Test
    void buildBundle_preservesTeamContextWhenAvailable(@TempDir Path workspace) {
        MemoryStore memoryStore = new MemoryStore(workspace);
        Session session = new Session("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TEAM_CONTEXT_KEY, Map.of(
                "session", Map.of("id", "team_1", "goal", "完成上下文抽离", "state", "PLANNING"),
                "whiteboardPath", ".team/team_1/WHITEBOARD.md",
                "whiteboardSummary", "leader note",
                "workerResults", List.of("dev: done")
        ));

        ContextAssembler.AssembledContext assembled = assembler(workspace, memoryStore)
                .buildInteractiveContext(message("继续 team 任务"), prepared(session), 20);

        assertTrue(assembled.bundle().render().contains("team_1"), assembled.bundle().render());
        assertTrue(assembled.combinedContext().contains("完成上下文抽离"), assembled.combinedContext());
    }

    @Test
    void buildBundle_handlesMissingOptionalSources(@TempDir Path workspace) {
        MemoryStore memoryStore = new MemoryStore(workspace);

        ContextAssembler.AssembledContext assembled = assembler(workspace, memoryStore)
                .buildInteractiveContext(message("hello"), prepared(new Session("cli:direct")), 20);

        assertNotNull(assembled.bundle());
        assertNotNull(assembled.history());
        assertNotNull(assembled.initialMessages());
        assertNotNull(assembled.contextTrace());
    }

    @Test
    void buildBundle_preservesContextTrace(@TempDir Path workspace) throws Exception {
        MemoryStore memoryStore = new MemoryStore(workspace);

        ContextAssembler.AssembledContext assembled = assembler(workspace, memoryStore)
                .buildInteractiveContext(message("please use demo"), prepared(new Session("cli:direct")), 20);

        assertEquals("interactive", assembled.contextTrace().get("mode"));
        assertEquals("cli:direct", assembled.contextTrace().get("session_key"));
        assertTrue(assembled.contextTrace().containsKey("prompt_context_budget"));
        assertTrue(assembled.contextTrace().containsKey("context_quality"));
        assertTrue(assembled.contextTrace().containsKey("combined_context_chars"));
    }

    private static ContextAssembler assembler(Path workspace, MemoryStore memoryStore) {
        return new ContextAssembler(
                workspace,
                new ContextBuilder(workspace, "UTC"),
                new ContextSelectionService(memoryStore, new ToolTraceSummarizer())
        );
    }

    private static PreparedSessionContext prepared(Session session) {
        return new PreparedSessionContext(
                session.getKey(),
                session,
                "",
                TaskState.fromSession(session),
                null,
                false
        );
    }

    private static InboundMessage message(String content) {
        return new InboundMessage("cli", "user", "direct", content);
    }
}
