package ricbot.tool.api;

import lombok.RequiredArgsConstructor;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import ricbot.tool.api.Tool.ToolExecutionContext;

public class ToolRegistry {
    private static final List<LegacyToolExecutor<? extends Tool>> LEGACY_EXECUTORS = List.of(
            new LegacyToolExecutor<>(
                    ReadFileTool.class,
                    (tool, params) -> tool.execute(
                            (String) params.get("path"),
                            (Integer) params.get("offset"),
                            (Integer) params.get("limit")
                    )
            ),
            new LegacyToolExecutor<>(
                    ListDirTool.class,
                    (tool, params) -> tool.execute((String) params.get("path"))
            ),
            new LegacyToolExecutor<>(
                    GlobTool.class,
                    (tool, params) -> tool.execute(
                            (String) params.get("pattern"),
                            (String) params.get("base_dir")
                    )
            ),
            new LegacyToolExecutor<>(
                    GrepTool.class,
                    (tool, params) -> tool.execute(
                            (String) params.get("pattern"),
                            (String) params.get("base_dir"),
                            (String) params.get("file_glob"),
                            (Boolean) params.get("ignore_case"),
                            (Integer) params.get("max_results")
                    )
            ),
            new LegacyToolExecutor<>(
                    WebFetchTool.class,
                    (tool, params) -> tool.execute(
                            (String) params.get("url"),
                            (String) params.get("extract_mode"),
                            (Integer) params.get("max_chars")
                    )
            ),
            new LegacyToolExecutor<>(
                    WebSearchTool.class,
                    (tool, params) -> tool.execute(
                            (String) params.get("query"),
                            (Integer) params.get("count")
                    )
            )
    );

    // 存储已注册的工具，键为工具名称，值为工具实例
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ToolExecutionPolicy executionPolicy;

    public ToolRegistry() {
        this(ToolExecutionPolicy.defaultPolicy());
    }

    public ToolRegistry(ToolExecutionPolicy executionPolicy) {
        this.executionPolicy = executionPolicy != null ? executionPolicy : ToolExecutionPolicy.defaultPolicy();
    }

    /**
     * 注册一个工具到注册表中
     * @param tool 要注册的工具实例
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 从注册表中注销指定名称的工具
     * @param name 要注销的工具名称
     */
    public void unregister(String name) {
        tools.remove(name);
    }

    /**
     * 根据名称获取工具实例
     * @param name 工具名称
     * @return 对应的工具实例，如果不存在则返回 null
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    public ToolPolicy policyFor(String name) {
        return executionPolicy.policyFor(name, tools.get(name));
    }

    public boolean canRunConcurrently(Collection<String> names) {
        return executionPolicy.canRunConcurrently(names, this::policyFor);
    }

    public ToolExecutionPolicy executionPolicy() {
        return executionPolicy;
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

    /**
     * 获取所有已注册工具的定义列表
     * 结果将分为内置工具和 MCP 工具两类，并分别按名称排序后合并返回
     * @return 工具定义列表
     */
    public List<Map<String, Object>> getDefinitions() {
        // 收集所有工具的原始定义
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String toolName : sortedToolNames()) {
            Tool tool = tools.get(toolName);
            if (tool != null) {
                definitions.add(tool.toSchema());
            }
        }

        // 分类存储内置工具和 MCP 工具
        List<Map<String, Object>> builtins = new ArrayList<>();
        List<Map<String, Object>> mcpTools = new ArrayList<>();

        // 根据名称前缀进行分类
        for (Map<String, Object> schema : definitions) {
            String name = schemaName(schema);
            if (name.startsWith("mcp_")) mcpTools.add(schema);
            else builtins.add(schema);
        }

        // 分别对两类工具按名称进行排序
        builtins.sort(Comparator.comparing(ToolRegistry::schemaName));
        mcpTools.sort(Comparator.comparing(ToolRegistry::schemaName));

        // 合并列表：先内置工具，后 MCP 工具
        List<Map<String, Object>> out = new ArrayList<>(builtins);
        out.addAll(mcpTools);
        return out;
    }

    /**
     * 准备调用指定的工具，包括参数验证和类型转换
     * @param name 工具名称
     * @param rawParams 原始参数对象
     * @return 准备结果，包含工具实例、转换后的参数或错误信息
     */
    public PrepareResult prepareCall(String name, Object rawParams) {
        // 查找工具
        Tool tool = tools.get(name);
        if (tool == null) {
            return new PrepareResult(null, rawParams, toolNotFoundMessage(name));
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

    public Object executeApproved(String name, java.util.Map<String, Object> params) {
        return executeApproved(name, params, "");
    }

    public Object executeApproved(String name, java.util.Map<String, Object> params, String approvalId) {
        return execute(name, params, ToolExecutionContext.approved(approvalId));
    }

    public Object executeProtocol(
            String name,
            java.util.Map<String, Object> params,
            String idempotencyKey,
            String approvalId
    ) {
        return execute(name, params, ToolExecutionContext.protocol(idempotencyKey, approvalId));
    }

    public Object compensate(
            String name,
            java.util.Map<String, Object> params,
            Object previousResult,
            String idempotencyKey,
            String approvalId
    ) {
        Tool tool = get(name);
        if (tool == null) return toolNotFoundMessage(name);
        if (!tool.supportsCompensation()) {
            return "Error: tool '" + name + "' does not support compensation";
        }
        ToolExecutionContext context = ToolExecutionContext.protocol(idempotencyKey, approvalId);
        try (ToolExecutionContext.Scope ignored = ToolExecutionContext.activate(context)) {
            return tool.compensate(params, previousResult, context);
        } catch (Exception e) {
            return executionFailedMessage(name + " compensation", e);
        }
    }

    private Object execute(String name, java.util.Map<String, Object> params, ToolExecutionContext context) {
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
            try (ToolExecutionContext.Scope ignored = ToolExecutionContext.activate(context)) {
                Object result = executeTool(tool, params, context);
                // 如果结果是字符串且以错误或错误开头，直接返回
                if (result instanceof String s && (s.startsWith("Error") || s.startsWith("错误"))) return s;
                return result;
            }
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

    private List<String> sortedToolNames() {
        List<String> names = new ArrayList<>(tools.keySet());
        names.sort(String::compareTo);
        return names;
    }

    private Object executeTool(Tool tool, Map<String, Object> params, ToolExecutionContext context) throws Exception {
        LegacyToolExecutor<? extends Tool> legacyExecutor = findLegacyExecutor(tool);
        if (legacyExecutor != null) {
            return legacyExecutor.executeUnchecked(tool, params);
        }
        return tool.execute(params, context);
    }

    private LegacyToolExecutor<? extends Tool> findLegacyExecutor(Tool tool) {
        if (tool instanceof ExecTool) {
            return null;
        }
        for (LegacyToolExecutor<? extends Tool> executor : LEGACY_EXECUTORS) {
            if (executor.supports(tool)) {
                return executor;
            }
        }
        return null;
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

    @RequiredArgsConstructor
    private static final class LegacyToolExecutor<T extends Tool> {
        private final Class<T> toolType;
        private final LegacyToolInvoker<T> invoker;

        private boolean supports(Tool tool) {
            return toolType.isInstance(tool);
        }

        private Object executeUnchecked(Tool tool, Map<String, Object> params) throws Exception {
            return invoker.execute(toolType.cast(tool), params);
        }
    }

    @FunctionalInterface
    private interface LegacyToolInvoker<T extends Tool> {
        Object execute(T tool, Map<String, Object> params) throws Exception;
    }
}
