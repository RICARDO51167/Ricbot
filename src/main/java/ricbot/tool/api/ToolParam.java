package ricbot.tool.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数定义
 */
public class ToolParam {

    // 参数名称
    private String name;
    // 参数类型 (如 string, integer, boolean 等)
    private String type;
    // 参数描述
    private String description;
    // 是否必填
    private boolean required;
    // 默认值
    private Object defaultValue;
    // 额外的 Schema 属性
    private Map<String, Object> extraSchema;

    /**
     * 无参构造函数
     */
    public ToolParam() {
    }

    /**
     * 全参构造函数 (不含 defaultValue 和 extraSchema)
     *
     * @param name        参数名称
     * @param type        参数类型
     * @param description 参数描述
     * @param required    是否必填
     */
    public ToolParam(String name, String type, String description, boolean required) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
    }

    /**
     * 静态工厂方法，用于创建 ToolParam 实例
     *
     * @param name        参数名称
     * @param type        参数类型
     * @param description 参数描述
     * @param required    是否必填
     * @return ToolParam 实例
     */
    public static ToolParam of(String name, String type, String description, boolean required) {
        return new ToolParam(name, type, description, required);
    }

    /**
     * 获取参数名称
     *
     * @return 参数名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置参数名称
     *
     * @param name 参数名称
     * @return 当前对象实例，支持链式调用
     */
    public ToolParam setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * 获取参数类型
     *
     * @return 参数类型
     */
    public String getType() {
        return type;
    }

    /**
     * 设置参数类型
     *
     * @param type 参数类型
     * @return 当前对象实例，支持链式调用
     */
    public ToolParam setType(String type) {
        this.type = type;
        return this;
    }

    /**
     * 获取参数描述
     *
     * @return 参数描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置参数描述
     *
     * @param description 参数描述
     * @return 当前对象实例，支持链式调用
     */
    public ToolParam setDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * 判断参数是否必填
     *
     * @return 如果必填返回 true，否则返回 false
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * 设置参数是否必填
     *
     * @param required 是否必填
     * @return 当前对象实例，支持链式调用
     */
    public ToolParam setRequired(boolean required) {
        this.required = required;
        return this;
    }

    /**
     * 设置默认值
     *
     * @param defaultValue 默认值
     * @return 当前对象实例，支持链式调用
     */
    public ToolParam setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     * 获取额外的 Schema 属性
     *
     * @return 额外的 Schema 属性 Map
     */
    public Map<String, Object> getExtraSchema() {
        return extraSchema;
    }

    /**
     * 设置额外的 Schema 属性
     *
     * @param extraSchema 额外的 Schema 属性 Map
     * @return 当前对象实例，支持链式调用
     */
    public ToolParam setExtraSchema(Map<String, Object> extraSchema) {
        this.extraSchema = extraSchema;
        return this;
    }

    /**
     * 将当前参数定义转换为 JSON Schema 字段定义
     *
     * @return 包含 JSON Schema 信息的 Map
     */
    public Map<String, Object> toSchema() {
        // 使用 LinkedHashMap 保持插入顺序
        Map<String, Object> schema = new LinkedHashMap<>();
        // 设置类型，如果未指定则默认为 string
        schema.put("type", type != null ? type : "string");
        // 如果描述不为空且非空白，则添加描述
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        // 如果存在默认值，则添加默认值
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        // 如果存在额外的 Schema 属性，则合并到结果中
        if (extraSchema != null && !extraSchema.isEmpty()) {
            schema.putAll(extraSchema);
        }
        return schema;
    }

    /**
     * 检查给定值是否符合预期的 JSON Schema 类型
     *
     * @param expectedType 预期的类型字符串 (如 "string", "integer" 等)
     * @param value        待检查的值
     * @return 如果值符合预期类型或值为 null/类型为 null，则返回 true
     */
    public static boolean typeMatches(String expectedType, Object value) {
        // null 值视为匹配任何类型
        if (value == null) {
            return true;
        }
        // 如果预期类型为 null 或空白，视为匹配
        if (expectedType == null || expectedType.isBlank()) {
            return true;
        }

        // 根据预期类型进行匹配检查
        return switch (expectedType) {
            case "string" -> value instanceof String; // 字符串类型
            case "integer" -> value instanceof Integer // 整数类型，包括 Long, Short, Byte
                    || value instanceof Long
                    || value instanceof Short
                    || value instanceof Byte;
            case "number" -> value instanceof Number; // 数字类型，包括 Integer, Double, Float 等
            case "boolean" -> value instanceof Boolean; // 布尔类型
            case "array" -> value instanceof java.util.List<?>; // 数组类型，对应 Java List
            case "object" -> value instanceof java.util.Map<?, ?>; // 对象类型，对应 Java Map
            default -> true; // 其他未知类型默认视为匹配
        };
    }
}