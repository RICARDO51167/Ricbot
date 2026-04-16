package ricbot.domain.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站消息：
 * 表示“Agent 处理完后，要发送回聊天渠道的一条消息”。
 *
 * 对应 Python:
 * @dataclass
 * class OutboundMessage:
 */
public class OutboundMessage {

    /**
     * 目标渠道，例如 telegram / discord / slack / whatsapp
     */
    private String channel;

    /**
     * 目标聊天会话 ID。
     */
    private String chatId;

    /**
     * 要发送的文本内容。
     */
    private String content;

    /**
     * 可选的回复目标 ID。
     *
     * 用于“回复某一条具体消息”的场景，
     * 例如 Telegram reply_to_message_id、Slack thread reply 等。
     */
    private String replyTo;

    /**
     * 要发送的媒体资源列表。
     */
    private List<String> media;

    /**
     * 渠道相关的扩展元数据。
     */
    private Map<String, Object> metadata;

    /**
     * 无参构造。
     *
     * 默认初始化：
     * - media = 空列表
     * - metadata = 空 Map
     */
    public OutboundMessage() {
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 常用构造方法。
     *
     * @param channel 目标渠道
     * @param chatId  目标聊天会话 ID
     * @param content 要发送的文本内容
     */
    public OutboundMessage(String channel, String chatId, String content) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public List<String> getMedia() {
        return media;
    }

    public void setMedia(List<String> media) {
        this.media = media;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "channel='" + channel + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + content + '\'' +
                ", replyTo='" + replyTo + '\'' +
                ", media=" + media +
                ", metadata=" + metadata +
                '}';
    }
}