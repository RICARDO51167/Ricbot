package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.session.Session;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextServiceTest {

    @Test
    void buildInteractiveRequest_combinesMemorySkillsSummaryAndBuildsHook(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("memory"));
        Files.createDirectories(workspace.resolve("skills").resolve("demo"));
        Files.writeString(workspace.resolve("memory").resolve("MEMORY.md"), "remember this");
        Files.writeString(workspace.resolve("USER.md"), "user profile");
        Files.writeString(workspace.resolve("SOUL.md"), "soul profile");
        Files.writeString(workspace.resolve("skills").resolve("demo").resolve("SKILL.md"), "Demo skill body");

        ContextBuilder contextBuilder = new ContextBuilder(workspace, "UTC", List.of());
        MemoryStore memoryStore = new MemoryStore(workspace);
        SkillsLoader skillsLoader = new SkillsLoader(workspace, null, Set.of());
        SkillRouter skillRouter = new SkillRouter(skillsLoader, 3, 12_000);
        ToolRegistry tools = new ToolRegistry();
        AtomicReference<String> appliedContext = new AtomicReference<>();
        AgentHookFactory hookFactory = new AgentHookFactory(new MessageBus(), (channel, chatId, messageId) ->
                appliedContext.set(channel + ":" + chatId + ":" + messageId)
        );
        List<AgentHook> globalHooks = new java.util.ArrayList<>();
        AgentContextService service = new AgentContextService(
                workspace,
                contextBuilder,
                memoryStore,
                skillsLoader,
                skillRouter,
                tools,
                hookFactory,
                (channel, chatId, messageId) -> appliedContext.set(channel + ":" + chatId + ":" + messageId),
                globalHooks
        );

        Session session = new Session("cli:direct");
        session.addMessage("assistant", "older reply");
        PreparedSessionContext prepared = new PreparedSessionContext(
                "cli:direct",
                session,
                "summary block",
                null,
                true
        );
        InboundMessage msg = new InboundMessage("cli", "user", "direct", "please use demo");
        msg.setMetadata(new HashMap<>(Map.of("message_id", "m-1")));

        AgentRequestContext request = service.buildInteractiveRequest(msg, prepared, List.of(), 20);

        assertEquals("cli:direct:m-1", appliedContext.get());
        assertTrue(request.combinedContext().contains("MEMORY.md"));
        assertTrue(request.combinedContext().contains("remember this"));
        assertTrue(request.combinedContext().contains("USER.md"));
        assertTrue(request.combinedContext().contains("SOUL.md"));
        assertTrue(request.combinedContext().contains("summary block"));
        assertTrue(request.combinedContext().contains("Demo skill body"));
        assertEquals(1, request.history().size());
        assertNotNull(request.hook());
        Map<String, Object> current = request.initialMessages().get(request.initialMessages().size() - 1);
        assertEquals("user", current.get("role"));
        assertEquals("please use demo", current.get("content"));
    }
}
