package ricbot.tool.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数定义
 */
public class ToolParam {

    private String name;
    private String type;
    private String description;
    private boolean required;
    private Object defaultValue;
    private Map<String, Object> extraSchema;

    public ToolParam() {
    }

    public ToolParam(String name, String type, String description, boolean required) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
    }

    public static ToolParam of(String name, String type, String description, boolean required) {
        return new ToolParam(name, type, description, required);
    }

    public String getName() {
        return name;
    }

    public ToolParam setName(String name) {
        this.name = name;
        return this;
    }

    public String getType() {
        return type;
    }

    public ToolParam setType(String type) {
        this.type = type;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public ToolParam setDescription(String description) {
        this.description = description;
        return this;
    }

    public boolean isRequired() {
        return required;
    }

    public ToolParam setRequired(boolean required) {
        this.required = required;
        return this;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public ToolParam setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public Map<String, Object> getExtraSchema() {
        return extraSchema;
    }

    public ToolParam setExtraSchema(Map<String, Object> extraSchema) {
        this.extraSchema = extraSchema;
        return this;
    }

    /**
     * 转成 JSON Schema 字段定义
     */
    public Map<String, Object> toSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type != null ? type : "string");
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        if (extraSchema != null && !extraSchema.isEmpty()) {
            schema.putAll(extraSchema);
        }
        return schema;
    }

    /**
     * 基础类型检查
     */
    public static boolean typeMatches(String expectedType, Object value) {
        if (value == null) {
            return true;
        }
        if (expectedType == null || expectedType.isBlank()) {
            return true;
        }

        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer
                    || value instanceof Long
                    || value instanceof Short
                    || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof java.util.List<?>;
            case "object" -> value instanceof java.util.Map<?, ?>;
            default -> true;
        };
    }
}