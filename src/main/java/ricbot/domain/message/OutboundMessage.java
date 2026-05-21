package ricbot.domain.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站消息：表示 Agent 处理完后，要发送回聊天渠道的一条消息。
 */
public class OutboundMessage {

    private String channel;

    private String chatId;

    private String content;

    private String replyTo;

    private List<String> media;

    private Map<String, Object> metadata;

    public OutboundMessage() {
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

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