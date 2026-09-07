package ricbot.tool.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-level descriptor builder used while declaring built-in JSON Schemas. */
public final class BuiltinParameter {
    private final String name;
    private final String type;
    private final String description;
    private final boolean required;
    private Object defaultValue;
    private final Map<String, Object> constraints = new LinkedHashMap<>();

    private BuiltinParameter(String name, String type, String description, boolean required) {
        this.name = name; this.type = type; this.description = description; this.required = required;
    }
    public static BuiltinParameter of(String name, String type, String description, boolean required) {
        return new BuiltinParameter(name, type, description, required);
    }
    public String name() { return name; }
    public String type() { return type; }
    public boolean required() { return required; }
    public BuiltinParameter defaultValue(Object value) { defaultValue = value; return this; }
    public BuiltinParameter minimum(long value) { constraints.put("minimum", value); return this; }
    public BuiltinParameter maximum(long value) { constraints.put("maximum", value); return this; }
    public BuiltinParameter minLength(int value) { constraints.put("minLength", value); return this; }
    public BuiltinParameter minItems(int value) { constraints.put("minItems", value); return this; }
    public BuiltinParameter maxItems(int value) { constraints.put("maxItems", value); return this; }
    public BuiltinParameter items(Map<String, Object> value) { constraints.put("items", Map.copyOf(value)); return this; }
    public BuiltinParameter enumValues(java.util.Collection<?> values) {
        constraints.put("enum", List.copyOf(values)); return this;
    }
    public Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type != null ? type : "string");
        if (description != null && !description.isBlank()) schema.put("description", description);
        if (defaultValue != null) schema.put("default", defaultValue);
        schema.putAll(constraints);
        return schema;
    }
    public static boolean matches(String expected, Object value) {
        if (value == null || expected == null || expected.isBlank()) return true;
        return switch (expected) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof java.util.List<?>;
            case "object" -> value instanceof java.util.Map<?, ?>;
            default -> true;
        };
    }
}
