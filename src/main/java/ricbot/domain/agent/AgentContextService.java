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

// 代理上下文服务类，负责构建代理请求所需的上下文信息
final class AgentContextService {

    // 工作空间路径
    private final Path workspace;
    // 上下文构建器
    private final ContextBuilder contextBuilder;
    // 内存存储
    private final MemoryStore memoryStore;
    // 技能加载器
    private final SkillsLoader skillsLoader;
    // 技能路由器
    private final SkillRouter skillRouter;
    // 工具注册表
    private final ToolRegistry tools;
    // 代理钩子工厂
    private final AgentHookFactory hookFactory;
    // 工具上下文应用器
    private final ToolContextApplier toolContextApplier;
    // 全局钩子列表
    private final List<AgentHook> globalHooks;
    // 上下文选择服务
    private final ContextSelectionService contextSelectionService;

    // 构造函数，初始化所有依赖项
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

    // 构建交互式请求上下文
    AgentRequestContext buildInteractiveRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            List<AgentHook> requestHooks,
            int historyWindowMessages
    ) {
        // 应用工具上下文，设置通道、聊天ID和消息ID
        toolContextApplier.apply(msg.getChannel(), msg.getChatId(), messageIdOf(msg));

        // 获取技能上下文
        String skillsContext = skillsLoader.getSkillsContext();
        // 执行技能路由选择并渲染结果
        SkillRouter.SelectionResult selected = skillRouter.selectAndRender(new SkillRoutingContext(
                workspace,
                msg.getChannel(),
                msg.getChatId(),
                msg.getContent(),
                tools.toolNames(),
                msg.getMetadata(),
                Map.of()
        ));

        // 根据会话准备输入、消息内容等选择上下文
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

        // 合并技能上下文、结构化上下文和选定的上下文
        String combinedContext = combineContext(
                skillsContext,
                selection.bundle().render(),
                selected.renderedContext()
        );

        // 获取历史消息
        List<Map<String, Object>> history = selection.history();
        // 构建初始消息列表
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

        // 创建代理钩子
        AgentHook hook = hookFactory.create(msg, globalHooks, requestHooks);
        // 返回构建好的代理请求上下文
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

    // 构建系统请求上下文
    AgentRequestContext buildSystemRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            String channel,
            String chatId,
            String currentRole,
            int historyWindowMessages
    ) {
        // 应用工具上下文
        toolContextApplier.apply(channel, chatId, messageIdOf(msg));
        // 获取指定窗口大小的历史消息
        List<Map<String, Object>> history = prepared.session().getHistory(historyWindowMessages);
        // 构建初始消息列表，系统请求通常不包含媒体和额外上下文
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

        // 返回构建好的系统代理请求上下文
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

    // 合并多个上下文字符串
    private String combineContext(String skillsContext, String structuredContext, String selectedContext) {
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, skillsContext);
        appendBlock(sb, structuredContext);
        appendBlock(sb, selectedContext);
        return sb.toString();
    }

    // 向StringBuilder中追加非空文本块，并在需要时添加换行符
    private void appendBlock(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append(value);
    }

    // 从入站消息元数据中提取消息ID
    private String messageIdOf(InboundMessage msg) {
        if (msg.getMetadata() == null) {
            return null;
        }
        Object value = msg.getMetadata().get("message_id");
        return value != null ? String.valueOf(value) : null;
    }

    // 获取最近的工具调用追踪记录
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
