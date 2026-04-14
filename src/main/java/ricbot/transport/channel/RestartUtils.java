package ricbot.transport.channel;

/**
 * 重启通知工具，占位版。
 */
public class RestartUtils {

    public static RestartNotice consumeRestartNoticeFromEnv() {
        return null;
    }

    public static String formatRestartCompletedMessage(String startedAtRaw) {
        return "Restart completed. Started at: " + startedAtRaw;
    }
}