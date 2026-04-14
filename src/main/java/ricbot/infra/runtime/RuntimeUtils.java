package ricbot.infra.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeUtils {

    public static final int MAX_REPEAT_EXTERNAL_LOOKUPS = 2;

    public static final String EMPTY_FINAL_RESPONSE_MESSAGE =
            "I completed the tool steps but couldn't produce a final answer. Please try again or narrow the task.";

    public static final String FINALIZATION_RETRY_PROMPT =
            "Please provide your response to the user based on the conversation above.";

    public static final String LENGTH_RECOVERY_PROMPT =
            "Output limit reached. Continue exactly where you left off — no recap, no apology. Break remaining work into smaller steps if needed.";

    private RuntimeUtils() {
    }

    public static String emptyToolResultMessage(String toolName) {
        return "(" + toolName + " completed with no output)";
    }

    public static Object ensureNonemptyToolResult(String toolName, Object content) {
        if (content == null) {
            return emptyToolResultMessage(toolName);
        }
        if (content instanceof String s && s.isBlank()) {
            return emptyToolResultMessage(toolName);
        }
        if (content instanceof List<?> list && list.isEmpty()) {
            return emptyToolResultMessage(toolName);
        }
        return content;
    }

    public static boolean isBlankText(String content) {
        return content == null || content.isBlank();
    }

    public static Map<String, String> buildFinalizationRetryMessage() {
        Map<String, String> m = new HashMap<>();
        m.put("role", "user");
        m.put("content", FINALIZATION_RETRY_PROMPT);
        return m;
    }

    public static Map<String, String> buildLengthRecoveryMessage() {
        Map<String, String> m = new HashMap<>();
        m.put("role", "user");
        m.put("content", LENGTH_RECOVERY_PROMPT);
        return m;
    }

    public static String externalLookupSignature(String toolName, Map<String, Object> arguments) {
        if ("web_fetch".equals(toolName)) {
            String url = value(arguments, "url");
            if (!url.isBlank()) {
                return "web_fetch:" + url.toLowerCase();
            }
        }
        if ("web_search".equals(toolName)) {
            String query = value(arguments, "query");
            if (query.isBlank()) {
                query = value(arguments, "search_term");
            }
            if (!query.isBlank()) {
                return "web_search:" + query.toLowerCase();
            }
        }
        return null;
    }

    private static String value(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object v = map.get(key);
        return v != null ? String.valueOf(v).trim() : "";
    }
}