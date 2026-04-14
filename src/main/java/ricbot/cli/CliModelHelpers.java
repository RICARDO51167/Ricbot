package ricbot.cli;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对应 Python: models.py
 *
 * 主要目标：
 * 1. 为 onboard 向导保留“模型信息查询”接口
 * 2. 当前模型数据库关闭时，返回空结果
 * 3. 保持调用方签名稳定
 */
public final class CliModelHelpers {

    private CliModelHelpers() {
    }

    /**
     * 获取全部模型列表。
     *
     * 当前保持空实现，对应 Python 中的临时禁用逻辑。
     */
    public static List<String> getAllModels() {
        return Collections.emptyList();
    }

    /**
     * 查找模型信息。
     *
     * 当前返回 null，表示没有内置模型信息库。
     */
    public static Map<String, Object> findModelInfo(String modelName) {
        return null;
    }

    /**
     * 获取模型上下文窗口上限。
     *
     * 当前返回 null，表示无法自动推断。
     */
    public static Integer getModelContextLimit(String model, String provider) {
        return null;
    }

    /**
     * 获取模型补全建议。
     *
     * 当前返回空列表。
     */
    public static List<String> getModelSuggestions(String partial, String provider, int limit) {
        return Collections.emptyList();
    }

    /**
     * 格式化 token 数字，例如 200000 -> 200,000
     */
    public static String formatTokenCount(int tokens) {
        return NumberFormat.getNumberInstance(Locale.US).format(tokens);
    }
}