package ricbot.tool.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, strict and serializable description of a model-visible capability. */
public record ToolDescriptor(
        int schemaVersion, String id, String name, String description, Map<String, Object> parameters,
        ToolGroup group, ToolSource source, ToolEffectPolicy effectPolicy,
        ExecutionMode executionMode, ToolResultPolicy resultPolicy
) {
    public static final int SCHEMA_VERSION = 1;
    public ToolDescriptor {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported ToolDescriptor schema: " + schemaVersion);
        id = required(id, "id"); name = required(name, "name"); description = required(description, "description");
        if (!id.matches("[a-z0-9][a-z0-9._-]*")) throw new IllegalArgumentException("invalid descriptor id: " + id);
        if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("invalid tool name: " + name);
        Map<String, Object> strict = new LinkedHashMap<>(parameters != null ? parameters : Map.of());
        strict.putIfAbsent("type", "object");
        strict.putIfAbsent("properties", Map.of());
        strict.putIfAbsent("additionalProperties", false);
        if (!"object".equals(strict.get("type"))) throw new IllegalArgumentException("tool parameters must be an object schema");
        parameters = Map.copyOf(strict);
        group = group != null ? group : ToolGroup.ADMIN;
        source = source != null ? source : ToolSource.BUILTIN;
        effectPolicy = effectPolicy != null ? effectPolicy : ToolEffectPolicy.undeclared();
        executionMode = executionMode != null ? executionMode : ExecutionMode.LOCAL;
        resultPolicy = resultPolicy != null ? resultPolicy : ToolResultPolicy.GENERIC;
    }
    public Map<String, Object> providerSchema() {
        return Map.of("type", "function", "function", Map.of("name", name,
                "description", description, "parameters", parameters));
    }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
