package ricbot.tool.message;


import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.core.message.OutboundMessage;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 对应 Python: MessageTool
 *
 * 主要目标：
 * 1. 让 agent 主动发送消息到当前 channel/chat
 * 2. 支持 reply_to
 * 3. 记录当前 turn 内是否发送过消息
 */
public class MessageTool extends Tool {

    /**
     * 发消息回调
     */
    private final Consumer<OutboundMessage> sendCallback;

    private String channel = "cli";
    private String chatId = "direct";
    private String messageId;

    /**
     * 对应 Python: _sent_in_turn
     */
    private final AtomicBoolean sentInTurn = new AtomicBoolean(false);

    public MessageTool(Consumer<OutboundMessage> sendCallback) {
        this.sendCallback = sendCallback;
    }

    @Override
    public String getName() {
        return "message";
    }

    @Override
    public String getDescription() {
        return "Send a message to the current chat immediately.";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("content", "string", "Message content to send", true),
                ToolParam.of("reply_to", "string", "Optional message id to reply to", false)
        );
    }

    /**
     * 对应 Python: set_context(channel, chat_id, message_id)
     */
    public void setContext(String channel, String chatId, String messageId) {
        if (channel != null && !channel.isBlank()) {
            this.channel = channel;
        }
        if (chatId != null && !chatId.isBlank()) {
            this.chatId = chatId;
        }
        this.messageId = messageId;
    }

    /**
     * 对应 Python: start_turn()
     */
    public void startTurn() {
        sentInTurn.set(false);
    }

    public boolean isSentInTurn() {
        return sentInTurn.get();
    }

    /**
     * 工具执行
     */
    public String execute(String content, String replyTo) {
        if (content == null) {
            content = "";
        }

        OutboundMessage msg = new OutboundMessage();
        msg.setChannel(channel);
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setReplyTo(replyTo != null && !replyTo.isBlank() ? replyTo : messageId);
        msg.setMetadata(new HashMap<>());

        if (sendCallback != null) {
            sendCallback.accept(msg);
        }

        sentInTurn.set(true);
        return "Message sent.";
    }
}