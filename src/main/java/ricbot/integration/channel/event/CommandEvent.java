// 定义包名，表明该类属于 ricbot.transport.channel.event 包
package ricbot.integration.channel.event;

// 导入 LocalDateTime 类，用于处理时间戳
import java.time.LocalDateTime;
// 导入 Map 接口，用于存储元数据
import java.util.Map;

/**
 * 命令事件记录类。
 * 实现了 ChannelEvent 接口，用于封装来自聊天频道的命令事件信息。
 */
public record CommandEvent(
        // 频道标识符
        String channel,
        // 聊天ID，用于标识具体的聊天会话
        String chatId,
        // 发送者ID，用于标识消息的发送者
        String senderId,
        // 发送者名称，显示用的用户名
        String senderName,
        // 命令名称，不包含前缀 '/'
        String command,
        // 命令参数，命令后的附加文本
        String args,
        // 元数据，存储额外的键值对信息
        Map<String, Object> metadata,
        // 会话密钥覆盖，可选的会话标识
        String sessionKeyOverride,
        // 事件唯一标识符
        String eventId,
        // 事件发生的时间戳
        LocalDateTime timestamp
) implements ChannelEvent {

    /**
     * 返回事件类型。
     *
     * @return 始终返回 ChannelEventType.COMMAND，表示这是一个命令事件
     */
    @Override
    public ChannelEventType type() {
        return ChannelEventType.COMMAND;
    }

    /**
     * 将命令和参数格式化为可显示的文本字符串。
     * 格式为 "/command args" 或 "/command"。
     *
     * @return 格式化后的命令文本，如果命令为空则返回空字符串
     */
    @Override
    public String text() {
        // 获取命令部分，如果为 null 则设为空字符串，并去除首尾空白
        String c = command != null ? command.trim() : "";
        // 获取参数部分，如果为 null 则设为空字符串，并去除首尾空白
        String a = args != null ? args.trim() : "";
        
        // 如果命令部分为空，直接返回空字符串
        if (c.isBlank()) {
            return "";
        }
        
        // 如果参数部分为空，只返回 "/command"
        if (a.isBlank()) {
            return "/" + c;
        }
        
        // 如果参数不为空，返回 "/command args"
        return "/" + c + " " + a;
    }
}

