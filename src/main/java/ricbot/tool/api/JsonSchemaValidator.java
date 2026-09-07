package ricbot.tool.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small deterministic JSON-Schema subset used by tool descriptors. */
public final class JsonSchemaValidator {
    private JsonSchemaValidator() { }

    public static List<String> validate(Map<String, Object> schema, Object value) {
        List<String> errors = new ArrayList<>();
        validateNode(schema != null ? schema : Map.of(), value, "$", errors);
        return List.copyOf(errors);
    }

    @SuppressWarnings("unchecked")
    private static void validateNode(Map<String, Object> schema, Object value,
                                     String path, List<String> errors) {
        String type = String.valueOf(schema.getOrDefault("type", ""));
        if (!type.isBlank() && !matches(type, value)) {
            errors.add(path + " must be " + type);
            return;
        }
        if (schema.get("enum") instanceof Collection<?> allowed && !allowed.contains(value)) {
            errors.add(path + " must be one of " + allowed);
            return;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> object = (Map<String, Object>) raw;
            Set<String> required = schema.get("required") instanceof Collection<?> values
                    ? values.stream().map(String::valueOf).collect(
                    java.util.stream.Collectors.toCollection(LinkedHashSet::new)) : Set.of();
            for (String name : required) {
                if (!object.containsKey(name)) errors.add(path + "." + name + " is required");
            }
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> propertiesRaw
                    ? (Map<String, Object>) propertiesRaw : Map.of();
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                object.keySet().stream().filter(name -> !properties.containsKey(name))
                        .sorted().forEach(name -> errors.add(path + "." + name + " is not allowed"));
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object childSchema = properties.get(entry.getKey());
                if (childSchema instanceof Map<?, ?> child) {
                    validateNode((Map<String, Object>) child, entry.getValue(),
                            path + "." + entry.getKey(), errors);
                }
            }
        }
        if (value instanceof List<?> list) {
            compareSize(schema, "minItems", list.size(), path, "items", true, errors);
            compareSize(schema, "maxItems", list.size(), path, "items", false, errors);
            if (schema.get("items") instanceof Map<?, ?> child) {
                for (int i = 0; i < list.size(); i++) {
                    validateNode((Map<String, Object>) child, list.get(i), path + "[" + i + "]", errors);
                }
            }
        }
        if (value instanceof String string) {
            compareSize(schema, "minLength", string.length(), path, "characters", true, errors);
            compareSize(schema, "maxLength", string.length(), path, "characters", false, errors);
        }
        if (value instanceof Number number) {
            BigDecimal actual = new BigDecimal(number.toString());
            compareNumber(schema, "minimum", actual, path, true, errors);
            compareNumber(schema, "maximum", actual, path, false, errors);
        }
    }

    private static void compareSize(Map<String, Object> schema, String key, int actual, String path,
                                    String unit, boolean minimum, List<String> errors) {
        if (!(schema.get(key) instanceof Number bound)) return;
        int expected = bound.intValue();
        if ((minimum && actual < expected) || (!minimum && actual > expected)) {
            errors.add(path + " must contain " + (minimum ? "at least " : "at most ")
                    + expected + " " + unit);
        }
    }

    private static void compareNumber(Map<String, Object> schema, String key, BigDecimal actual,
                                      String path, boolean minimum, List<String> errors) {
        if (!(schema.get(key) instanceof Number bound)) return;
        BigDecimal expected = new BigDecimal(bound.toString());
        int comparison = actual.compareTo(expected);
        if ((minimum && comparison < 0) || (!minimum && comparison > 0)) {
            errors.add(path + " must be " + (minimum ? ">= " : "<= ") + expected.toPlainString());
        }
    }

    private static boolean matches(String expected, Object value) {
        if (value == null) return "null".equals(expected);
        return switch (expected) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "null" -> false;
            default -> true;
        };
    }
}
