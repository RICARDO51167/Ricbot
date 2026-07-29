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

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }

    public ToolParam setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public Map<String, Object> toSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type != null ? type : "string");
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        return schema;
    }

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
