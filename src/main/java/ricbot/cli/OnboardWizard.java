package ricbot.cli;

import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 对应 Python: onboard.py
 *
 * 主要目标：
 * 1. 交互式配置 nanobot
 * 2. 支持主菜单与分区配置
 * 3. 支持字段浏览、输入、保存、放弃
 *
 * 说明：
 * Python 版 heavily 依赖 questionary + rich + pydantic 反射。
 * Java 这里改成 Scanner + 反射 的通用实现，但保留整体职责。
 */
public final class OnboardWizard {

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "api_key", "token", "secret", "password", "credentials"
    );

    private static final Map<String, SelectHint> SELECT_FIELD_HINTS = Map.of(
            "reasoningEffort", new SelectHint(
                    List.of("low", "medium", "high"),
                    "low / medium / high - enables LLM thinking mode"
            )
    );

    private OnboardWizard() {
    }

    public static OnboardResult runOnboard(Config initialConfig) {
        Scanner scanner = new Scanner(System.in);

        Config baseConfig;
        if (initialConfig != null) {
            baseConfig = deepCopyConfig(initialConfig);
        } else {
            baseConfig = ConfigLoader.loadOrDefault();
        }

        Config originalConfig = deepCopyConfig(baseConfig);
        Config config = deepCopyConfig(baseConfig);

        while (true) {
            clearConsole();
            showMainMenuHeader();

            System.out.println("What would you like to configure?");
            System.out.println("1. LLM Provider");
            System.out.println("2. Chat Channel");
            System.out.println("3. Agent Settings");
            System.out.println("4. Gateway");
            System.out.println("5. Tools");
            System.out.println("6. View Configuration Summary");
            System.out.println("7. Save and Exit");
            System.out.println("8. Exit Without Saving");
            System.out.print("> ");

            String answer = safeReadLine(scanner);
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

            switch (answer.trim()) {
                case "1" -> configureProviders(scanner, config);
                case "2" -> configureChannels(scanner, config);
                case "3" -> configureGeneralSettings(scanner, config.getAgents(), "Agent Settings");
                case "4" -> configureGeneralSettings(scanner, config.getGateway(), "Gateway");
                case "5" -> configureGeneralSettings(scanner, config.getTools(), "Tools");
                case "6" -> showSummary(config);
                case "7" -> {
                    return new OnboardResult(config, true);
                }
                case "8" -> {
                    return new OnboardResult(originalConfig, false);
                }
                default -> System.out.println("Unknown option");
            }
        }
    }

    // =========================================================
    // Main sections
    // =========================================================

    private static void configureProviders(Scanner scanner, Config config) {
        showSectionHeader("LLM Provider", "Configure provider settings");
        configureObject(scanner, config.getProviders(), Set.of());
    }

    private static void configureChannels(Scanner scanner, Config config) {
        showSectionHeader("Chat Channel", "Configure channel settings");
        configureObject(scanner, config.getChannels(), Set.of("transcriptionProvider"));
    }

    private static void configureGeneralSettings(Scanner scanner, Object target, String title) {
        showSectionHeader(title, "Configure section fields");
        configureObject(scanner, target, Set.of());
    }

    // =========================================================
    // Generic object editor
    // =========================================================

    private static void configureObject(Scanner scanner, Object model, Set<String> skipFields) {
        Object working = deepCopyObject(model);

        while (true) {
            showObjectPanel(working, skipFields);

            List<Field> editableFields = getEditableFields(working.getClass(), skipFields);
            for (int i = 0; i < editableFields.size(); i++) {
                System.out.println((i + 1) + ". " + getFieldDisplayName(editableFields.get(i)));
            }
            System.out.println("D. Done");
            System.out.println("B. Back (discard section changes)");
            System.out.print("> ");

            String input = safeReadLine(scanner);
            if (input == null) {
                return;
            }

            if ("D".equalsIgnoreCase(input)) {
                copyObjectState(working, model);
                return;
            }
            if ("B".equalsIgnoreCase(input)) {
                return;
            }

            int index;
            try {
                index = Integer.parseInt(input) - 1;
            } catch (Exception e) {
                continue;
            }

            if (index < 0 || index >= editableFields.size()) {
                continue;
            }

            Field field = editableFields.get(index);
            editField(scanner, working, field);
        }
    }

    private static void editField(Scanner scanner, Object workingModel, Field field) {
        try {
            field.setAccessible(true);
            Object currentValue = field.get(workingModel);
            String fieldName = field.getName();
            String displayName = getFieldDisplayName(field);

            if ("model".equals(fieldName)) {
                handleModelField(scanner, workingModel, field, displayName, currentValue);
                return;
            }

            if ("contextWindowTokens".equals(fieldName)) {
                handleContextWindowField(scanner, workingModel, field, displayName, currentValue);
                return;
            }

            Class<?> type = field.getType();

            if (type == boolean.class || type == Boolean.class) {
                Boolean newValue = inputBool(scanner, displayName, currentValue instanceof Boolean b ? b : false);
                if (newValue != null) {
                    field.set(workingModel, newValue);
                }
                return;
            }

            SelectHint hint = SELECT_FIELD_HINTS.get(fieldName);
            if (hint != null) {
                String selected = inputSelect(scanner, displayName, hint.choices(), currentValue != null ? currentValue.toString() : null, hint.hintText());
                if (selected != null) {
                    field.set(workingModel, selected);
                }
                return;
            }

            if (isSimpleType(type)) {
                Object value = inputText(scanner, displayName, currentValue, type);
                if (value != null) {
                    field.set(workingModel, value);
                }
                return;
            }

            if (List.class.isAssignableFrom(type)) {
                Object value = inputList(scanner, displayName, currentValue);
                if (value != null) {
                    field.set(workingModel, value);
                }
                return;
            }

            if (Map.class.isAssignableFrom(type)) {
                Object value = inputJsonMap(scanner, displayName, currentValue);
                if (value != null) {
                    field.set(workingModel, value);
                }
                return;
            }

            // 嵌套对象递归编辑
            if (currentValue != null) {
                configureObject(scanner, currentValue, Set.of());
            }

        } catch (Exception e) {
            System.out.println("Failed to edit field: " + e.getMessage());
        }
    }

    // =========================================================
    // Specialized field handlers
    // =========================================================

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

    private static String inputModelWithAutocomplete(Scanner scanner, String displayName, Object current, String provider) {
        String currentText = current != null ? current.toString() : "";
        System.out.println(displayName + " (current: " + (currentText.isBlank() ? "[not set]" : currentText) + ")");
        List<String> suggestions = CliModelHelpers.getModelSuggestions(currentText, provider, 10);
        if (!suggestions.isEmpty()) {
            System.out.println("Suggestions: " + String.join(", ", suggestions));
        }
        System.out.print("New value: ");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static Integer inputContextWindowWithRecommendation(
            Scanner scanner,
            String displayName,
            Object current,
            Object modelObj
    ) {
        System.out.println(displayName);
        System.out.println("1. Enter new value");
        if (current != null) {
            System.out.println("2. Keep existing value");
        }
        System.out.println("3. Get recommended value");
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
                    System.out.println("Please configure model first");
                    return null;
                }

                Integer recommended = CliModelHelpers.getModelContextLimit(
                        modelName.toString(),
                        getCurrentProvider(modelObj)
                );

                if (recommended != null) {
                    System.out.println("Recommended: " + CliModelHelpers.formatTokenCount(recommended) + " tokens");
                    return recommended;
                } else {
                    System.out.println("No recommendation available; please enter manually");
                }
            } catch (Exception e) {
                System.out.println("Failed to fetch recommendation");
            }
        }

        System.out.print(displayName + ": ");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) return null;

        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            System.out.println("Invalid number");
            return null;
        }
    }

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

    private static Boolean inputBool(Scanner scanner, String displayName, boolean current) {
        System.out.print(displayName + " [y/n] (current: " + current + "): ");
        String line = safeReadLine(scanner);
        if (line == null || line.isBlank()) return null;
        return line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes");
    }

    private static String inputSelect(Scanner scanner, String displayName, List<String> choices, String current, String hintText) {
        System.out.println(displayName + (hintText != null && !hintText.isBlank() ? " - " + hintText : ""));
        for (int i = 0; i < choices.size(); i++) {
            String mark = Objects.equals(choices.get(i), current) ? " (current)" : "";
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

    private static Object inputText(Scanner scanner, String displayName, Object current, Class<?> type) {
        String currentText = current != null ? current.toString() : "";
        if (!currentText.isBlank() && isSensitiveField(displayName)) {
            currentText = maskValue(currentText);
        }

        System.out.print(displayName + (currentText.isBlank() ? "" : " (current: " + currentText + ")") + ": ");
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
            System.out.println("Invalid format");
            return null;
        }
    }

    private static Object inputList(Scanner scanner, String displayName, Object current) {
        System.out.print(displayName + " (comma-separated"
                + (current != null ? ", current: " + current : "") + "): ");
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

    private static Object inputJsonMap(Scanner scanner, String displayName, Object current) {
        System.out.print(displayName + " (JSON"
                + (current != null ? ", current: " + current : "") + "): ");
        String value = safeReadLine(scanner);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            System.out.println("Invalid JSON");
            return null;
        }
    }

    // =========================================================
    // Rendering helpers
    // =========================================================

    private static void showMainMenuHeader() {
        System.out.println();
        System.out.println("🐈 nanobot");
        System.out.println();
    }

    private static void showSectionHeader(String title, String subtitle) {
        System.out.println();
        System.out.println("==== " + title + " ====");
        if (subtitle != null && !subtitle.isBlank()) {
            System.out.println(subtitle);
        }
        System.out.println();
    }

    private static void showObjectPanel(Object model, Set<String> skipFields) {
        System.out.println("--- Current Configuration ---");
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

    private static void showSummary(Config config) {
        System.out.println();
        System.out.println("===== Configuration Summary =====");
        System.out.println(config);
        System.out.println("Press Enter to continue...");
        new Scanner(System.in).nextLine();
    }

    // =========================================================
    // Utility helpers
    // =========================================================

    private static boolean hasUnsavedChanges(Config originalConfig, Config config) {
        return !Objects.equals(String.valueOf(originalConfig), String.valueOf(config));
    }

    private static String promptMainMenuExit(Scanner scanner, boolean hasUnsavedChanges) {
        if (!hasUnsavedChanges) {
            return "discard";
        }

        System.out.println("You have unsaved changes.");
        System.out.println("[S] Save and Exit");
        System.out.println("[X] Exit Without Saving");
        System.out.println("[R] Return");
        System.out.print("> ");

        String answer = safeReadLine(scanner);
        if (answer == null) return "resume";

        if ("S".equalsIgnoreCase(answer)) return "save";
        if ("X".equalsIgnoreCase(answer)) return "discard";
        return "resume";
    }

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

    private static String getFieldDisplayName(Field field) {
        String name = field.getName();
        name = name.replaceAll("([a-z])([A-Z])", "$1 $2");

        if (name.endsWith(" S")) name = name.substring(0, name.length() - 2) + " (seconds)";
        if (name.endsWith(" Ms")) name = name.substring(0, name.length() - 3) + " (ms)";
        if (name.endsWith(" Url")) name = name.substring(0, name.length() - 4) + " URL";
        if (name.endsWith(" Path")) name = name.substring(0, name.length() - 5) + " Path";
        if (name.endsWith(" Id")) name = name.substring(0, name.length() - 3) + " ID";
        if (name.endsWith(" Key")) name = name.substring(0, name.length() - 4) + " Key";
        if (name.endsWith(" Token")) name = name.substring(0, name.length() - 6) + " Token";

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isSimpleType(Class<?> type) {
        return type == String.class
                || type == Integer.class || type == int.class
                || type == Long.class || type == long.class
                || type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == Boolean.class || type == boolean.class;
    }

    private static boolean isSensitiveField(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String maskValue(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static String formatValue(Object value, String fieldName) {
        if (value == null) {
            return "[not set]";
        }

        if (value instanceof String s && s.isBlank()) {
            return "[not set]";
        }
        if (value instanceof Collection<?> c && c.isEmpty()) {
            return "[not set]";
        }
        if (value instanceof Map<?, ?> m && m.isEmpty()) {
            return "[not set]";
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

    private static String safeReadLine(Scanner scanner) {
        try {
            return scanner.nextLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static void clearConsole() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    // =========================================================
    // Copy helpers
    // =========================================================

    private static Config deepCopyConfig(Config config) {
        return (Config) deepCopyObject(config);
    }

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

    public record OnboardResult(Config config, boolean shouldSave) {
    }

    private record SelectHint(List<String> choices, String hintText) {
    }
}