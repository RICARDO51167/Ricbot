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

/**
 * 代理上下文服务类，负责构建代理请求所需的上下文信息
 *
 * @param workspace               工作空间路径
 * @param contextBuilder          上下文构建器
 * @param memoryStore             内存存储
 * @param skillsLoader            技能加载器
 * @param skillRouter             技能路由器
 * @param tools                   工具注册表
 * @param hookFactory             代理钩子工厂
 * @param toolContextApplier      工具上下文应用器
 * @param globalHooks             全局钩子列表
 * @param contextSelectionService 上下文选择服务
 */
record AgentContextService(Path workspace, ContextBuilder contextBuilder, MemoryStore memoryStore,
                           SkillsLoader skillsLoader, SkillRouter skillRouter, ToolRegistry tools,
                           AgentHookFactory hookFactory, ToolContextApplier toolContextApplier,
                           List<AgentHook> globalHooks, ContextSelectionService contextSelectionService) {

    /**
     * 构建交互式请求上下文
     *
     * @param msg                  入站消息
     * @param prepared             预处理的会话上下文
     * @param requestHooks         请求级别的钩子列表
     * @param historyWindowMessages 历史消息窗口大小
     * @return 构建好的代理请求上下文
     */
    AgentRequestContext buildInteractiveRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            List<AgentHook> requestHooks,
            int historyWindowMessages
    ) {
        // 应用工具上下文，设置通道、聊天ID和消息ID
        toolContextApplier.apply(msg.getChannel(), msg.getChatId(), messageIdOf(msg));

        SkillRoutingContext skillRoutingContext = new SkillRoutingContext(
                workspace,
                msg.getChannel(),
                msg.getChatId(),
                msg.getContent(),
                tools.toolNames(),
                msg.getMetadata(),
                Map.of()
        );
        SkillRouter.SelectionResult selected = skillRouter.selectAndRenderProgressive(skillRoutingContext);

        // 根据会话准备输入（如归档摘要、任务状态快照、最近工具追踪）、消息内容等选择上下文
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

        // 合并后的上下文用于诊断/测试；实际 system prompt 中结构化上下文和技能上下文分槽注入，避免重复。
        String structuredContext = selection.bundle().render();
        String skillContext = skillsContext(selected.renderedContext());
        String combinedContext = combineContext(structuredContext, skillContext);

        // 获取经过筛选的历史消息列表
        List<Map<String, Object>> history = selection.history();
        
        // 构建初始消息列表，包含历史消息、当前消息内容、媒体信息、渠道信息及合并后的上下文
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId(),
                "",
                skillContext,
                "user",
                selection.bundle()
        );

        // 创建代理钩子，整合全局钩子和请求级钩子
        AgentHook hook = hookFactory.create(msg, globalHooks, requestHooks);
        
        // 返回构建好的代理请求上下文对象
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

    /**
     * 构建系统请求上下文
     *
     * @param msg                  入站消息
     * @param prepared             预处理的会话上下文
     * @param channel              通信渠道
     * @param chatId               聊天ID
     * @param currentRole          当前角色
     * @param historyWindowMessages 历史消息窗口大小
     * @return 构建好的系统代理请求上下文
     */
    AgentRequestContext buildSystemRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            String channel,
            String chatId,
            String currentRole,
            int historyWindowMessages
    ) {
        // 应用工具上下文，设置通道、聊天ID和消息ID
        toolContextApplier.apply(channel, chatId, messageIdOf(msg));

        // 获取指定窗口大小的历史消息列表
        List<Map<String, Object>> history = prepared.session().getHistory(historyWindowMessages);

        // 构建初始消息列表，系统请求通常不包含媒体和额外上下文，因此相关参数传null
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

        // 返回构建好的系统代理请求上下文，其中上下文字符串为空，Bundle为空对象，无钩子
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

    /**
     * 合并多个上下文字符串块
     *
     * @param blocks 待合并的字符串块
     * @return 合并后的字符串
     */
    private String combineContext(String... blocks) {
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            appendBlock(sb, block);
        }
        return sb.toString();
    }

    private String skillsContext(String loadedSkillsContext) {
        String summary = skillsLoader.buildSkillsSummary();
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("## Skills Summary\n");
            sb.append("Only summary metadata is loaded by default. Use the read_skill tool to load a skill's full SKILL.md before following it, unless the skill is already included below.\n");
            sb.append(summary);
        }
        if (loadedSkillsContext != null && !loadedSkillsContext.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Loaded Skills\n").append(loadedSkillsContext);
        }
        return sb.toString();
    }

    /**
     * 向StringBuilder中追加非空文本块，并在需要时添加换行符分隔
     *
     * @param sb    目标StringBuilder
     * @param value 待追加的值
     */
    private void appendBlock(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append(value);
    }

    /**
     * 从入站消息元数据中提取消息ID
     *
     * @param msg 入站消息
     * @return 消息ID字符串，若不存在则返回null
     */
    private String messageIdOf(InboundMessage msg) {
        if (msg.getMetadata() == null) {
            return null;
        }
        Object value = msg.getMetadata().get("message_id");
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 获取最近的工具调用追踪记录
     *
     * @param session 会话对象
     * @return 工具追踪记录列表，每个元素为Map结构
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recentToolTrace(Session session) {
        if (session == null) {
            return List.of();
        }
        // 从会话元数据中获取原始的工具追踪对象
        Object raw = session.getMetadata().get(SessionRuntimeKeys.TOOL_TRACE_KEY);
        // 检查是否为List类型
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        // 遍历列表，将每个Map元素转换为LinkedHashMap以保持顺序
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return out;
    }
}
