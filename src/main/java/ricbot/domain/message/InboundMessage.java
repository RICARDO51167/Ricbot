package ricbot.domain.message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站消息：
 * 表示“从外部聊天渠道接收到的一条消息”。
 *
 * 对应 Python:
 * @dataclass
 * class InboundMessage:
 */
public class InboundMessage {

    /**
     * 消息来源渠道，例如：
     * telegram / discord / slack / whatsapp
     */
    private String channel;

    /**
     * 发送者 ID，用于标识是谁发来的消息。
     */
    private String senderId;

    /**
     * 聊天 ID / 会话 ID / 群聊 ID。
     * 用于标识消息属于哪个聊天会话。
     */
    private String chatId;

    /**
     * 消息正文内容。
     */
    private String content;

    /**
     * 消息接收时间。
     *
     * Python 中使用：
     * field(default_factory=datetime.now)
     * 表示默认值为“创建对象时的当前时间”。
     */
    private LocalDateTime timestamp;

    /**
     * 媒体资源列表，例如图片、音频、视频 URL。
     *
     * Python 中：
     * media: list[str] = field(default_factory=list)
     */
    private List<String> media;

    /**
     * 渠道相关的扩展元数据。
     * 例如 Telegram message_id、Slack thread_ts 等。
     *
     * Python 中：
     * metadata: dict[str, Any] = field(default_factory=dict)
     */
    private Map<String, Object> metadata;

    /**
     * 可选的 session key 覆盖值。
     *
     * 默认情况下 session_key = channel + ":" + chatId
     * 但某些场景下可能需要按线程、子会话等维度手动覆盖。
     */
    private String sessionKeyOverride;

    /**
     * 无参构造。
     *
     * 默认初始化：
     * - timestamp = 当前时间
     * - media = 空列表
     * - metadata = 空 Map
     */
    public InboundMessage() {
        this.timestamp = LocalDateTime.now();
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 常用构造方法。
     *
     * @param channel  消息来源渠道
     * @param senderId 发送者 ID
     * @param chatId   聊天/会话 ID
     * @param content  消息正文
     */
    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
        this.timestamp = LocalDateTime.now();
        this.media = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 获取 session key。
     *
     * 对应 Python:
     * @property
     * def session_key(self) -> str:
     *     return self.session_key_override or f"{self.channel}:{self.chat_id}"
     *
     * 逻辑：
     * - 如果显式设置了 sessionKeyOverride，则优先使用它
     * - 否则使用默认规则：channel:chatId
     *
     * @return 唯一会话标识
     */
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

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
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
                ", timestamp=" + timestamp +
                ", media=" + media +
                ", metadata=" + metadata +
                ", sessionKeyOverride='" + sessionKeyOverride + '\'' +
                '}';
    }
}