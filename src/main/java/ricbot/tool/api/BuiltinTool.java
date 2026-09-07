package ricbot.tool.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal migration base for built-ins; the public runtime contract remains {@link Tool}. */
public abstract class BuiltinTool implements Tool {
    public abstract String getName();
    public abstract String getDescription();
    public abstract ToolEffectPolicy effectPolicy();
    public List<BuiltinParameter> getParams() { return List.of(); }
    public Map<String, Object> castParams(Map<String, Object> params) { return params != null ? params : Map.of(); }
    public List<String> validateParams(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        for (BuiltinParameter parameter : getParams()) {
            if (parameter.required() && !params.containsKey(parameter.name())) errors.add("missing '" + parameter.name() + "'");
            else if (params.containsKey(parameter.name()) && !BuiltinParameter.matches(parameter.type(), params.get(parameter.name()))) {
                errors.add("'" + parameter.name() + "' must be " + parameter.type());
            }
        }
        return errors;
    }
    public abstract Object execute(Map<String, Object> params) throws Exception;
    public Object execute(Map<String, Object> params, ToolExecutionContext context) throws Exception { return execute(params); }
    public ToolRiskDecision assessRisk(Map<String, Object> params) { return ToolRiskDecision.allow(); }

    @Override public ToolDescriptor descriptor() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (BuiltinParameter parameter : getParams()) {
            properties.put(parameter.name(), parameter.schema());
            if (parameter.required()) required.add(parameter.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object"); schema.put("properties", properties); schema.put("additionalProperties", false);
        if (!required.isEmpty()) schema.put("required", required);
        return new ToolDescriptor(ToolDescriptor.SCHEMA_VERSION, "builtin." + getName(), getName(), getDescription(),
                schema, ToolGroup.ADMIN, ToolSource.BUILTIN, effectPolicy(), ExecutionMode.LOCAL,
                resultPolicy(getName()));
    }

    @Override public ToolRiskEvidence assessRisk(ToolInvocation invocation, ToolExecutionContext context) {
        return ToolRiskEvidence.from(assessRisk(invocation.arguments()));
    }
    @Override public List<String> resourceKeys(ToolInvocation invocation, ToolExecutionContext context) {
        ToolEffectPolicy.Concurrency concurrency = effectPolicy().concurrency();
        if (concurrency == ToolEffectPolicy.Concurrency.SHARED) return List.of();
        if (concurrency == ToolEffectPolicy.Concurrency.EXCLUSIVE_WORKSPACE) return List.of("workspace:" + context.workspaceId());
        return List.of("run:" + context.runId() + ":" + getName());
    }
    @Override public ToolResult execute(ToolInvocation invocation, ToolExecutionContext context,
                                              ToolChunkSink chunks) throws Exception {
        Map<String, Object> cast = castParams(new LinkedHashMap<>(invocation.arguments()));
        List<String> errors = validateParams(cast);
        if (!errors.isEmpty()) return new ToolResult.Failure("INVALID_ARGUMENTS", String.join("; ", errors), false, List.of());
        Object value = execute(cast, context);
        if (value instanceof String text && (text.startsWith("Error") || text.startsWith("错误"))) {
            return new ToolResult.Failure("TOOL_FAILED", text, false, List.of());
        }
        return ToolResult.Success.of(value);
    }
    private static ToolResultPolicy resultPolicy(String name) {
        if (name.contains("grep")) return ToolResultPolicy.GREP;
        if (name.contains("read")) return ToolResultPolicy.READ;
        if (name.equals("exec")) return ToolResultPolicy.EXEC_TEST;
        return ToolResultPolicy.GENERIC;
    }
}
