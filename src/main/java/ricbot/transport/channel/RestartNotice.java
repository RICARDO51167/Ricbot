package ricbot.transport.channel;

/**
 * 重启通知信息，占位 record。
 */
public record RestartNotice(
        String channel,
        String chatId,
        String startedAtRaw
) {
}