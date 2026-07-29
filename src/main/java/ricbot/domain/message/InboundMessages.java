package ricbot.domain.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InboundMessages {

    // 私有构造函数，防止实例化
    private InboundMessages() {
    }

    /**
     * 创建简单的入站消息对象
     *
     * @param channel   渠道
     * @param senderId  发送者ID
     * @param chatId    聊天ID
     * @param content   内容
     * @return InboundMessage 对象
     */
    public static InboundMessage of(String channel, String senderId, String chatId, String content) {
        // 调用完整参数的of方法，使用默认值
        return of(channel, senderId, chatId, content, List.of(), Map.of(), null);
    }

    /**
     * 创建完整的入站消息对象
     *
     * @param channel             渠道
     * @param senderId            发送者ID
     * @param chatId              聊天ID
     * @param content             内容
     * @param media               媒体列表
     * @param metadata            元数据
     * @param sessionKeyOverride  会话密钥覆盖
     * @return InboundMessage 对象
     */
    public static InboundMessage of(
            String channel,
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKeyOverride
    ) {
        // 创建新的InboundMessage实例
        InboundMessage msg = new InboundMessage();
        // 设置渠道
        msg.setChannel(channel);
        // 设置发送者ID
        msg.setSenderId(senderId);
        // 设置聊天ID
        msg.setChatId(chatId);
        // 设置内容
        msg.setContent(content);
        // 设置媒体列表，如果为null则初始化为空列表
        msg.setMedia(media != null ? new ArrayList<>(media) : new ArrayList<>());
        // 设置元数据，如果为null则初始化为空Map
        msg.setMetadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        // 设置会话密钥覆盖
        msg.setSessionKeyOverride(sessionKeyOverride);
        // 返回构建好的消息对象
        return msg;
    }
}
