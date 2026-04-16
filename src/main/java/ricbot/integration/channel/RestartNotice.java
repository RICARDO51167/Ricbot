package ricbot.integration.channel;

/**
 * 重启通知信息，占位 record。
 */
public record RestartNotice(
        /**
         * 渠道标识
         */
        String channel,
        /**
         * 聊天ID
         */
        String chatId,
        /**
         * 原始开始时间字符串
         */
        String startedAtRaw
) {
}