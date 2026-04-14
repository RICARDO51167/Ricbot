package ricbot.tool.api;

/**
 * 需要感知当前 channel/chat/message 路由上下文的工具，实现这个接口。
 */
public interface ToolContextAware {

    /**
     * 设置当前工具调用上下文。
     *
     * @param channel   来源渠道
     * @param chatId    聊天 ID
     * @param messageId 消息 ID
     */
    void setContext(String channel, String chatId, String messageId);
}