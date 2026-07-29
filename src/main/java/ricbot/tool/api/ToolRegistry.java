package ricbot.tool.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import ricbot.tool.api.Tool.ToolExecutionContext;

public class ToolRegistry {
    // 存储已注册的工具，键为工具名称，值为工具实例
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolGroup> groups = new ConcurrentHashMap<>();
    private volatile ToolExposure exposure = ToolExposure.all();
    public ToolRegistry() {}

    /**
     * 注册一个工具到注册表中
     * @param tool 要注册的工具实例
     */
    public void register(Tool tool) {
        register(tool, defaultGroup(tool.getName()));
    }

    public void register(Tool tool, ToolGroup group) {
        Objects.requireNonNull(tool, "tool");
        tools.put(tool.getName(), tool);
        groups.put(tool.getName(), group != null ? group : ToolGroup.ADMIN);
    }

    /**
     * 从注册表中注销指定名称的工具
     * @param name 要注销的工具名称
     */
    public void unregister(String name) {
        tools.remove(name);
        groups.remove(name);
    }

    /**
     * 根据名称获取工具实例
     * @param name 工具名称
     * @return 对应的工具实例，如果不存在则返回 null
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    public ToolExposure exposure() { return exposure; }
    public void setExposure(ToolExposure exposure) { this.exposure = exposure != null ? exposure : ToolExposure.all(); }
    public ToolGroup groupFor(String name) { return groups.getOrDefault(name, ToolGroup.ADMIN); }
    public boolean visible(String name) { return exposure.activeGroups().contains(groupFor(name)); }

    public ToolRegistry copy() {
        ToolRegistry copy = new ToolRegistry();
        tools.forEach((name, tool) -> copy.register(tool, groupFor(name)));
        copy.setExposure(exposure);
        return copy;
    }

    public ToolPolicy policyFor(String name) {
        Tool tool = tools.get(name);
        if (tool == null) return new ToolPolicy(name, false, false, true, "missing");
        ToolEffectPolicy effect = tool.effectPolicy();
        boolean readOnly = effect.readOnly();
        boolean exclusive = effect.concurrency() == ToolEffectPolicy.Concurrency.EXCLUSIVE_WORKSPACE;
        return new ToolPolicy(name, readOnly, exclusive, !exclusive && readOnly,
                effect.effect().name().toLowerCase(java.util.Locale.ROOT));
    }

    public boolean canRunConcurrently(Collection<String> names) {
        return names == null || names.stream().map(this::policyFor).allMatch(ToolPolicy::concurrentSafe);
    }

    /**
     * 从工具的模式（Schema）中提取工具名称
     * 支持两种格式：直接包含 "name" 字段，或包含在 "function" 对象中的 "name" 字段
     * @param schema 工具的模式定义
     * @return 工具名称，如果无法提取则返回空字符串
     */
    private static String schemaName(Map<String, Object> schema) {
        // 尝试从 "function" 对象中获取名称
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object name = fnMap.get("name");
            if (name instanceof String s) return s;
        }
        // 尝试直接从根级别获取名称
        Object name = schema.get("name");
        return (name instanceof String s) ? s : "";
    }

    /** 获取所有已注册工具的定义列表。 */
    public List<Map<String, Object>> getDefinitions() {
        // 收集所有工具的原始定义
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String toolName : sortedToolNames()) {
            Tool tool = visible(toolName) ? tools.get(toolName) : null;
            if (tool != null) {
                definitions.add(tool.toSchema());
            }
        }

        definitions.sort(Comparator.comparing(ToolRegistry::schemaName));
        return definitions;
    }

    /**
     * 准备调用指定的工具，包括参数验证和类型转换
     * @param name 工具名称
     * @param rawParams 原始参数对象
     * @return 准备结果，包含工具实例、转换后的参数或错误信息
     */
    public PrepareResult prepareCall(String name, Object rawParams) {
        if (!visible(name)) {
            return new PrepareResult(null, rawParams, "Error: Tool '" + name + "' is not active for this run");
        }
        // 查找工具
        Tool tool = tools.get(name);
        if (tool == null) {
            return new PrepareResult(null, rawParams, toolNotFoundMessage(name));
        }
        if (!tool.effectPolicy().declared()) {
            return new PrepareResult(tool, rawParams,
                    "Error: Tool '" + name + "' has no ToolEffectPolicy and is refused by default");
        }

        // 验证参数是否为 Map 类型
        if (!(rawParams instanceof Map<?, ?>)) {
            return new PrepareResult(
                    tool,
                    rawParams,
                    invalidParameterShapeMessage(name, rawParams)
            );
        }

        Map<String, Object> params = sanitizeExecutionParams(copyObjectMap((Map<?, ?>) rawParams));
        Map<String, Object> castParams = tool.castParams(params);
        List<String> errors = tool.validateParams(castParams);

        // 如果存在校验错误，返回错误信息
        if (!errors.isEmpty()) {
            return new PrepareResult(tool, castParams, invalidParametersMessage(name, errors));
        }

        // 返回成功的准备结果
        return new PrepareResult(tool, castParams, null);
    }

    /**
     * 执行指定名称的工具，接受原始参数对象
     * @param name 工具名称
     * @param rawParams 原始参数对象
     * @return 执行结果或错误信息
     */
    public Object execute(String name, Object rawParams) {
        // 首先准备调用以进行验证
        PrepareResult pr = prepareCall(name, rawParams);
        if (pr.error() != null) return pr.error();

        if (!(pr.params() instanceof Map<?, ?> raw)) {
            return invalidParameterShapeMessage(name, pr.params());
        }
        return execute(name, copyObjectMap(raw));
    }

    /**
     * 执行指定名称的工具，接受已转换的参数 Map
     * @param name 工具名称
     * @param params 已转换的参数 Map
     * @return 执行结果或错误信息
     */
    public Object execute(String name, java.util.Map<String, Object> params) {
        return execute(name, params, ToolExecutionContext.normal());
    }

    /** Checked protocol execution used by the runtime's timeout and lease boundary. */
    public Object executeProtocolChecked(String name, java.util.Map<String, Object> params,
                                         boolean approved) throws Exception {
        PrepareResult prepared = prepareCall(name, params);
        if (prepared.error() != null) throw new IllegalArgumentException(prepared.error());
        @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) prepared.params();
        ToolExecutionContext context = approved
                ? ToolExecutionContext.approvedContext() : ToolExecutionContext.normal();
        return executeTool(prepared.tool(), cast, context);
    }

    public Object execute(String name, java.util.Map<String, Object> params, ToolExecutionContext context) {
        if (!visible(name)) return "Error: Tool '" + name + "' is not active for this run";
        // 再次获取工具实例以防万一
        Tool tool = get(name);
        if (tool == null) {
            return toolNotFoundMessage(name);
        }

        // 参数转换和二次校验
        params = sanitizeExecutionParams(params);
        params = tool.castParams(params);
        java.util.List<String> errors = tool.validateParams(params);
        if (!errors.isEmpty()) {
            return invalidParametersMessage(name, errors);
        }

        try {
            Object result = executeTool(tool, params, context != null ? context : ToolExecutionContext.normal());
            if (result instanceof String s && (s.startsWith("Error") || s.startsWith("错误"))) return s;
            return result;
        } catch (Exception e) {
            // 捕获执行过程中的异常并返回错误信息
            return executionFailedMessage(name, e);
        }
    }

    /**
     * 获取所有已注册工具的名称列表
     * @return 工具名称列表
     */
    public List<String> toolNames() {
        return sortedToolNames();
    }

    public List<String> visibleToolNames() {
        return sortedToolNames().stream().filter(this::visible).toList();
    }

    private List<String> sortedToolNames() {
        List<String> names = new ArrayList<>(tools.keySet());
        names.sort(String::compareTo);
        return names;
    }

    private Object executeTool(Tool tool, Map<String, Object> params, ToolExecutionContext context) throws Exception {
        return tool.execute(params, context);
    }

    private String toolNotFoundMessage(String name) {
        return "Error: Tool '" + name + "' not found. Available tools: " + String.join(", ", toolNames());
    }

    private static String invalidParameterShapeMessage(String name, Object rawParams) {
        return "Error: Tool '" + name + "' parameters must be a JSON object, got "
                + (rawParams == null ? "null" : rawParams.getClass().getSimpleName());
    }

    private static String invalidParametersMessage(String name, List<String> errors) {
        return "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors);
    }

    private static String executionFailedMessage(String name, Exception e) {
        return "Error: Tool '" + name + "' execution failed: " + e.getMessage();
    }

    /**
     * 记录准备调用的结果
     * @param tool 工具实例
     * @param params 转换后的参数
     * @param error 错误信息，如果没有错误则为 null
     */
    public record PrepareResult(Tool tool, Object params, String error) {}

    public record ToolPolicy(String name, boolean readOnly, boolean exclusive, boolean concurrentSafe, String risk) {}

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        return ricbot.infra.common.JsonMapUtils.copyObjectMap(raw);
    }

    private static Map<String, Object> sanitizeExecutionParams(Map<String, Object> raw) {
        Map<String, Object> out = raw != null ? new LinkedHashMap<>(raw) : new LinkedHashMap<>();
        out.keySet().removeIf(key -> key != null && key.startsWith("__"));
        return out;
    }

    private static ToolGroup defaultGroup(String name) {
        if (name == null) return ToolGroup.ADMIN;
        return switch (name) {
            case "read_file", "list_dir", "grep", "glob", "artifact_list", "artifact_read",
                    "artifact_grep", "manage_tool_groups" -> ToolGroup.BASIC;
            case "write_file", "edit_file" -> ToolGroup.CODING;
            case "exec" -> ToolGroup.VERIFICATION;
            default -> ToolGroup.ADMIN;
        };
    }

}
