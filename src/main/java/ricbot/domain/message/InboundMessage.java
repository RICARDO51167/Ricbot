package ricbot.domain.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站消息
 */
public class InboundMessage {

    private String channel;

    private String senderId;

    private String chatId;

    private String content;

    private List<String> media;

    private Map<String, Object> metadata;

    private String sessionKeyOverride;

    public InboundMessage() {
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public String getSessionKey() {
        return (sessionKeyOverride != null && !sessionKeyOverride.isEmpty())
                ? sessionKeyOverride
                : channel + ":" + chatId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
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

    public String getSessionKeyOverride() {
        return sessionKeyOverride;
    }

    public void setSessionKeyOverride(String sessionKeyOverride) {
        this.sessionKeyOverride = sessionKeyOverride;
    }

    @Override
    public String toString() {
        return "InboundMessage{" +
                "channel='" + channel + '\'' +
                ", senderId='" + senderId + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + content + '\'' +
                ", media=" + media +
                ", metadata=" + metadata +
                ", sessionKeyOverride='" + sessionKeyOverride + '\'' +
                '}';
    }
}
