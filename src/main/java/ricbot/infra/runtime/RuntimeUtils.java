package ricbot.infra.runtime;

/**
 * 运行时工具类，提供用于处理工具执行结果、构建消息以及生成外部查找签名的实用方法。
 */
public final class RuntimeUtils {

    /**
     * 当工具步骤完成但无法生成最终答案时返回的空响应消息。
     */
    public static final String EMPTY_FINAL_RESPONSE_MESSAGE =
            "已完成工具步骤但无法生成最终答案。请重试或缩小任务范围。";

    /**
     * 私有构造函数，防止实例化。
     */
    private RuntimeUtils() {
    }

    /**
     * 判断给定的文本是否为 null 或空白。
     *
     * @param content 待检查的文本
     * @return 如果文本为 null 或空白则返回 true，否则返回 false
     */
    public static boolean isBlankText(String content) {
        return content == null || content.isBlank();
    }
}
