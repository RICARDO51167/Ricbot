package ricbot.tool.api;

import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import java.util.*;

public class ToolRegistry {

    // 存储已注册的工具，键为工具名称，值为工具实例
    private final Map<String, Tool> tools = new HashMap<>();

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
        for (Tool tool : tools.values()) {
            definitions.add(tool.toSchema());
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
            return new PrepareResult(null, rawParams,
                    "Error: Tool '" + name + "' not found. Available tools: " + String.join(", ", toolNames()));
        }

        // 验证参数是否为 Map 类型
        if (!(rawParams instanceof Map<?, ?>)) {
            return new PrepareResult(
                    tool,
                    rawParams,
                    "Error: Tool '" + name + "' parameters must be a JSON object, got "
                            + (rawParams == null ? "null" : rawParams.getClass().getSimpleName())
            );
        }

        // 类型转换和参数校验
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) rawParams;
        Map<String, Object> castParams = tool.castParams(params);
        List<String> errors = tool.validateParams(castParams);

        // 如果存在校验错误，返回错误信息
        if (!errors.isEmpty()) {
            return new PrepareResult(tool, castParams,
                    "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors));
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

        // 获取转换后的参数并执行具体逻辑
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) pr.params();
        return execute(name, params);
    }

    /**
     * 执行指定名称的工具，接受已转换的参数 Map
     * @param name 工具名称
     * @param params 已转换的参数 Map
     * @return 执行结果或错误信息
     */
    public Object execute(String name, java.util.Map<String, Object> params) {
        // 再次获取工具实例以防万一
        Tool tool = get(name);
        if (tool == null) {
            return "Error: Tool '" + name + "' not found. Available tools: " + String.join(", ", toolNames());
        }

        // 参数转换和二次校验
        params = tool.castParams(params);
        java.util.List<String> errors = tool.validateParams(params);
        if (!errors.isEmpty()) {
            return "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors);
        }

        try {
            Object result;
            // 根据工具类型执行特定的 execute 方法，避免通用反射调用的开销或类型问题
            if (tool instanceof ReadFileTool t) {
                result = t.execute(
                        (String) params.get("path"),
                        (Integer) params.get("offset"),
                        (Integer) params.get("limit")
                );
            } else if (tool instanceof ListDirTool t) {
                result = t.execute((String) params.get("path"));

            } else if (tool instanceof ExecTool t) {
                result = t.execute(
                        (String) params.get("command"),
                        (String) params.get("working_dir"),
                        (Integer) params.get("timeout")
                );
            } else if (tool instanceof GlobTool t) {
                result = t.execute(
                        (String) params.get("pattern"),
                        (String) params.get("base_dir")
                );
            } else if (tool instanceof GrepTool t) {
                result = t.execute(
                        (String) params.get("pattern"),
                        (String) params.get("base_dir"),
                        (String) params.get("file_glob"),
                        (Boolean) params.get("ignore_case"),
                        (Integer) params.get("max_results")
                );
            } else if (tool instanceof WriteFileTool t) {
                result = t.execute(
                        (String) params.get("path"),
                        (String) params.get("content")
                );
            } else if (tool instanceof EditFileTool t) {
                result = t.execute(
                        (String) params.get("path"),
                        (String) params.get("old_text"),
                        (String) params.get("new_text"),
                        (Boolean) params.get("replace_all")
                );
            } else {
                // 默认执行方式
                result = tool.execute(params);
            }

            // 如果结果是字符串且以错误或错误开头，直接返回
            if (result instanceof String s && (s.startsWith("Error") || s.startsWith("错误"))) return s;
            return result;
        } catch (Exception e) {
            // 捕获执行过程中的异常并返回错误信息
            return "Error: Tool '" + name + "' execution failed: " + e.getMessage();
        }
    }

    /**
     * 获取所有已注册工具的名称列表
     * @return 工具名称列表
     */
    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 记录准备调用的结果
     * @param tool 工具实例
     * @param params 转换后的参数
     * @param error 错误信息，如果没有错误则为 null
     */
    public record PrepareResult(Tool tool, Object params, String error) {}
}
