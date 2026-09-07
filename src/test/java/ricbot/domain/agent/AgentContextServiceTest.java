package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.dto.AgentContextService;
import ricbot.domain.agent.dto.PreparedSessionContext;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.session.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextServiceTest {

    @Test
    void buildInteractiveRequest_combinesMemoryAndBuildsHook(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("memory"));

        ContextBuilder contextBuilder = new ContextBuilder(workspace, "UTC");
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
        AgentHookFactory hookFactory = new AgentHookFactory(new MessageBus());
        AgentContextService service = new AgentContextService(
                workspace,
                contextBuilder,
                hookFactory,
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

        AgentRequestContext request = service.buildInteractiveRequest(msg, prepared, 20);

        assertTrue(request.combinedContext().contains("remember this"));
        assertTrue(request.combinedContext().contains("summary block"));
        assertEquals(1, request.history().size());
        assertNotNull(request.hook());
        assertFalse(request.promptContext().isEmpty());
        assertEquals(1, request.contextTrace().get("history_selected"));
        assertTrue(request.contextTrace().containsKey("prompt_context_budget"));
        Map<?, ?> budget = (Map<?, ?>) request.contextTrace().get("prompt_context_budget");
        assertTrue(budget.containsKey("sections"));
        String systemPrompt = String.valueOf(request.initialMessages().stream()
                .filter(message -> "ricbot_initial_context".equals(message.get("name"))).findFirst().orElseThrow().get("content"));
        assertTrue(systemPrompt.contains("remember this"));
        assertEquals(systemPrompt.indexOf("remember this"), systemPrompt.lastIndexOf("remember this"));
        Map<String, Object> current = request.initialMessages().get(request.initialMessages().size() - 1);
        assertEquals("user", current.get("role"));
        assertEquals("please use demo", current.get("content"));
    }

    @Test
    void buildInteractiveRequest_delegatesToAssemblerAndKeepsOutputCompatible(@TempDir Path workspace) {
        ContextBuilder contextBuilder = new ContextBuilder(workspace, "UTC");
        MemoryStore memoryStore = new MemoryStore(workspace);
        memoryStore.mergeMemoryEntries(List.of(new MemoryEntry()
                .setType(MemoryEntry.TYPE_PREFERENCE)
                .setScope(MemoryEntry.SCOPE_LONG_TERM)
                .setSummary("用户偏好简短回答")
                .setDetails("user profile")
                .setImportance(0.9d)
                .setConfidence(0.9d)
                .setTags(List.of("user"))));
        ContextSelectionService selectionService = new ContextSelectionService(memoryStore, new ToolTraceSummarizer());
        AgentContextService service = new AgentContextService(
                workspace,
                contextBuilder,
                new AgentHookFactory(new MessageBus()),
                selectionService
        );
        ContextAssembler assembler = new ContextAssembler(
                workspace,
                contextBuilder,
                selectionService
        );
        Session session = new Session("cli:direct");
        PreparedSessionContext prepared = new PreparedSessionContext(
                "cli:direct",
                session,
                "summary block",
                TaskState.fromSession(session),
                null,
                false
        );
        InboundMessage msg = new InboundMessage("cli", "user", "direct", "请按我的偏好回答");

        AgentRequestContext request = service.buildInteractiveRequest(msg, prepared, 20);
        ContextAssembler.AssembledContext assembled = assembler.buildInteractiveContext(msg, prepared, 20);

        assertEquals(assembled.combinedContext(), request.combinedContext());
        assertEquals(assembled.bundle().render(), request.promptContext().render());
        assertEquals(assembled.history(), request.history());
        assertEquals(assembled.initialMessages().size(), request.initialMessages().size());
        assertEquals(assembled.initialMessages().get(assembled.initialMessages().size() - 1), request.initialMessages().get(request.initialMessages().size() - 1));
        String dynamicContext = String.valueOf(request.initialMessages().stream()
                .filter(message -> "ricbot_initial_context".equals(message.get("name"))).findFirst().orElseThrow().get("content"));
        assertTrue(dynamicContext.contains("用户偏好简短回答"));
        assertTrue(dynamicContext.contains("summary block"));
        assertEquals(assembled.contextTrace().get("combined_context_chars"), request.contextTrace().get("combined_context_chars"));
        assertTrue(request.combinedContext().contains("用户偏好简短回答"));
        assertTrue(request.combinedContext().contains("summary block"));
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
