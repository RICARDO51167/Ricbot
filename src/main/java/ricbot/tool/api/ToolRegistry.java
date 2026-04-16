package ricbot.tool.api;

import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import java.util.*;

/**
 * 工具注册表，用于管理工具的注册、注销、获取和执行。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    private static String schemaName(Map<String, Object> schema) {
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object name = fnMap.get("name");
            if (name instanceof String s) return s;
        }
        Object name = schema.get("name");
        return (name instanceof String s) ? s : "";
    }

    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            definitions.add(tool.toSchema());
        }

        List<Map<String, Object>> builtins = new ArrayList<>();
        List<Map<String, Object>> mcpTools = new ArrayList<>();

        for (Map<String, Object> schema : definitions) {
            String name = schemaName(schema);
            if (name.startsWith("mcp_")) mcpTools.add(schema);
            else builtins.add(schema);
        }

        builtins.sort(Comparator.comparing(ToolRegistry::schemaName));
        mcpTools.sort(Comparator.comparing(ToolRegistry::schemaName));

        List<Map<String, Object>> out = new ArrayList<>(builtins);
        out.addAll(mcpTools);
        return out;
    }

    public PrepareResult prepareCall(String name, Object rawParams) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return new PrepareResult(null, rawParams,
                    "Error: Tool '" + name + "' not found. Available tools: " + String.join(", ", toolNames()));
        }

        if (!(rawParams instanceof Map<?, ?>)) {
            return new PrepareResult(
                    tool,
                    rawParams,
                    "Error: Tool '" + name + "' parameters must be a JSON object, got "
                            + (rawParams == null ? "null" : rawParams.getClass().getSimpleName())
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) rawParams;
        Map<String, Object> castParams = tool.castParams(params);
        List<String> errors = tool.validateParams(castParams);

        if (!errors.isEmpty()) {
            return new PrepareResult(tool, castParams,
                    "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors));
        }

        return new PrepareResult(tool, castParams, null);
    }

    public Object execute(String name, Object rawParams) {
        PrepareResult pr = prepareCall(name, rawParams);
        if (pr.error() != null) return pr.error();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) pr.params();
        return execute(name, params);
    }

    public Object execute(String name, java.util.Map<String, Object> params) {
        Tool tool = get(name);
        if (tool == null) {
            return "Error: Tool '" + name + "' not found. Available tools: " + String.join(", ", toolNames());
        }

        params = tool.castParams(params);
        java.util.List<String> errors = tool.validateParams(params);
        if (!errors.isEmpty()) {
            return "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors);
        }

        try {
            Object result;
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
                result = tool.execute(params);
            }

            if (result instanceof String s && (s.startsWith("Error") || s.startsWith("错误"))) return s;
            return result;
        } catch (Exception e) {
            return "Error: Tool '" + name + "' execution failed: " + e.getMessage();
        }
    }

    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }

    public record PrepareResult(Tool tool, Object params, String error) {}
}
