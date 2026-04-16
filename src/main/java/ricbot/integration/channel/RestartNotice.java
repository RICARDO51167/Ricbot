package ricbot.integration.channel;

/**
 * 重启通知信息
 */
public record RestartNotice(
        String channel,
        String chatId,
        String startedAtRaw
) {
}