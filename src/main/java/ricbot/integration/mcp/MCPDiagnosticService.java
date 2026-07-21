package ricbot.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.config.Config;
import ricbot.tool.api.ToolRegistry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class MCPDiagnosticService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry;
    private final MCPLoader loader;
    private final Config config;

    public MCPDiagnosticService(ToolRegistry registry, MCPLoader loader, Config config) {
        this.registry = registry;
        this.loader = loader;
        this.config = config;
    }

    public Map<String, Object> diagnostics() {
        Map<String, Config.MCPServerConfig> parsed = parsedConfigs();
        Map<String, String> statuses = loader != null ? loader.getServerStatuses() : Map.of();
        Map<String, MCPAdapters.MCPServerLoadInfo> loadInfo = loader != null ? loader.getLastLoadInfo() : Map.of();
        List<String> registryNames = registry != null ? registry.toolNames() : List.of();

        TreeSet<String> names = new TreeSet<>();
        names.addAll(parsed.keySet());
        names.addAll(statuses.keySet());
        names.addAll(loadInfo.keySet());

        List<Map<String, Object>> servers = new ArrayList<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String name : names) {
            Config.MCPServerConfig cfg = parsed.get(name);
            MCPAdapters.MCPServerLoadInfo info = loadInfo.get(name);
            String status = normalizeStatus(statuses.get(name), info, cfg);
            List<String> registered = registeredToolNames(name, registryNames, info);
            List<String> filtered = filteredToolNames(info);
            List<String> serverWarnings = configWarnings(cfg, info);
            warnings.addAll(serverWarnings.stream().map(w -> name + ": " + w).toList());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("transportType", transportType(cfg, info));
            row.put("enabled", cfg != null);
            row.put("status", status);
            row.put("loadedToolCount", registered.size());
            row.put("registeredToolNames", registered);
            row.put("filteredToolNames", filtered);
            row.put("disabledReason", disabledReason(status, cfg, info, registered));
            row.put("lastError", redactText(info != null ? info.lastError() : ""));
            row.put("configWarnings", serverWarnings);
            if (cfg != null) {
                row.put("config", sanitizedConfig(cfg));
            }
            servers.add(row);

            tools.addAll(toolExplanations(name, cfg, info, registryNames));
        }

        List<Map<String, Object>> schemaSummary = schemaSummary();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configuredCount", parsed.size());
        out.put("connectedCount", statuses.values().stream().filter("connected"::equalsIgnoreCase).count());
        out.put("mcpToolCount", registryNames.stream().filter(name -> name.startsWith("mcp_")).count());
        out.put("schemaHash", schemaHash(schemaSummary));
        out.put("warnings", warnings);
        out.put("servers", servers);
        out.put("tools", tools);
        out.put("schemaSummary", schemaSummary);
        return out;
    }

    private Map<String, Config.MCPServerConfig> parsedConfigs() {
        Map<String, Object> raw;
        if (loader != null) {
            raw = loader.getServerConfigs();
            if ((raw == null || raw.isEmpty()) && config != null && config.getTools() != null) {
                raw = config.getTools().getMcpServers();
            }
        } else if (config != null && config.getTools() != null) {
            raw = config.getTools().getMcpServers();
        } else {
            raw = Map.of();
        }
        return MCPAdapters.parseMcpServers(raw);
    }

    private List<Map<String, Object>> toolExplanations(
            String serverName,
            Config.MCPServerConfig cfg,
            MCPAdapters.MCPServerLoadInfo info,
            List<String> registryNames
    ) {
        TreeSet<String> names = new TreeSet<>();
        if (info != null && info.rawToolNames() != null) {
            for (String raw : info.rawToolNames()) {
                names.add(wrappedName(serverName, raw));
            }
        }
        if (info != null && info.filteredToolNames() != null) {
            names.addAll(info.filteredToolNames());
        }
        names.addAll(registryNames.stream()
                .filter(name -> name.startsWith("mcp_" + serverName + "_"))
                .filter(name -> !name.contains("_resource_") && !name.contains("_prompt_"))
                .toList());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String wrapped : names) {
            String raw = rawToolName(serverName, wrapped);
            boolean registered = registryNames.contains(wrapped);
            boolean allowedByEnabledTools = allowedByEnabledTools(cfg, raw, wrapped);
            boolean allowedByToolsets = true;
            boolean exposed = registered && allowedByEnabledTools && allowedByToolsets;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverName", serverName);
            row.put("name", wrapped);
            row.put("rawName", raw);
            row.put("registeredToToolRegistry", registered);
            row.put("allowedByEnabledTools", allowedByEnabledTools);
            row.put("allowedByToolsets", allowedByToolsets);
            row.put("toolsetsRestricted", false);
            row.put("exposedToModel", exposed);
            row.put("reason", toolReason(cfg, registered, allowedByEnabledTools, allowedByToolsets));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> schemaSummary() {
        if (registry == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> schema : registry.getDefinitions()) {
            String name = schemaName(schema);
            if (!name.startsWith("mcp_")) {
                continue;
            }
            Map<String, Object> fn = functionMap(schema);
            Map<String, Object> params = asObjectMap(fn.get("parameters"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("description", clean(String.valueOf(fn.getOrDefault("description", ""))));
            row.put("parameterCount", parameterCount(params));
            row.put("required", required(params));
            out.add(row);
        }
        out.sort(Comparator.comparing(row -> String.valueOf(row.get("name"))));
        return out;
    }

    private String schemaHash(List<Map<String, Object>> schemaSummary) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(canonical(schemaSummary));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).toList();
        }
        return value;
    }

    private List<String> registeredToolNames(String serverName, List<String> registryNames, MCPAdapters.MCPServerLoadInfo info) {
        List<String> names = registryNames.stream()
                .filter(name -> name.startsWith("mcp_" + serverName + "_"))
                .filter(name -> !name.contains("_resource_") && !name.contains("_prompt_"))
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            return names;
        }
        if (info != null && info.registeredToolNames() != null) {
            return info.registeredToolNames().stream().sorted().toList();
        }
        return List.of();
    }

    private List<String> filteredToolNames(MCPAdapters.MCPServerLoadInfo info) {
        if (info == null || info.filteredToolNames() == null) {
            return List.of();
        }
        return info.filteredToolNames().stream().sorted().toList();
    }

    private String normalizeStatus(String raw, MCPAdapters.MCPServerLoadInfo info, Config.MCPServerConfig cfg) {
        if ("connected".equalsIgnoreCase(raw)) {
            return "CONNECTED";
        }
        if (info != null && info.status() != null && !info.status().isBlank()) {
            return info.status();
        }
        if ("disconnected".equalsIgnoreCase(raw)) {
            return cfg != null ? "FAILED" : "UNKNOWN";
        }
        return cfg != null ? "CONFIGURED" : "UNKNOWN";
    }

    private String disabledReason(String status, Config.MCPServerConfig cfg, MCPAdapters.MCPServerLoadInfo info, List<String> registered) {
        if (cfg == null) {
            return "server not configured";
        }
        if ("CONNECTED".equalsIgnoreCase(status) && registered.isEmpty()) {
            return "connected but no MCP tools are exposed";
        }
        if ("CONNECTED".equalsIgnoreCase(status)) {
            return "";
        }
        if (info != null && info.lastError() != null && !info.lastError().isBlank()) {
            return redactText(info.lastError());
        }
        return "server is not connected";
    }

    private List<String> configWarnings(Config.MCPServerConfig cfg, MCPAdapters.MCPServerLoadInfo info) {
        List<String> warnings = new ArrayList<>();
        if (cfg != null) {
            String type = transportType(cfg, info);
            if (!List.of("stdio", "streamableHttp").contains(type)) {
                warnings.add("unknown transport type: " + type);
            }
            if ("stdio".equals(type) && (cfg.getCommand() == null || cfg.getCommand().isBlank())) {
                warnings.add("stdio transport requires command");
            }
            if ("streamableHttp".equals(type) && (cfg.getUrl() == null || cfg.getUrl().isBlank())) {
                warnings.add(type + " transport requires url");
            }
        }
        if (info != null && info.configWarnings() != null) {
            warnings.addAll(info.configWarnings().stream().map(this::redactText).toList());
        }
        return warnings.stream().distinct().toList();
    }

    private boolean allowedByEnabledTools(Config.MCPServerConfig cfg, String rawName, String wrappedName) {
        if (cfg == null) {
            return false;
        }
        List<String> enabledTools = cfg.getEnabledTools();
        return enabledTools == null
                || enabledTools.isEmpty()
                || enabledTools.contains("*")
                || enabledTools.contains(rawName)
                || enabledTools.contains(wrappedName);
    }

    private String toolReason(Config.MCPServerConfig cfg, boolean registered, boolean allowedByEnabledTools, boolean allowedByToolsets) {
        if (cfg == null) {
            return "server not configured";
        }
        if (!allowedByEnabledTools) {
            return "filtered by enabled_tools";
        }
        if (!allowedByToolsets) {
            return "filtered by toolsets";
        }
        if (!registered) {
            return "not registered; server failed, returned no tools, or tool was not loaded";
        }
        return "exposed";
    }

    private String transportType(Config.MCPServerConfig cfg, MCPAdapters.MCPServerLoadInfo info) {
        if (info != null && info.transportType() != null && !info.transportType().isBlank()) {
            return info.transportType();
        }
        if (cfg == null) {
            return "unknown";
        }
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

    private Map<String, Object> sanitizedConfig(Config.MCPServerConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", transportType(cfg, null));
        out.put("url", sanitizeUrl(cfg.getUrl()));
        out.put("command", redactText(cfg.getCommand()));
        out.put("args", cfg.getArgs() != null ? cfg.getArgs().stream().map(this::redactText).toList() : List.of());
        out.put("env", sanitizeEnv(cfg.getEnv()));
        out.put("toolTimeout", cfg.getToolTimeout());
        out.put("enabledTools", cfg.getEnabledTools());
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
                return redactText(raw);
            }
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), "[REDACTED]", uri.getFragment()).toString();
        } catch (Exception e) {
            return redactText(raw);
        }
    }

    private String redactText(String value) {
        if (value == null || value.isBlank()) {
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

    private String wrappedName(String serverName, String rawToolName) {
        return "mcp_" + serverName + "_" + rawToolName;
    }

    private String rawToolName(String serverName, String wrappedName) {
        String prefix = "mcp_" + serverName + "_";
        return wrappedName != null && wrappedName.startsWith(prefix) ? wrappedName.substring(prefix.length()) : wrappedName;
    }

    private String schemaName(Map<String, Object> schema) {
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name instanceof String s ? s : "";
        }
        Object name = schema.get("name");
        return name instanceof String s ? s : "";
    }

    private Map<String, Object> functionMap(Map<String, Object> schema) {
        Object fn = schema.get("function");
        return fn instanceof Map<?, ?> map ? asObjectMap(map) : schema;
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private int parameterCount(Map<String, Object> params) {
        Object props = params.get("properties");
        return props instanceof Map<?, ?> map ? map.size() : 0;
    }

    private List<String> required(Map<String, Object> params) {
        Object required = params.get("required");
        if (!(required instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).sorted().toList();
    }

    private String clean(String value) {
        return value != null ? value : "";
    }
}
