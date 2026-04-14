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

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    private static String schemaName(Map<String, Object> schema) {
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object name = ((Map<?, ?>) fnMap).get("name");
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
                    "Error: Tool '" + name + "' not found. Available: " + String.join(", ", toolNames()));
        }

        if (!(rawParams instanceof Map<?, ?>)) {
            return new PrepareResult(
                    tool,
                    rawParams,
                    "Error: Tool '" + name + "' parameters must be a JSON object, got "
                            + (rawParams == null ? "null" : rawParams.getClass().getSimpleName())
                            + ". Use named parameters: tool_name(param1=\"value1\", param2=\"value2\")"
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
        final String HINT = "\n\n[Analyze the error above and try a different approach.]";
        PrepareResult pr = prepareCall(name, rawParams);
        if (pr.error() != null) return pr.error() + HINT;

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) pr.params();
        return execute(name, params);
    }

    public Object execute(String name, java.util.Map<String, Object> params) {
        final String HINT = "\n\n[Analyze the error above and try a different approach.]";
        Tool tool = get(name);
        if (tool == null) {
            return "Error: Tool '" + name + "' not found. Available: " + String.join(", ", toolNames()) + HINT;
        }

        params = tool.castParams(params);
        java.util.List<String> errors = tool.validateParams(params);
        if (!errors.isEmpty()) {
            return "Error: Invalid parameters for tool '" + name + "': " + String.join("; ", errors) + HINT;
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
                result = "Error: Tool '" + name + "' is registered but not dispatchable yet.";
            }

            if (result instanceof String s && s.startsWith("Error")) {
                return s + HINT;
            }
            return result;
        } catch (Exception e) {
            return "Error executing " + name + ": " + e.getMessage() + HINT;
        }
    }

    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public record PrepareResult(Tool tool, Object params, String error) {}
}
