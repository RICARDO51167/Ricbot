package ricbot.integration.channel.event;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ChannelEvent {

    /**
     * 获取渠道事件的类型
     *
     * @return 渠道事件类型枚举
     */
    ChannelEventType type();

    /**
     * 获取渠道标识（如微信、钉钉等）
     *
     * @return 渠道名称
     */
    String channel();

    /**
     * 获取聊天会话ID
     *
     * @return 聊天ID
     */
    String chatId();

    /**
     * 获取发送者ID
     *
     * @return 发送者唯一标识
     */
    String senderId();

    /**
     * 获取发送者名称，默认为空
     *
     * @return 发送者名称，若不存在则返回 null
     */
    default String senderName() {
        return null;
    }

    /**
     * 获取事件唯一ID，默认为空
     *
     * @return 事件ID，若不存在则返回 null
     */
    default String eventId() {
        return null;
    }

    /**
     * 获取会话密钥覆盖值，用于自定义会话键生成逻辑，默认为空
     *
     * @return 会话密钥覆盖值，若不需要覆盖则返回 null
     */
    default String sessionKeyOverride() {
        return null;
    }

    /**
     * 获取事件发生的时间戳，默认为空
     *
     * @return 事件时间，若未提供则返回 null
     */
    default LocalDateTime timestamp() {
        return null;
    }

    /**
     * 获取事件的元数据映射，默认为空映射
     *
     * @return 元数据 Map，若不存在则返回空 Map
     */
    default Map<String, Object> metadata() {
        return Map.of();
    }

    /**
     * 获取事件的文本内容，默认为空字符串
     *
     * @return 文本消息内容
     */
    default String text() {
        return "";
    }

    /**
     * 获取事件的媒体资源列表（如图片、视频链接等），默认为空列表
     *
     * @return 媒体资源 URL 列表
     */
    default List<String> media() {
        return List.of();
    }

    /**
     * 将当前渠道事件转换为标准的入站消息对象 InboundMessage
     *
     * @return 转换后的 InboundMessage 对象
     */
    default InboundMessage toInboundMessage() {
        // 处理元数据，复制现有元数据并确保可修改
        Map<String, Object> meta = metadata() != null ? new HashMap<>(metadata()) : new HashMap<>();
        // 强制写入事件类型，确保元数据中包含该字段
        meta.putIfAbsent("_event_type", type().name());
        // 如果事件ID存在且非空白，则写入元数据
        if (eventId() != null && !eventId().isBlank()) {
            meta.putIfAbsent("_event_id", eventId());
        }
        // 如果发送者名称存在且非空白，则写入元数据
        if (senderName() != null && !senderName().isBlank()) {
            meta.putIfAbsent("sender_name", senderName());
        }
        return InboundMessages.of(
                channel(),
                senderId(),
                chatId(),
                text(),
                media(),
                meta,
                sessionKeyOverride(),
                timestamp()
        );
    }
}
