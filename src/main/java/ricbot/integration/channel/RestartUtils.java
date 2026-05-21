package ricbot.integration.channel;

import ricbot.infra.runtime.RestartSupport;

/**
 * 重启通知工具类
 */
public class RestartUtils {

    public static RestartNotice consumeRestartNoticeFromEnv() {
        RestartSupport.RestartNotice notice = RestartSupport.consumeRestartNoticeFromEnv();
        if (notice == null) {
            return null;
        }
        return new RestartNotice(notice.channel(), notice.chatId(), notice.startedAtRaw());
    }

    public static String formatRestartCompletedMessage(String startedAtRaw) {
        return RestartSupport.formatRestartCompletedMessage(startedAtRaw);
    }
}
