package ricbot.integration.api;

/**
 * 运行时常量。
 */
public final class RuntimeConstants {

    private RuntimeConstants() {
    }

    /**
     * 当 Agent 最终没有给出有效回复时的兜底文本。
     */
    public static final String EMPTY_FINAL_RESPONSE_MESSAGE =
            "处理已完成，但无响应可提供。";
}