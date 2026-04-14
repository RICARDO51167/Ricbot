package ricbot.tool.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schema 抽象基类。
 *
 * 作用：
 * 1. 表示一个 JSON Schema 片段
 * 2. 提供统一的参数校验能力
 */
public abstract class Schema {

    /**
     * 从 JSON Schema 的 type 中取出非 null 的主类型。
     * <p>
     * JSON Schema 允许 type 为字符串或字符串数组（用于支持 nullable，如 ["string", "null"]）。
     * 此方法旨在提取实际的数据类型，忽略 "null"。
     *
     * @param type JSON Schema 中的 type 字段值
     * @return 主类型字符串，如果无法解析则返回 null
     */
    public static String resolveJsonSchemaType(Object type) {
        if (type instanceof List<?> list) {
            for (Object item : list) {
                // 跳过 null 类型，寻找第一个非 null 的类型定义
                if (!Objects.equals(item, "null")) {
                    return String.valueOf(item);
                }
            }
            return null;
        }
        return type == null ? null : String.valueOf(type);
    }

    /**
     * 构造嵌套路径。
     * <p>
     * 用于在递归校验过程中生成错误信息的路径标识。
     *
     * @param path 当前父路径
     * @param key  当前字段名或索引
     * @return 拼接后的完整路径，例如 "user.name" 或 "[0]"
     */
    public static String subpath(String path, String key) {
        return (path == null || path.isEmpty()) ? key : path + "." + key;
    }

    /**
     * 通用 JSON Schema 校验逻辑。
     * <p>
     * 根据提供的 Schema 定义校验值的有效性。
     *
     * @param val    待校验的值
     * @param schema JSON Schema 定义 Map
     * @param path   当前校验路径，用于生成清晰的错误提示
     * @return 错误列表，空列表表示校验通过
     */
    @SuppressWarnings("unchecked")
    public static List<String> validateJsonSchemaValue(Object val, Map<String, Object> schema, String path) {
        List<String> errors = new ArrayList<>();

        // 1. 解析类型和可空性
        Object rawType = schema.get("type");
        boolean nullable = false;

        // 检查 type 数组中是否包含 "null"
        if (rawType instanceof List<?> list && list.contains("null")) {
            nullable = true;
        }
        // 检查显式的 nullable 属性
        if (Boolean.TRUE.equals(schema.get("nullable"))) {
            nullable = true;
        }

        String type = resolveJsonSchemaType(rawType);
        // 确定错误信息中的标签，根节点使用 "parameter"，其他使用路径
        String label = (path == null || path.isEmpty()) ? "parameter" : path;

        // 如果值为 null 且允许为空，则直接通过校验
        if (nullable && val == null) {
            return errors;
        }

        // 2. 基本类型检查
        // 注意：JSON 中的整数通常映射为 Java 的 Integer 或 Long，这里主要检查 Integer
        if ("integer".equals(type) && !(val instanceof Integer)) {
            errors.add(label + " should be integer");
            return errors;
        }
        // Number 涵盖 Integer, Double, Float 等
        if ("number".equals(type) && !(val instanceof Number)) {
            errors.add(label + " should be number");
            return errors;
        }
        if ("string".equals(type) && !(val instanceof String)) {
            errors.add(label + " should be string");
            return errors;
        }
        if ("boolean".equals(type) && !(val instanceof Boolean)) {
            errors.add(label + " should be boolean");
            return errors;
        }
        if ("array".equals(type) && !(val instanceof List<?>)) {
            errors.add(label + " should be array");
            return errors;
        }
        if ("object".equals(type) && !(val instanceof Map<?, ?>)) {
            errors.add(label + " should be object");
            return errors;
        }

        // 3. enum 枚举值校验
        if (schema.containsKey("enum")) {
            List<?> enums = (List<?>) schema.get("enum");
            if (!enums.contains(val)) {
                errors.add(label + " must be one of " + enums);
            }
        }

        // 4. Object 类型深层校验
        if ("object".equals(type) && val instanceof Map<?, ?> obj) {
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());

            // 检查必填字段
            for (String key : required) {
                if (!obj.containsKey(key)) {
                    errors.add("missing required " + subpath(path, key));
                }
            }

            // 递归校验每个属性
            for (Map.Entry<?, ?> entry : obj.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // 只校验 schema 中定义了 properties 的字段
                if (props.containsKey(key)) {
                    errors.addAll(validateJsonSchemaValue(
                            entry.getValue(),
                            (Map<String, Object>) props.get(key),
                            subpath(path, key)
                    ));
                }
            }
        }

        // 5. Array 类型深层校验
        if ("array".equals(type) && val instanceof List<?> listVal) {
            // 如果定义了 items schema，则对数组每个元素进行校验
            if (schema.containsKey("items")) {
                Map<String, Object> itemSchema = (Map<String, Object>) schema.get("items");
                for (int i = 0; i < listVal.size(); i++) {
                    // 数组元素的路径格式为 path[index]
                    errors.addAll(validateJsonSchemaValue(
                            listVal.get(i),
                            itemSchema,
                            (path == null || path.isEmpty()) ? "[" + i + "]" : path + "[" + i + "]"
                    ));
                }
            }
        }

        return errors;
    }

    /**
     * 将当前 Schema 对象转换为标准的 JSON Schema 字典结构。
     *
     * @return JSON Schema 定义的 Map 表示
     */
    public abstract Map<String, Object> toJsonSchema();

    /**
     * 校验一个值是否符合当前 Schema 定义。
     *
     * @param value 待校验的值
     * @param path  当前路径上下文
     * @return 错误列表，空列表表示校验通过
     */
    public List<String> validateValue(Object value, String path) {
        return validateJsonSchemaValue(value, toJsonSchema(), path);
    }
}