// 定义包路径，用于组织类文件
package ricbot.integration.channel.event;

// 导入 LocalDateTime 类，用于处理日期和时间
import java.time.LocalDateTime;
// 导入 List 接口，用于存储媒体文件列表
import java.util.List;
// 导入 Map 接口，用于存储元数据键值对
import java.util.Map;

/**
 * 表示传入消息事件的数据记录。
 * 该记录实现了 ChannelEvent 接口，封装了来自聊天渠道的消息详细信息。
 */
public record IncomingMessageEvent(
        // 消息来源的渠道标识（如微信、钉钉等）
        String channel,
        // 聊天会话的唯一标识 ID
        String chatId,
        // 发送者的唯一标识 ID
        String senderId,
        // 发送者的显示名称
        String senderName,
        // 消息的具体文本内容
        String content,
        // 消息中包含的媒体文件 URL 或标识列表
        List<String> media,
        // 附加的元数据信息，以键值对形式存储
        Map<String, Object> metadata,
        // 可选的会话密钥覆盖值，用于特定会话管理
        String sessionKeyOverride,
        // 事件的唯一标识 ID
        String eventId,
        // 事件发生的时间戳
        LocalDateTime timestamp
) implements ChannelEvent {

    /**
     * 返回当前事件的类型。
     *
     * @return 固定返回 INCOMING_MESSAGE，表示这是一个传入消息事件
     */
    @Override
    public ChannelEventType type() {
        return ChannelEventType.INCOMING_MESSAGE;
    }

    /**
     * 获取消息的文本内容。
     * 如果内容为 null，则返回空字符串以避免空指针异常。
     *
     * @return 消息文本内容，若为空则返回 ""
     */
    @Override
    public String text() {
        return content != null ? content : "";
    }
}

