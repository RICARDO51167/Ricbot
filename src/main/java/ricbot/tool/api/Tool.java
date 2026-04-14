package ricbot.tool.api;

import java.util.*;

/**
 * 所有工具的抽象基类
 *
 * 主要目标：
 * 1. 提供统一 schema 导出
 * 2. 提供统一参数校验入口
 * 3. 让 ToolRegistry 能按名称执行工具
 *
 * 这相当于 Python 里 Tool 基类的 Java 版。
 */
public abstract class Tool {

    /**
     * 工具名，必须唯一
     */
    public abstract String getName();

    /**
     * 工具描述
     */
    public abstract String getDescription();

    /**
     * 工具参数定义
     *
     * 子类按需覆盖；默认无参。
     */
    public List<ToolParam> getParams() {
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = toSchema();
        Object fnObj = schema.get("function");
        if (fnObj instanceof Map<?, ?> fn) {
            Object paramsObj = fn.get("parameters");
            if (paramsObj instanceof Map<?, ?> params) {
                return new LinkedHashMap<>((Map<String, Object>) params);
            }
        }
        return Map.of("type", "object", "properties", Map.of());
    }

    /**
     * 是否只读工具
     */
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 是否独占执行
     *
     * 例如 exec 这类工具通常更适合串行执行。
     */
    public boolean isExclusive() {
        return false;
    }

    /**
     * 将参数做基础类型转换。
     *
     * 默认原样返回。
     * 你后续如果需要更复杂的 cast，可以在子类重写。
     */
    public Map<String, Object> castParams(Map<String, Object> params) {
        return params != null ? params : new LinkedHashMap<>();
    }

    /**
     * 参数校验。
     *
     * 返回错误列表；为空表示通过。
     */
    public List<String> validateParams(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> actual = params != null ? params : Collections.emptyMap();

        for (ToolParam param : getParams()) {
            if (param.isRequired() && !actual.containsKey(param.getName())) {
                errors.add("missing required parameter '" + param.getName() + "'");
                continue;
            }

            if (!actual.containsKey(param.getName())) {
                continue;
            }

            Object value = actual.get(param.getName());
            String expectedType = param.getType();

            if (!ToolParam.typeMatches(expectedType, value)) {
                errors.add("parameter '" + param.getName() + "' should be of type " + expectedType);
            }
        }

        return errors;
    }

    /**
     * 导出 OpenAI function-calling 风格 schema
     *
     * 对应 Python: to_schema()
     */
    public Map<String, Object> toSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParam param : getParams()) {
            properties.put(param.getName(), param.toSchema());
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", getName());
        function.put("description", getDescription());
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);

        return schema;
    }

    public Object execute(Map<String, Object> params) throws Exception {
        throw new UnsupportedOperationException("Tool '" + getName() + "' does not implement execute(Map).");
    }
}
