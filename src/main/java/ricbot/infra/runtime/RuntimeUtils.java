package ricbot.infra.runtime;

/**
 * 运行时工具类
 */
public final class RuntimeUtils {

    public static final String EMPTY_FINAL_RESPONSE_MESSAGE =
            "已完成工具步骤但无法生成最终答案。请重试或缩小任务范围。";

    private RuntimeUtils() {
    }

    public static boolean isBlankText(String content) {
        return content == null || content.isBlank();
    }
}
