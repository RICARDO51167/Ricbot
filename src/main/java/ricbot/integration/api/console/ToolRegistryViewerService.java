package ricbot.integration.api.console;

import ricbot.infra.config.Config;
import ricbot.integration.mcp.MCPAdapters;
import ricbot.integration.mcp.MCPLoader;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.api.ToolRegistry;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ToolRegistryViewerService {
    private final ToolRegistry registry;
    private final MCPLoader mcpLoader;
    private final Config config;

    public ToolRegistryViewerService(ToolRegistry registry, MCPLoader mcpLoader, Config config) {
        this.registry = registry;
        this.mcpLoader = mcpLoader;
        this.config = config;
    }

    public Map<String, Object> tools() {
        List<Map<String, Object>> items = new ArrayList<>();
        if (registry != null) {
            for (String name : registry.toolNames()) {
                Tool tool = registry.get(name);
                items.add(toolRow(name, tool));
            }
        }
        long mcpCount = items.stream().filter(row -> "MCP".equals(row.get("source"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("builtinCount", items.stream().filter(row -> "BUILTIN".equals(row.get("source"))).count());
        out.put("mcpCount", mcpCount);
        out.put("generatedCount", items.stream().filter(row -> "GENERATED".equals(row.get("source"))).count());
        out.put("items", items);
        return out;
    }

    public Map<String, Object> mcp() {
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(
                config != null && config.getTools() != null ? config.getTools().getMcpServers() : Map.of()
        );
        Map<String, String> statuses = mcpLoader != null ? mcpLoader.getServerStatuses() : Map.of();
        List<String> toolNames = registry != null ? registry.toolNames() : List.of();

        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        names.addAll(parsed.keySet());
        names.addAll(statuses.keySet());
        for (String toolName : toolNames) {
            String serverName = mcpServerFromToolName(toolName, parsed.keySet());
            if (!serverName.isBlank()) {
                names.add(serverName);
            }
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        for (String name : names) {
            Config.MCPServerConfig cfg = parsed.get(name);
            List<String> loadedTools = loadedTools(name, toolNames);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("transportType", cfg != null ? transportType(cfg) : "unknown");
            row.put("status", normalizeStatus(statuses.get(name), cfg));
            row.put("enabledTools", cfg != null ? cfg.getEnabledTools() : List.of());
            row.put("loadedToolCount", loadedTools.size());
            row.put("loadedTools", loadedTools);
            row.put("lastError", "");
            if (cfg != null) {
                row.put("config", sanitizedMcpConfig(cfg));
            }
            servers.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configuredCount", parsed.size());
        out.put("connectedCount", statuses.values().stream().filter("connected"::equalsIgnoreCase).count());
        out.put("mcpToolCount", toolNames.stream().filter(name -> name.startsWith("mcp_")).count());
        out.put("servers", servers);
        return out;
    }

    private Map<String, Object> toolRow(String name, Tool tool) {
        ToolRegistry.ToolPolicy policy = registry != null ? registry.policyFor(name) : new ToolRegistry.ToolPolicy(name, false, false, true, "unknown");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("source", toolSource(name));
        row.put("description", tool != null ? clean(tool.getDescription()) : "");
        row.put("enabled", tool != null);
        row.put("readOnly", policy.readOnly());
        row.put("exclusive", policy.exclusive());
        row.put("concurrentSafe", policy.concurrentSafe());
        row.put("risk", policy.risk());
        row.put("parameters", parametersSummary(tool));
        return row;
    }

    private Map<String, Object> parametersSummary(Tool tool) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (tool == null) {
            out.put("count", 0);
            out.put("required", List.of());
            out.put("items", List.of());
            return out;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (ToolParam param : tool.getParams()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", param.getName());
            item.put("type", param.getType());
            item.put("required", param.isRequired());
            items.add(item);
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }
        out.put("count", items.size());
        out.put("required", required);
        out.put("items", items);
        return out;
    }

    private String toolSource(String name) {
        if (name == null || name.isBlank()) {
            return "UNKNOWN";
        }
        if (name.startsWith("mcp_")) {
            return "MCP";
        }
        if (name.startsWith("generated_") || name.startsWith("skill_generated_")) {
            return "GENERATED";
        }
        return "BUILTIN";
    }

    private List<String> loadedTools(String serverName, List<String> toolNames) {
        String prefix = "mcp_" + serverName + "_";
        return toolNames.stream()
                .filter(name -> name.startsWith(prefix))
                .sorted()
                .toList();
    }

    private String mcpServerFromToolName(String toolName, java.util.Set<String> configuredNames) {
        if (toolName == null || !toolName.startsWith("mcp_")) {
            return "";
        }
        for (String configuredName : configuredNames) {
            if (toolName.startsWith("mcp_" + configuredName + "_")) {
                return configuredName;
            }
        }
        String rest = toolName.substring("mcp_".length());
        int idx = rest.indexOf('_');
        return idx > 0 ? rest.substring(0, idx) : rest;
    }

    private String normalizeStatus(String raw, Config.MCPServerConfig cfg) {
        if ("connected".equalsIgnoreCase(raw)) {
            return "CONNECTED";
        }
        if ("disconnected".equalsIgnoreCase(raw)) {
            return cfg != null ? "FAILED" : "UNKNOWN";
        }
        return cfg != null ? "UNKNOWN" : "UNKNOWN";
    }

    private String transportType(Config.MCPServerConfig cfg) {
        String type = cfg.getType();
        if (type != null && !type.isBlank()) {
            return type;
        }
        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            return "stdio";
        }
        if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            return "streamableHttp";
        }
        return "unknown";
    }

    private Map<String, Object> sanitizedMcpConfig(Config.MCPServerConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", transportType(cfg));
        out.put("url", sanitizeUrl(cfg.getUrl()));
        out.put("command", isSensitiveText(cfg.getCommand()) ? "[REDACTED]" : clean(cfg.getCommand()));
        out.put("args", cfg.getArgs() != null ? cfg.getArgs().stream().map(this::sanitizeText).toList() : List.of());
        out.put("env", sanitizeEnv(cfg.getEnv()));
        out.put("toolTimeout", cfg.getToolTimeout());
        return out;
    }

    private Map<String, Object> sanitizeEnv(Map<String, String> env) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (env == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey() : "";
            out.put(key, isSensitiveKey(key) || isSensitiveText(entry.getValue()) ? "[REDACTED]" : "[SET]");
        }
        return out;
    }

    private String sanitizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(raw);
            if (uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
                return raw;
            }
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), "[REDACTED]", uri.getFragment()).toString();
        } catch (Exception e) {
            return sanitizeText(raw);
        }
    }

    private String sanitizeText(String value) {
        if (value == null) {
            return "";
        }
        return isSensitiveText(value) ? "[REDACTED]" : value;
    }

    private boolean isSensitiveText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("authorization")
                || lower.contains("bearer")
                || lower.contains("cookie")
                || lower.contains("set-cookie");
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key != null ? key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "";
        return normalized.contains("apikey")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.equals("authorization")
                || normalized.contains("bearer")
                || normalized.contains("cookie");
    }

    private String clean(String value) {
        return value != null ? value : "";
    }
}
