package ricbot.domain.agent;

import ricbot.domain.hook.AgentHook;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillRoutingContext;
import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentContextService {

    private final Path workspace;
    private final ContextBuilder contextBuilder;
    private final MemoryStore memoryStore;
    private final SkillsLoader skillsLoader;
    private final SkillRouter skillRouter;
    private final ToolRegistry tools;
    private final AgentHookFactory hookFactory;
    private final ToolContextApplier toolContextApplier;
    private final List<AgentHook> globalHooks;
    private final ContextSelectionService contextSelectionService;

    AgentContextService(
            Path workspace,
            ContextBuilder contextBuilder,
            MemoryStore memoryStore,
            SkillsLoader skillsLoader,
            SkillRouter skillRouter,
            ToolRegistry tools,
            AgentHookFactory hookFactory,
            ToolContextApplier toolContextApplier,
            List<AgentHook> globalHooks,
            ContextSelectionService contextSelectionService
    ) {
        this.workspace = workspace;
        this.contextBuilder = contextBuilder;
        this.memoryStore = memoryStore;
        this.skillsLoader = skillsLoader;
        this.skillRouter = skillRouter;
        this.tools = tools;
        this.hookFactory = hookFactory;
        this.toolContextApplier = toolContextApplier;
        this.globalHooks = globalHooks;
        this.contextSelectionService = contextSelectionService;
    }

    AgentRequestContext buildInteractiveRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            List<AgentHook> requestHooks,
            int historyWindowMessages
    ) {
        toolContextApplier.apply(msg.getChannel(), msg.getChatId(), messageIdOf(msg));

        String skillsContext = skillsLoader.getSkillsContext();
        SkillRouter.SelectionResult selected = skillRouter.selectAndRender(new SkillRoutingContext(
                workspace,
                msg.getChannel(),
                msg.getChatId(),
                msg.getContent(),
                tools.toolNames(),
                msg.getMetadata(),
                Map.of()
        ));

        ContextSelectionService.SelectionResult selection = contextSelectionService.select(
                new ContextSelectionService.SessionPreparedInputs(
                        prepared.archivedSummary(),
                        prepared.taskStateSnapshot(),
                        recentToolTrace(prepared.session())
                ),
                prepared.session().getMessages(),
                msg.getContent(),
                historyWindowMessages
        );

        String combinedContext = combineContext(
                skillsContext,
                selection.bundle().render(),
                selected.renderedContext()
        );

        List<Map<String, Object>> history = selection.history();
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId(),
                combinedContext,
                "user",
                selection.bundle()
        );

        AgentHook hook = hookFactory.create(msg, globalHooks, requestHooks);
        return new AgentRequestContext(
                msg,
                prepared.sessionKey(),
                prepared.session(),
                combinedContext,
                selection.bundle(),
                history,
                initialMessages,
                hook,
                prepared.userPersistedEarly()
        );
    }

    AgentRequestContext buildSystemRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            String channel,
            String chatId,
            String currentRole,
            int historyWindowMessages
    ) {
        toolContextApplier.apply(channel, chatId, messageIdOf(msg));
        List<Map<String, Object>> history = prepared.session().getHistory(historyWindowMessages);
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                null,
                channel,
                chatId,
                null,
                currentRole,
                null
        );

        return new AgentRequestContext(
                msg,
                prepared.sessionKey(),
                prepared.session(),
                "",
                new PromptContextBundle(),
                history,
                initialMessages,
                null,
                false
        );
    }

    private String combineContext(String skillsContext, String structuredContext, String selectedContext) {
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, skillsContext);
        appendBlock(sb, structuredContext);
        appendBlock(sb, selectedContext);
        return sb.toString();
    }

    private void appendBlock(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append(value);
    }

    private String messageIdOf(InboundMessage msg) {
        if (msg.getMetadata() == null) {
            return null;
        }
        Object value = msg.getMetadata().get("message_id");
        return value != null ? String.valueOf(value) : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recentToolTrace(Session session) {
        if (session == null) {
            return List.of();
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.TOOL_TRACE_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return out;
    }
}
