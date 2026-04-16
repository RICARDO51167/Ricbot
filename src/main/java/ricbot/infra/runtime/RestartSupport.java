package ricbot.infra.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重启支持工具类，用于管理重启通知相关的环境变量模拟与处理。
 */
public final class RestartSupport {

    public static final String RESTART_NOTIFY_CHANNEL_ENV = "RICBOT_RESTART_NOTIFY_CHANNEL";
    public static final String RESTART_NOTIFY_CHAT_ID_ENV = "RICBOT_RESTART_NOTIFY_CHAT_ID";
    public static final String RESTART_STARTED_AT_ENV = "RICBOT_RESTART_STARTED_AT";

    public static final String LEGACY_RESTART_NOTIFY_CHANNEL_ENV = "RICBOT_RESTART_CHANNEL";
    public static final String LEGACY_RESTART_NOTIFY_CHAT_ID_ENV = "RICBOT_RESTART_CHAT_ID";
    public static final String LEGACY_RESTART_STARTED_AT_ENV = "RICBOT_RESTART_AT";

    private static final Map<String, String> ENV_OVERLAY = new ConcurrentHashMap<>();

    private RestartSupport() {
    }

    public record RestartNotice(String channel, String chatId, String startedAtRaw) {
    }

    public static String formatRestartCompletedMessage(String startedAtRaw) {
        String suffix = "";
        if (startedAtRaw != null && !startedAtRaw.isBlank()) {
            try {
                double elapsed = Math.max(0.0, System.currentTimeMillis() / 1000.0 - Double.parseDouble(startedAtRaw));
                suffix = String.format(" in %.1fs", elapsed);
            } catch (Exception ignored) {
            }
        }
        return "重启完成" + suffix + "。";
    }

    public static void setRestartNoticeToEnv(String channel, String chatId) {
        ENV_OVERLAY.put(RESTART_NOTIFY_CHANNEL_ENV, channel);
        ENV_OVERLAY.put(RESTART_NOTIFY_CHAT_ID_ENV, chatId);
        ENV_OVERLAY.put(RESTART_STARTED_AT_ENV, String.valueOf(System.currentTimeMillis() / 1000.0));

        ENV_OVERLAY.put(LEGACY_RESTART_NOTIFY_CHANNEL_ENV, channel);
        ENV_OVERLAY.put(LEGACY_RESTART_NOTIFY_CHAT_ID_ENV, chatId);
        ENV_OVERLAY.put(LEGACY_RESTART_STARTED_AT_ENV, ENV_OVERLAY.get(RESTART_STARTED_AT_ENV));
    }

    public static RestartNotice consumeRestartNoticeFromEnv() {
        String channel = pop(RESTART_NOTIFY_CHANNEL_ENV);
        String chatId = pop(RESTART_NOTIFY_CHAT_ID_ENV);
        String startedAtRaw = pop(RESTART_STARTED_AT_ENV);

        if (isBlank(channel) || isBlank(chatId)) {
            channel = pop(LEGACY_RESTART_NOTIFY_CHANNEL_ENV);
            chatId = pop(LEGACY_RESTART_NOTIFY_CHAT_ID_ENV);
            startedAtRaw = pop(LEGACY_RESTART_STARTED_AT_ENV);
        }

        if (isBlank(channel) || isBlank(chatId)) {
            return null;
        }
        return new RestartNotice(channel, chatId, startedAtRaw);
    }

    public static boolean shouldShowCliRestartNotice(RestartNotice notice, String sessionId) {
        if (notice == null) {
            return false;
        }
        if (!"cli".equals(notice.channel())) {
            return false;
        }
        return sessionId != null && sessionId.equals(notice.chatId());
    }

    private static String pop(String key) {
        String v = ENV_OVERLAY.remove(key);
        if (v != null) {
            return v;
        }
        return System.getenv(key);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
