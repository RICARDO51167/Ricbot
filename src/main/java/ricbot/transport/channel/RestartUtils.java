package ricbot.transport.channel;

/**
 * 重启通知工具，占位版。
 */
public class RestartUtils {

    public static RestartNotice consumeRestartNoticeFromEnv() {
        String channel = System.getenv("RICBOT_RESTART_CHANNEL");
        String chatId = System.getenv("RICBOT_RESTART_CHAT_ID");
        String startedAt = System.getenv("RICBOT_RESTART_AT");

        if (channel != null && chatId != null) {
            return new RestartNotice(channel, chatId, startedAt);
        }
        return null;
    }

    public static String formatRestartCompletedMessage(String startedAtRaw) {
        return "Restart completed. Started at: " + startedAtRaw;
    }
}