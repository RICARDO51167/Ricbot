package ricbot.app.cli;

import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 对应 Python: onboard.py
 *
 * 主要目标：
 * 1. 交互式配置 ricbot
 * 2. 支持主菜单与分区配置
 * 3. 支持字段浏览、输入、保存、放弃
 *
 * 说明：
 * Python 版 heavily 依赖 questionary + rich + pydantic 反射。
 * Java 这里改成 Scanner + 反射 的通用实现，但保留整体职责。
 */
public final class OnboardWizard {

    // 定义敏感字段的关键字集合，用于后续掩码处理
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "api_key", "token", "secret", "password", "credentials"
    );

    // 定义特定字段的选项提示映射，例如 reasoningEffort 字段的选择项和提示文本
    private static final Map<String, SelectHint> SELECT_FIELD_HINTS = Map.of(
            "reasoningEffort", new SelectHint(
                    List.of("low", "medium", "high"),
                    "low / medium / high - 启用模型推理（thinking）模式"
            )
    );

    // 私有构造函数，防止实例化
    private OnboardWizard() {
    }

    /**
     * 运行 onboard 向导的主入口方法
     * @param initialConfig 初始配置对象，如果为 null 则加载默认配置
     * @return OnboardResult 包含最终配置和是否保存的标志
     */
    public static OnboardResult runOnboard(Config initialConfig) {
        // 创建 Scanner 对象用于读取用户输入
        Scanner scanner = new Scanner(System.in);

        Config baseConfig;
        // 如果提供了初始配置，则深拷贝一份作为基础配置
        if (initialConfig != null) {
            baseConfig = deepCopyConfig(initialConfig);
        } else {
            // 否则从 ConfigLoader 加载默认配置
            baseConfig = ConfigLoader.loadOrDefault();
        }

        // 深拷贝基础配置作为原始配置，用于比较是否有未保存的更改
        Config originalConfig = deepCopyConfig(baseConfig);
        // 深拷贝基础配置作为当前工作配置
        Config config = deepCopyConfig(baseConfig);

        // 主循环，直到用户选择保存或退出
        while (true) {
            // 清空控制台屏幕
            clearConsole();
            // 显示主菜单头部
            showMainMenuHeader();

            // 打印主菜单选项
            System.out.println("你想配置哪一项？");
            System.out.println("1. LLM Provider（模型提供商）");
            System.out.println("2. Chat Channel（聊天渠道）");
            System.out.println("3. Agent Settings（代理设置）");
            System.out.println("4. Gateway（网关）");
            System.out.println("5. Tools（工具）");
            System.out.println("6. 查看配置汇总");
            System.out.println("7. 保存并退出");
            System.out.println("8. 不保存直接退出");
            System.out.print("> ");

            // 安全地读取一行用户输入
            String answer = safeReadLine(scanner);
            // 如果读取失败（例如 EOF），提示用户处理未保存的更改
            if (answer == null) {
                String action = promptMainMenuExit(scanner, hasUnsavedChanges(originalConfig, config));
                if ("save".equals(action)) {
                    return new OnboardResult(config, true);
                }
                if ("discard".equals(action)) {
                    return new OnboardResult(originalConfig, false);
                }
                continue;
            }

            // 根据用户输入执行相应操作
            switch (answer.trim()) {
                case "1" -> configureProviders(scanner, config); // 配置 LLM 提供商
                case "2" -> configureChannels(scanner, config); // 配置聊天渠道
                case "3" -> configureGeneralSettings(scanner, config.getAgents(), "代理设置"); // 配置代理设置
                case "4" -> configureGeneralSettings(scanner, config.getGateway(), "网关"); // 配置网关
                case "5" -> configureGeneralSettings(scanner, config.getTools(), "工具"); // 配置工具
                case "6" -> showSummary(config); // 显示配置汇总
                case "7" -> {
                    return new OnboardResult(config, true); // 保存并退出
                }
                case "8" -> {
                    return new OnboardResult(originalConfig, false); // 不保存直接退出
                }
                default -> System.out.println("未知选项"); // 处理无效输入
            }
        }
    }

    // =========================================================
    // Main sections
    // =========================================================

    /**
     * 配置 LLM 提供商
     */
    private static void configureProviders(Scanner scanner, Config config) {
        showSectionHeader("LLM Provider（模型提供商）", "配置 provider 相关参数");
        configureObject(scanner, config.getProviders(), Set.of());
    }

    /**
     * 配置聊天渠道
     */
    private static void configureChannels(Scanner scanner, Config config) {
        showSectionHeader("Chat Channel（聊天渠道）", "配置渠道相关参数");
        configureObject(scanner, config.getChannels(), Set.of());
    }

    /**
     * 配置通用设置
     */
    private static void configureGeneralSettings(Scanner scanner, Object target, String title) {
        showSectionHeader(title, "配置本节字段");
        configureObject(scanner, target, Set.of());
    }

    // =========================================================
    // Generic object editor
    // =========================================================

    /**
     * 通用对象编辑器，允许用户编辑对象的字段
     */
    private static void configureObject(Scanner scanner, Object model, Set<String> skipFields) {
        // 深拷贝模型对象，以便在用户确认前进行临时修改
        Object working = deepCopyObject(model);

        while (true) {
            // 显示当前对象的配置面板
            showObjectPanel(working, skipFields);

            // 获取可编辑的字段列表
            List<Field> editableFields = getEditableFields(working.getClass(), skipFields);
            // 打印字段选项
            for (int i = 0; i < editableFields.size(); i++) {
                System.out.println((i + 1) + ". " + getFieldDisplayName(editableFields.get(i)));
            }
            System.out.println("D. 完成");
            System.out.println("B. 返回（放弃本节修改）");
            System.out.print("> ");

            // 读取用户输入
            String input = safeReadLine(scanner);
            if (input == null) {
                return;
            }

            // 如果用户选择完成，则将临时修改复制到原对象
            if ("D".equalsIgnoreCase(input)) {
                copyObjectState(working, model);
                return;
            }
            // 如果用户选择返回，则放弃本节修改
            if ("B".equalsIgnoreCase(input)) {
                return;
            }

            int index;
            try {
                // 尝试将输入解析为字段索引
                index = Integer.parseInt(input) - 1;
            } catch (Exception e) {
                continue;
            }

            // 检查索引是否有效
            if (index < 0 || index >= editableFields.size()) {
                continue;
            }

            // 获取选中的字段并编辑
            Field field = editableFields.get(index);
            editField(scanner, working, field);
        }
    }

    /**
     * 编辑单个字段
     */
    private static void editField(Scanner scanner, Object workingModel, Field field) {
        try {
            // 设置字段可访问
            field.setAccessible(true);
            // 获取当前字段值
            Object currentValue = field.get(workingModel);
            String fieldName = field.getName();
            String displayName = getFieldDisplayName(field);

            // 特殊处理 model 字段
            if ("model".equals(fieldName)) {
                handleModelField(scanner, workingModel, field, displayName, currentValue);
                return;
            }

            // 特殊处理 contextWindowTokens 字段
            if ("contextWindowTokens".equals(fieldName)) {
                handleContextWindowField(scanner, workingModel, field, displayName, currentValue);
                return;
            }

            Class<?> type = field.getType();

            // 处理布尔类型字段
            if (type == boolean.class || type == Boolean.class) {
                Boolean newValue = inputBool(scanner, displayName, currentValue instanceof Boolean b ? b : false);
                if (newValue != null) {
                    field.set(workingModel, newValue);
                }
                return;
            }

            // 处理有预定义选项的字段
            SelectHint hint = SELECT_FIELD_HINTS.get(fieldName);
            if (hint != null) {
                String selected = inputSelect(scanner, displayName, hint.choices(), currentValue != null ? currentValue.toString() : null, hint.hintText());
                if (selected != null) {
                    field.set(workingModel, selected);
                }
                return;
            }

            // 处理简单类型字段（String, Number, Boolean）
            if (isSimpleType(type)) {
                Object value = inputText(scanner, displayName, currentValue, type);
                if (value != null) {
                    field.set(workingModel, value);
                }
                return;
            }

            // 处理 List 类型字段
            if (List.class.isAssignableFrom(type)) {
                Object value = inputList(scanner, displayName, currentValue);
                if (value != null) {
                    field.set(workingModel, value);
                }
                return;
            }

            // 处理 Map 类型字段
            if (Map.class.isAssignableFrom(type)) {
                Object value = inputJsonMap(scanner, displayName, currentValue);
                if (value != null) {
                    field.set(workingModel, value);
                }
                return;
            }

            // 递归编辑嵌套对象
            if (currentValue != null) {
                configureObject(scanner, currentValue, Set.of());
            }

        } catch (Exception e) {
            System.out.println("字段编辑失败：" + e.getMessage());
        }
    }

    // =========================================================
    // Specialized field handlers
    // =========================================================

    /**
     * 处理 model 字段的编辑，支持自动补全和上下文窗口自动填充
     */
    private static void handleModelField(
            Scanner scanner,
            Object workingModel,
            Field field,
            String fieldDisplay,
            Object currentValue
    ) throws IllegalAccessException {
        String provider = getCurrentProvider(workingModel);
        String newValue = inputModelWithAutocomplete(scanner, fieldDisplay, currentValue, provider);
        if (newValue != null && !Objects.equals(newValue, currentValue)) {
            field.set(workingModel, newValue);
            tryAutoFillContextWindow(workingModel, newValue);
        }
    }

    /**
     * 处理 contextWindowTokens 字段的编辑，支持推荐值
     */
    private static void handleContextWindowField(
            Scanner scanner,
            Object workingModel,
            Field field,
            String fieldDisplay,
            Object currentValue
    ) throws IllegalAccessException {
        Integer newValue = inputContextWindowWithRecommendation(scanner, fieldDisplay, currentValue, workingModel);
        if (newValue != null) {
            field.set(workingModel, newValue);
        }
    }

    /**
     * 获取当前模型的 provider
     */
    private static String getCurrentProvider(Object model) {
        try {
            Field f = model.getClass().getDeclaredField("provider");
            f.setAccessible(true);
            Object value = f.get(model);
            return value != null ? value.toString() : "auto";
        } catch (Exception e) {
            return "auto";
        }
    }

    /**
     * 带自动补全的 model 输入
     */
    private static String inputModelWithAutocomplete(Scanner scanner, String displayName, Object current, String provider) {
        String currentText = current != null ? current.toString() : "";
        System.out.println(displayName + "（当前值：" + (currentText.isBlank() ? "（未设置）" : currentText) + "）");
        List<String> suggestions = CliModelHelpers.getModelSuggestions(currentText, provider, 10);
        if (!suggestions.isEmpty()) {
            System.out.println("建议值：" + String.join(", ", suggestions));
        }
        System.out.print("新值：");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    /**
     * 带推荐值的 contextWindowTokens 输入
     */
    private static Integer inputContextWindowWithRecommendation(
            Scanner scanner,
            String displayName,
            Object current,
            Object modelObj
    ) {
        System.out.println(displayName);
        System.out.println("1. 输入新值");
        if (current != null) {
            System.out.println("2. 保持现有值");
        }
        System.out.println("3. 获取推荐值");
        System.out.print("> ");

        String choice = safeReadLine(scanner);
        if (choice == null) return null;

        if ("2".equals(choice) && current != null) {
            return null;
        }

        if ("3".equals(choice)) {
            try {
                Field modelField = modelObj.getClass().getDeclaredField("model");
                modelField.setAccessible(true);
                Object modelName = modelField.get(modelObj);

                if (modelName == null || modelName.toString().isBlank()) {
                    System.out.println("请先配置 model");
                    return null;
                }

                Integer recommended = CliModelHelpers.getModelContextLimit(
                        modelName.toString(),
                        getCurrentProvider(modelObj)
                );

                if (recommended != null) {
                    System.out.println("推荐值：" + CliModelHelpers.formatTokenCount(recommended) + " tokens");
                    return recommended;
                } else {
                    System.out.println("暂无推荐值，请手动输入");
                }
            } catch (Exception e) {
                System.out.println("获取推荐值失败");
            }
        }

        System.out.print(displayName + ": ");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) return null;

        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            System.out.println("数字无效");
            return null;
        }
    }

    /**
     * 尝试自动填充 contextWindowTokens
     */
    private static void tryAutoFillContextWindow(Object workingModel, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return;
        }

        Integer recommended = CliModelHelpers.getModelContextLimit(modelName, getCurrentProvider(workingModel));
        if (recommended == null) {
            return;
        }

        try {
            Field ctxField = workingModel.getClass().getDeclaredField("contextWindowTokens");
            ctxField.setAccessible(true);
            Object current = ctxField.get(workingModel);
            if (current == null || (current instanceof Number n && n.intValue() == 0)) {
                ctxField.set(workingModel, recommended);
            }
        } catch (Exception ignored) {
        }
    }

    // =========================================================
    // Input helpers
    // =========================================================

    /**
     * 布尔值输入
     */
    private static Boolean inputBool(Scanner scanner, String displayName, boolean current) {
        System.out.print(displayName + " [y/n]（当前值：" + current + "）：");
        String line = safeReadLine(scanner);
        if (line == null || line.isBlank()) return null;
        return line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes");
    }

    /**
     * 选择项输入
     */
    private static String inputSelect(Scanner scanner, String displayName, List<String> choices, String current, String hintText) {
        System.out.println(displayName + (hintText != null && !hintText.isBlank() ? " - " + hintText : ""));
        for (int i = 0; i < choices.size(); i++) {
            String mark = Objects.equals(choices.get(i), current) ? "（当前）" : "";
            System.out.println((i + 1) + ". " + choices.get(i) + mark);
        }
        System.out.print("> ");
        String line = safeReadLine(scanner);
        if (line == null || line.isBlank()) return null;

        try {
            int idx = Integer.parseInt(line.trim()) - 1;
            if (idx >= 0 && idx < choices.size()) {
                return choices.get(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 文本输入，支持多种基本类型转换
     */
    private static Object inputText(Scanner scanner, String displayName, Object current, Class<?> type) {
        String currentText = current != null ? current.toString() : "";
        if (!currentText.isBlank() && isSensitiveField(displayName)) {
            currentText = maskValue(currentText);
        }

        System.out.print(displayName + (currentText.isBlank() ? "" : "（当前值：" + currentText + "）") + "：");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            if (type == Integer.class || type == int.class) {
                return Integer.parseInt(value.trim());
            }
            if (type == Long.class || type == long.class) {
                return Long.parseLong(value.trim());
            }
            if (type == Double.class || type == double.class) {
                return Double.parseDouble(value.trim());
            }
            if (type == Float.class || type == float.class) {
                return Float.parseFloat(value.trim());
            }
            return value;
        } catch (Exception e) {
            System.out.println("格式无效");
            return null;
        }
    }

    /**
     * 列表输入，逗号分隔
     */
    private static Object inputList(Scanner scanner, String displayName, Object current) {
        System.out.print(displayName + "（逗号分隔"
                + (current != null ? "，当前值：" + current : "") + "）：");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) {
            return null;
        }

        List<String> list = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    /**
     * JSON Map 输入
     */
    private static Object inputJsonMap(Scanner scanner, String displayName, Object current) {
        System.out.print(displayName + "（JSON"
                + (current != null ? "，当前值：" + current : "") + "）：");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            System.out.println("JSON 无效");
            return null;
        }
    }

    // =========================================================
    // Rendering helpers
    // =========================================================

    /**
     * 显示主菜单头部
     */
    private static void showMainMenuHeader() {
        System.out.println();
        System.out.println("🐈 ricbot");
        System.out.println();
    }

    /**
     * 显示章节头部
     */
    private static void showSectionHeader(String title, String subtitle) {
        System.out.println();
        System.out.println("==== " + title + " ====");
        if (subtitle != null && !subtitle.isBlank()) {
            System.out.println(subtitle);
        }
        System.out.println();
    }

    /**
     * 显示对象配置面板
     */
    private static void showObjectPanel(Object model, Set<String> skipFields) {
        System.out.println("--- 当前配置 ---");
        for (Field field : getEditableFields(model.getClass(), skipFields)) {
            try {
                field.setAccessible(true);
                Object value = field.get(model);
                System.out.println(getFieldDisplayName(field) + ": " + formatValue(value, field.getName()));
            } catch (Exception ignored) {
            }
        }
        System.out.println();
    }

    /**
     * 显示配置汇总
     */
    private static void showSummary(Config config) {
        System.out.println();
        System.out.println("===== 配置汇总 =====");
        System.out.println(config);
        System.out.println("按回车继续…");
        new Scanner(System.in).nextLine();
    }

    // =========================================================
    // Utility helpers
    // =========================================================

    /**
     * 检查是否有未保存的更改
     */
    private static boolean hasUnsavedChanges(Config originalConfig, Config config) {
        return !Objects.equals(String.valueOf(originalConfig), String.valueOf(config));
    }

    /**
     * 提示主菜单退出操作
     */
    private static String promptMainMenuExit(Scanner scanner, boolean hasUnsavedChanges) {
        if (!hasUnsavedChanges) {
            return "discard";
        }

        System.out.println("你有未保存的修改。");
        System.out.println("[S] 保存并退出");
        System.out.println("[X] 不保存退出");
        System.out.println("[R] 返回");
        System.out.print("> ");

        String answer = safeReadLine(scanner);
        if (answer == null) return "resume";

        if ("S".equalsIgnoreCase(answer)) return "save";
        if ("X".equalsIgnoreCase(answer)) return "discard";
        return "resume";
    }

    /**
     * 获取可编辑的字段列表
     */
    private static List<Field> getEditableFields(Class<?> clazz, Set<String> skipFields) {
        List<Field> fields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (skipFields.contains(f.getName())) {
                continue;
            }
            fields.add(f);
        }
        return fields;
    }

    /**
     * 获取字段的显示名称
     */
    private static String getFieldDisplayName(Field field) {
        String name = field.getName();
        name = name.replaceAll("([a-z])([A-Z])", "$1 $2");

        if (name.endsWith(" S")) name = name.substring(0, name.length() - 2) + "（秒）";
        if (name.endsWith(" Ms")) name = name.substring(0, name.length() - 3) + "（毫秒）";
        if (name.endsWith(" Url")) name = name.substring(0, name.length() - 4) + " URL";
        if (name.endsWith(" Path")) name = name.substring(0, name.length() - 5) + " 路径";
        if (name.endsWith(" Id")) name = name.substring(0, name.length() - 3) + " ID";
        if (name.endsWith(" Key")) name = name.substring(0, name.length() - 4) + " Key";
        if (name.endsWith(" Token")) name = name.substring(0, name.length() - 6) + " Token";

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * 判断是否为简单类型
     */
    private static boolean isSimpleType(Class<?> type) {
        return type == String.class
                || type == Integer.class || type == int.class
                || type == Long.class || type == long.class
                || type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == Boolean.class || type == boolean.class;
    }

    /**
     * 判断字段是否敏感
     */
    private static boolean isSensitiveField(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 掩码敏感值
     */
    private static String maskValue(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    /**
     * 格式化字段值用于显示
     */
    private static String formatValue(Object value, String fieldName) {
        if (value == null) {
            return "（未设置）";
        }

        if (value instanceof String s && s.isBlank()) {
            return "（未设置）";
        }
        if (value instanceof Collection<?> c && c.isEmpty()) {
            return "（未设置）";
        }
        if (value instanceof Map<?, ?> m && m.isEmpty()) {
            return "（未设置）";
        }

        if (isSensitiveField(fieldName) && value instanceof String s) {
            return maskValue(s);
        }

        if (value instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }

        if (value instanceof Map<?, ?>) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }

        return String.valueOf(value);
    }

    /**
     * 安全地读取一行输入
     */
    private static String safeReadLine(Scanner scanner) {
        try {
            return scanner.nextLine();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清空控制台
     */
    private static void clearConsole() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    // =========================================================
    // Copy helpers
    // =========================================================

    /**
     * 深拷贝 Config 对象
     */
    private static Config deepCopyConfig(Config config) {
        return (Config) deepCopyObject(config);
    }

    /**
     * 深拷贝任意对象
     */
    private static Object deepCopyObject(Object obj) {
        if (obj == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            byte[] bytes = mapper.writeValueAsBytes(obj);
            return mapper.readValue(bytes, obj.getClass());
        } catch (Exception e) {
            return obj;
        }
    }

    /**
     * 将 from 对象的状态复制到 to 对象
     */
    private static void copyObjectState(Object from, Object to) {
        if (from == null || to == null) return;
        if (!from.getClass().equals(to.getClass())) return;

        for (Field field : from.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================
    // Inner DTOs
    // =========================================================

    /**
     * Onboard 结果记录
     */
    public record OnboardResult(Config config, boolean shouldSave) {
    }

    /**
     * 选择提示记录
     */
    private record SelectHint(List<String> choices, String hintText) {
    }
}
