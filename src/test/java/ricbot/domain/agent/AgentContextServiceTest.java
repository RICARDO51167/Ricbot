package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.session.Session;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        Files.createDirectories(workspace.resolve("skills").resolve("unused"));
        Files.writeString(workspace.resolve("skills").resolve("demo").resolve("SKILL.md"), """
                ---
                description: Demo skill.
                keywords: demo
                ---
                Demo skill body
                """);
        Files.writeString(workspace.resolve("skills").resolve("unused").resolve("SKILL.md"), """
                ---
                keywords: qq
                ---
                Unused skill body
                """);

        ContextBuilder contextBuilder = new ContextBuilder(workspace, "UTC", List.of());
        MemoryStore memoryStore = new MemoryStore(workspace);
        memoryStore.mergeMemoryEntries(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PREFERENCE)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("remember this")
                        .setDetails("user profile")
                        .setImportance(0.9d)
                        .setConfidence(0.9d)
                        .setTags(List.of("user"))
        ));
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
                globalHooks,
                new ContextSelectionService(memoryStore, new ToolTraceSummarizer())
        );

        Session session = new Session("cli:direct");
        session.addMessage("assistant", "older reply");
        PreparedSessionContext prepared = new PreparedSessionContext(
                "cli:direct",
                session,
                "summary block",
                TaskState.fromSession(session),
                null,
                true
        );
        InboundMessage msg = new InboundMessage("cli", "user", "direct", "please use demo");
        msg.setMetadata(new HashMap<>(Map.of("message_id", "m-1")));

        AgentRequestContext request = service.buildInteractiveRequest(msg, prepared, List.of(), 20);

        assertEquals("cli:direct:m-1", appliedContext.get());
        assertTrue(request.combinedContext().contains("remember this"));
        assertTrue(request.combinedContext().contains("summary block"));
        assertTrue(request.combinedContext().contains("<skill name=\"demo\""));
        assertFalse(request.combinedContext().contains("Demo skill body"));
        assertFalse(request.combinedContext().contains("Unused skill body"));
        assertTrue(request.combinedContext().contains("Use the read_skill tool"));
        assertEquals(1, request.history().size());
        assertNotNull(request.hook());
        assertFalse(request.promptContext().isEmpty());
        assertEquals(1, request.contextTrace().get("history_selected"));
        assertTrue(request.contextTrace().containsKey("prompt_context_budget"));
        Map<?, ?> budget = (Map<?, ?>) request.contextTrace().get("prompt_context_budget");
        assertTrue(budget.containsKey("sections"));
        Map<?, ?> skillTrace = (Map<?, ?>) request.contextTrace().get("skills");
        assertEquals(1, skillTrace.get("selected_count"));
        String systemPrompt = String.valueOf(request.initialMessages().get(0).get("content"));
        assertTrue(systemPrompt.contains("remember this"));
        assertEquals(systemPrompt.indexOf("remember this"), systemPrompt.lastIndexOf("remember this"));
        assertTrue(systemPrompt.contains("<skill name=\"demo\""));
        assertFalse(systemPrompt.contains("Demo skill body"));
        assertTrue(systemPrompt.contains("## Skills Context"));
        assertTrue(systemPrompt.indexOf("## Skills Context") < systemPrompt.indexOf("<skill name=\"demo\""));
        Map<String, Object> current = request.initialMessages().get(request.initialMessages().size() - 1);
        assertEquals("user", current.get("role"));
        assertEquals("please use demo", current.get("content"));
    }

    @Test
    void buildInteractiveRequest_loadsExplicitSkillTrigger(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("skills").resolve("demo"));
        Files.writeString(workspace.resolve("skills").resolve("demo").resolve("SKILL.md"), """
                ---
                description: Demo skill.
                ---
                Demo skill body
                """);

        ContextBuilder contextBuilder = new ContextBuilder(workspace, "UTC", List.of());
        MemoryStore memoryStore = new MemoryStore(workspace);
        SkillsLoader skillsLoader = new SkillsLoader(workspace, null, Set.of());
        ToolRegistry tools = new ToolRegistry();
        AgentContextService service = new AgentContextService(
                workspace,
                contextBuilder,
                memoryStore,
                skillsLoader,
                new SkillRouter(skillsLoader, 3, 12_000),
                tools,
                new AgentHookFactory(new MessageBus(), (channel, chatId, messageId) -> {}),
                (channel, chatId, messageId) -> {},
                new java.util.ArrayList<>(),
                new ContextSelectionService(memoryStore, new ToolTraceSummarizer())
        );

        Session session = new Session("cli:direct");
        PreparedSessionContext prepared = new PreparedSessionContext(
                "cli:direct",
                session,
                "",
                TaskState.fromSession(session),
                null,
                false
        );

        AgentRequestContext request = service.buildInteractiveRequest(
                new InboundMessage("cli", "user", "direct", "please use $demo"),
                prepared,
                List.of(),
                20
        );

        assertTrue(request.combinedContext().contains("## Loaded Skills"), request.combinedContext());
        assertTrue(request.combinedContext().contains("Demo skill body"), request.combinedContext());
    }

    @Test
    void bundledRuntimePromptResourcesDoNotRequireMissingGoalUpdateTool() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<Path> roots = List.of(
                root.resolve("src/main/resources"),
                root.resolve("docs")
        );
        String missingTool = missingGoalUpdateToolName();
        List<String> hits = new ArrayList<>();

        for (Path scanRoot : roots) {
            if (!Files.isDirectory(scanRoot)) {
                continue;
            }
            try (var stream = Files.walk(scanRoot)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!(name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".html"))) {
                        continue;
                    }
                    String content = Files.readString(file);
                    if (content.contains(missingTool)) {
                        hits.add(root.relativize(file) + " contains " + missingTool);
                    }
                }
            }
        }

        assertTrue(hits.isEmpty(), String.join("\n", hits));
    }

    private static String missingGoalUpdateToolName() {
        return new String(new char[]{'u', 'p', 'd', 'a', 't', 'e', '_', 'g', 'o', 'a', 'l'});
    }
}
