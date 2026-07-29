package ricbot.domain.agent.dto;

import ricbot.domain.agent.*;
import ricbot.domain.message.InboundMessage;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理上下文服务类，负责构建代理请求所需的上下文信息
 *
 * @param workspace               工作空间路径
 * @param contextBuilder          上下文构建器
 * @param hookFactory             代理钩子工厂
 * @param contextSelectionService 上下文选择服务
 */
public record AgentContextService(Path workspace, ContextBuilder contextBuilder,
                           AgentHookFactory hookFactory,
                           ContextSelectionService contextSelectionService) {

    /**
     * 构建交互式请求上下文
     *
     * @param msg                  入站消息
     * @param prepared             预处理的会话上下文
     * @param historyWindowMessages 历史消息窗口大小
     * @return 构建好的代理请求上下文
     */
    public AgentRequestContext buildInteractiveRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            int historyWindowMessages
    ) {
        ContextAssembler.AssembledContext assembled = new ContextAssembler(
                workspace,
                contextBuilder,
                contextSelectionService
        ).buildInteractiveContext(
                msg,
                prepared,
                historyWindowMessages
        );

        var hook = hookFactory.create(msg);
        
        // 返回构建好的代理请求上下文对象
        return new AgentRequestContext(
                msg,
                prepared.sessionKey(),
                prepared.session(),
                assembled.combinedContext(),
                assembled.bundle(),
                assembled.history(),
                assembled.initialMessages(),
                hook,
                prepared.userPersistedEarly(),
                assembled.contextTrace()
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
    public AgentRequestContext buildSystemRequest(
            InboundMessage msg,
            PreparedSessionContext prepared,
            String channel,
            String chatId,
            String currentRole,
            int historyWindowMessages
    ) {
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
                false,
                buildSystemContextTrace(prepared, history, initialMessages)
        );
    }

    private Map<String, Object> buildSystemContextTrace(
            PreparedSessionContext prepared,
            List<Map<String, Object>> history,
            List<Map<String, Object>> initialMessages
    ) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("mode", "system");
        trace.put("session_key", prepared.sessionKey());
        trace.put("history_candidates", prepared.session() != null ? prepared.session().getMessages().size() : 0);
        trace.put("history_selected", history != null ? history.size() : 0);
        trace.put("initial_message_count", initialMessages != null ? initialMessages.size() : 0);
        trace.put("structured_context_chars", 0);
        trace.put("skills_context_chars", 0);
        trace.put("combined_context_chars", 0);
        return trace;
    }

}
