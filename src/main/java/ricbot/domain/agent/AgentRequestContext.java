package ricbot.domain.agent;

import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;

import java.util.List;
import java.util.Map;

// 代理请求上下文类，用于封装处理代理请求所需的所有相关信息
final class AgentRequestContext {

    // 入站消息对象，包含用户发送的原始消息内容
    private final InboundMessage message;
    // 会话唯一标识键，用于区分不同的用户会话
    private final String sessionKey;
    // 会话对象，包含会话的状态和元数据
    private final Session session;
    // 组合后的上下文信息，通常包括历史消息和系统提示等
    private final String combinedContext;
    // 提示词上下文 bundle，包含构建提示词所需的结构化数据
    private final PromptContextBundle promptContext;
    // 历史消息列表，记录之前的对话交互
    private final List<Map<String, Object>> history;
    // 初始消息列表，会话开始时预设的消息
    private final List<Map<String, Object>> initialMessages;
    // 代理钩子对象，用于在请求处理过程中插入自定义逻辑
    private final AgentHook hook;
    // 标记用户是否早期持久化，可能影响会话保存策略
    private final boolean userPersistedEarly;

    /**
     * 构造函数，初始化代理请求上下文的所有字段
     *
     * @param message           入站消息
     * @param sessionKey        会话键
     * @param session           会话对象
     * @param combinedContext   组合上下文
     * @param promptContext     提示词上下文 bundle
     * @param history           历史消息列表
     * @param initialMessages   初始消息列表
     * @param hook              代理钩子
     * @param userPersistedEarly 用户早期持久化标志
     */
    AgentRequestContext(
            InboundMessage message,
            String sessionKey,
            Session session,
            String combinedContext,
            PromptContextBundle promptContext,
            List<Map<String, Object>> history,
            List<Map<String, Object>> initialMessages,
            AgentHook hook,
            boolean userPersistedEarly
    ) {
        this.message = message; // 赋值入站消息
        this.sessionKey = sessionKey; // 赋值会话键
        this.session = session; // 赋值会话对象
        this.combinedContext = combinedContext; // 赋值组合上下文
        this.promptContext = promptContext; // 赋值提示词上下文 bundle
        this.history = history; // 赋值历史消息列表
        this.initialMessages = initialMessages; // 赋值初始消息列表
        this.hook = hook; // 赋值代理钩子
        this.userPersistedEarly = userPersistedEarly; // 赋值用户早期持久化标志
    }

    /**
     * 获取入站消息
     *
     * @return InboundMessage 对象
     */
    InboundMessage message() {
        return message;
    }

    /**
     * 获取会话键
     *
     * @return 会话键字符串
     */
    String sessionKey() {
        return sessionKey;
    }

    /**
     * 获取会话对象
     *
     * @return Session 对象
     */
    Session session() {
        return session;
    }

    /**
     * 获取组合上下文
     *
     * @return 组合上下文字符串
     */
    String combinedContext() {
        return combinedContext;
    }

    /**
     * 获取提示词上下文 bundle
     *
     * @return PromptContextBundle 对象
     */
    PromptContextBundle promptContext() {
        return promptContext;
    }

    /**
     * 获取历史消息列表
     *
     * @return 历史消息列表
     */
    List<Map<String, Object>> history() {
        return history;
    }

    /**
     * 获取初始消息列表
     *
     * @return 初始消息列表
     */
    List<Map<String, Object>> initialMessages() {
        return initialMessages;
    }

    /**
     * 获取代理钩子
     *
     * @return AgentHook 对象
     */
    AgentHook hook() {
        return hook;
    }

    /**
     * 获取用户早期持久化标志
     *
     * @return 布尔值，表示用户是否早期持久化
     */
    boolean userPersistedEarly() {
        return userPersistedEarly;
    }
}
