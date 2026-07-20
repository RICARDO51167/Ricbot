package ricbot.domain.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;
import ricbot.integration.mcp.MCPAdapters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigDoctorService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private final EnvLookup envLookup;
    private final CommandAvailabilityChecker commandChecker;
    private final ProviderCapabilityResolver capabilityResolver = new ProviderCapabilityResolver();

    public ConfigDoctorService() {
        this(System::getenv, ConfigDoctorService::defaultCommandExists);
    }

    public ConfigDoctorService(EnvLookup envLookup, CommandAvailabilityChecker commandChecker) {
        this.envLookup = envLookup != null ? envLookup : System::getenv;
        this.commandChecker = commandChecker != null ? commandChecker : ConfigDoctorService::defaultCommandExists;
    }

    public ConfigDoctorReport diagnose(Config config, Path configPath) {
        Config rawConfig = config != null ? config : new Config();
        Path resolvedPath = configPath != null ? configPath.toAbsolutePath().normalize() : ConfigLoader.getConfigPath();
        Map<String, Object> rawJson = readRawJson(resolvedPath);
        Config resolvedConfig = ConfigLoader.resolveConfigEnvVars(rawConfig);

        ConfigDoctorReport report = new ConfigDoctorReport();
        report.setConfigPath(resolvedPath.toString());
        report.setWorkspace(resolvedConfig.getWorkspacePath().toString());

        String model = resolvedConfig.getAgents().getDefaults().getModel();
        String providerName = resolvedConfig.getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        Config.ProviderConfig providerConfig = resolvedConfig.getProvider(model);
        String apiBase = resolvedConfig.getApiBase(model);
        String apiKey = providerConfig != null ? providerConfig.getApiKey() : null;

        report.setModel(model);
        report.setInferredProvider(providerName);
        report.setApiBase(apiBase != null ? apiBase : "");
        report.setApiKeyPresent(!isBlank(apiKey) && !looksLikePlaceholder(apiKey));
        report.setEffectivePorts(effectivePorts(resolvedConfig));
        report.setEnabledTools(enabledTools(resolvedConfig));
        report.setMcpServers(mcpServers(resolvedConfig));
        ProviderCapability providerCapability = capabilityResolver.resolve(resolvedConfig, providerName, model);
        report.setProviderCapability(providerCapability);

        if (!Files.exists(resolvedPath)) {
            report.addWarning("配置文件不存在，将使用默认配置：" + resolvedPath);
            report.addSuggestedFix("创建配置文件，或使用 -c/--config 指向正确的 ricbot.config.json。");
        }

        diagnoseEnvironmentPlaceholders(rawJson, report);
        diagnoseProvider(rawConfig, resolvedConfig, providerName, spec, model, apiBase, apiKey, report);
        diagnosePorts(resolvedConfig, rawJson, report);
        diagnoseTools(resolvedConfig, rawJson, report);
        diagnoseModelCapabilityOverrides(rawJson, resolvedConfig, providerName, model, providerCapability, report);
        diagnoseMcp(resolvedConfig, report);
        return report;
    }

    private Map<String, Object> readRawJson(Path path) {
        if (path == null || !Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(path.toFile(), JSON_OBJECT_TYPE);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private void diagnoseEnvironmentPlaceholders(Map<String, Object> rawJson, ConfigDoctorReport report) {
        for (EnvReference ref : findEnvReferences(rawJson)) {
            String value = envLookup.get(ref.name());
            if (!isBlank(value)) {
                continue;
            }
            String message = "环境变量未设置：" + ref.name() + "（引用位置：" + ref.path() + "）";
            if (ref.path().contains(".api_key") || ref.path().contains(".apiKey")) {
                report.addError(message);
                report.addSuggestedFix("设置 " + ref.name() + "，或在 provider 配置中填入可用 api_key。");
            } else {
                report.addWarning(message);
            }
        }
    }

    private void diagnoseProvider(
            Config rawConfig,
            Config resolvedConfig,
            String providerName,
            ProviderSpec spec,
            String model,
            String apiBase,
            String apiKey,
            ConfigDoctorReport report
    ) {
        if (spec == null) {
            report.addError("无法从模型推断出已注册 Provider：model=" + model + ", provider=" + providerName);
            report.addSuggestedFix("使用带 provider 前缀的模型名，或补充 ProviderRegistry 中的 provider spec。");
            return;
        }

        if (isBlank(apiBase)) {
            report.addWarning("Provider api_base 为空：provider=" + providerName);
            report.addSuggestedFix("为 providers." + providerName + ".api_base 配置兼容端点，或确认该 provider 有默认 api_base。");
        }

        if (requiresApiKey(spec) && (isBlank(apiKey) || looksLikePlaceholder(apiKey))) {
            report.addError("Provider '" + providerName + "' 缺少已解析的 API Key。");
            String envKey = spec.getEnvKey();
            report.addSuggestedFix(!isBlank(envKey)
                    ? "写入 providers." + providerName + ".api_key，或将其配置为 ${" + envKey + "} 并设置该环境变量。"
                    : "写入 providers." + providerName + ".api_key。");
        }

        if (providerInferenceIsAmbiguous(rawConfig, resolvedConfig, model, providerName)) {
            report.addWarning("模型/Provider 推断不明确：model=" + model + " fallback 到 provider=" + providerName);
            report.addSuggestedFix("将 model 写成 provider/model 形式，或显式配置对应 provider 的 api_base/api_key。");
        }
    }

    private void diagnosePorts(Config config, Map<String, Object> rawJson, ConfigDoctorReport report) {
        int gatewayPort = config.getGateway().getPort();
        int apiPort = config.getApi().getPort();
        int actualPort = apiPort > 0 ? apiPort : gatewayPort;
        if (apiPort > 0 && gatewayPort > 0 && apiPort != gatewayPort) {
            report.addWarning("gateway.port 与 api.port 不同：serve 实际监听 api.port=" + actualPort + "，gateway.port=" + gatewayPort + " 仍保留给 gateway/heartbeat 配置段。");
            report.addSuggestedFix("如果希望减少混淆，请让 gateway.port 与 api.port 保持一致，或只配置 api.port。");
        }
        if (hasPath(rawJson, "api", "host")) {
            report.addSuggestedFix("api.host 已接入 serve；绑定公网地址时请同时配置 api.bearer_token。");
        }
    }

    private void diagnoseTools(Config config, Map<String, Object> rawJson, ConfigDoctorReport report) {
        Config.ToolsConfig tools = config.getTools();
        if (tools == null) {
            return;
        }
        Config.ExecToolConfig exec = tools.getExec();
        Config.WebToolsConfig web = tools.getWeb();

        if (hasPath(rawJson, "tools", "web", "max_chars") || hasPath(rawJson, "tools", "web", "maxChars")) {
            report.addIgnoredField("tools.web.max_chars: 配置模型存在，但 ConfigLoader 当前未从 JSON 映射到运行时 WebToolsConfig.maxChars。");
            report.addWarning("tools.web.max_chars 当前不会改变 web_fetch 默认截断长度。");
            report.addSuggestedFix("暂时在 web_fetch 调用参数中传 max_chars，或等待后续版本接线 ConfigLoader。");
        }

        if (exec != null && exec.isEnable() && exec.isSandbox()
                && !commandChecker.commandExists("sandbox-exec")
                && !commandChecker.commandExists("bwrap")) {
            report.addWarning("exec.sandbox=true，但未发现 sandbox-exec 或 bwrap。");
            report.addSuggestedFix("安装 sandbox-exec/bwrap，或关闭 tools.exec.sandbox。");
        }

        if (tools.isRestrictToWorkspace() == false) {
            report.addWarning("restrictToWorkspace=false：文件与命令工具可能访问工作区之外的路径。");
            report.addSuggestedFix("除非明确需要跨目录操作，建议设置 tools.restrictToWorkspace=true。");
        }

        if (web != null && web.isEnable()) {
            diagnoseWebSearch(web.getSearch(), report);
        }
    }

    private void diagnoseWebSearch(Config.WebSearchConfig search, ConfigDoctorReport report) {
        if (search == null) {
            return;
        }
        String provider = !isBlank(search.getProvider()) ? search.getProvider().trim().toLowerCase(Locale.ROOT) : "duckduckgo";
        switch (provider) {
            case "brave" -> warnMissingSearchKey(provider, "BRAVE_API_KEY", search.getApiKey(), report);
            case "tavily" -> warnMissingSearchKey(provider, "TAVILY_API_KEY", search.getApiKey(), report);
            case "jina" -> warnMissingSearchKey(provider, "JINA_API_KEY", search.getApiKey(), report);
            case "kagi" -> warnMissingSearchKey(provider, "KAGI_API_KEY", search.getApiKey(), report);
            case "searxng" -> {
                if (isBlank(search.getBaseUrl()) && isBlank(envLookup.get("SEARXNG_BASE_URL"))) {
                    report.addWarning("tools.web.search.provider=searxng，但缺少 base_url/SEARXNG_BASE_URL；运行时会回退 DuckDuckGo。");
                    report.addSuggestedFix("配置 tools.web.search.base_url 或环境变量 SEARXNG_BASE_URL。");
                }
            }
            case "duckduckgo" -> {
            }
            default -> {
                report.addWarning("未知 web search provider：" + provider);
                report.addSuggestedFix("将 tools.web.search.provider 设置为 duckduckgo/tavily/searxng/jina/brave/kagi 之一。");
            }
        }
    }

    private void warnMissingSearchKey(String provider, String envName, String configuredKey, ConfigDoctorReport report) {
        if (isBlank(configuredKey) && isBlank(envLookup.get(envName))) {
            report.addWarning("tools.web.search.provider=" + provider + "，但缺少 api_key/" + envName + "；运行时会回退 DuckDuckGo。");
            report.addSuggestedFix("配置 tools.web.search.api_key 或环境变量 " + envName + "。");
        }
    }

    private void diagnoseMcp(Config config, ConfigDoctorReport report) {
        Map<String, Object> raw = config.getTools() != null ? config.getTools().getMcpServers() : Map.of();
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(raw);
        for (Map.Entry<String, Config.MCPServerConfig> entry : parsed.entrySet()) {
            Config.MCPServerConfig server = entry.getValue();
            String type = server != null ? server.getType() : null;
            if (!isBlank(type) && !List.of("stdio", "sse", "streamableHttp").contains(type)) {
                report.addWarning("MCP server '" + entry.getKey() + "' 使用未知 type：" + type);
                report.addSuggestedFix("将 MCP server type 设置为 stdio、sse 或 streamableHttp。");
            }
        }
    }

    private void diagnoseModelCapabilityOverrides(
            Map<String, Object> rawJson,
            Config config,
            String providerName,
            String model,
            ProviderCapability providerCapability,
            ConfigDoctorReport report
    ) {
        Map<String, Object> rawOverrides = capabilityOverrides(rawJson);
        for (Map.Entry<String, Object> entry : rawOverrides.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> value = copyObjectMap(entry.getValue() instanceof Map<?, ?> map ? map : Map.of());
            warnInvalidTokenValue(key, "contextWindowTokens", value.get("contextWindowTokens"), report);
            warnInvalidTokenValue(key, "maxOutputTokens", value.get("maxOutputTokens"), report);

            if (!capabilityResolver.overrideAppliesTo(key, providerName, model)) {
                report.addWarning("model_capabilities." + key + " 当前未被默认模型使用，仅作为低优先级提示。");
            }
        }

        if (providerCapability != null
                && (ProviderCapability.SOURCE_USER_OVERRIDE.equals(providerCapability.source())
                || ProviderCapability.SOURCE_MIXED.equals(providerCapability.source()))) {
            report.addWarning("当前 provider capability 包含用户 override；这是本地声明，不是在线探测，可能与实际 provider 能力不一致。");
            report.addSuggestedFix("如遇到工具调用、流式输出或 JSON mode 异常，请核对 model_capabilities 中的能力声明。");
        }
    }

    private Map<String, Object> capabilityOverrides(Map<String, Object> rawJson) {
        Object value = rawJson.containsKey("model_capabilities")
                ? rawJson.get("model_capabilities")
                : rawJson.get("modelCapabilities");
        return copyObjectMap(value instanceof Map<?, ?> map ? map : Map.of());
    }

    private void warnInvalidTokenValue(String modelKey, String field, Object value, ConfigDoctorReport report) {
        if (value == null) {
            return;
        }
        Integer parsed = parseInteger(value);
        if (parsed == null || parsed <= 0) {
            report.addWarning("model_capabilities." + modelKey + "." + field + " 必须是正数，当前值已被忽略。");
        }
    }

    private static Integer parseInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value != null ? Integer.valueOf(String.valueOf(value)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> effectivePorts(Config config) {
        Map<String, Object> ports = new LinkedHashMap<>();
        Config.GatewayConfig gateway = config.getGateway();
        Config.ApiConfig api = config.getApi();
        int gatewayPort = gateway != null ? gateway.getPort() : 0;
        int apiPort = api != null ? api.getPort() : 0;
        String apiHost = api != null ? api.getHost() : "";
        double apiTimeout = api != null ? api.getTimeout() : 0.0;
        ports.put("gatewayPort", gatewayPort);
        ports.put("apiPort", apiPort);
        ports.put("actualApiHost", apiHost);
        ports.put("actualApiPort", apiPort > 0 ? apiPort : gatewayPort);
        ports.put("apiTimeoutSeconds", apiTimeout);
        return ports;
    }

    private Map<String, Object> enabledTools(Config config) {
        Map<String, Object> tools = new LinkedHashMap<>();
        Config.ToolsConfig tc = config.getTools();
        Config.WebToolsConfig web = tc != null ? tc.getWeb() : null;
        Config.ExecToolConfig exec = tc != null ? tc.getExec() : null;
        Map<String, Object> mcp = tc != null ? tc.getMcpServers() : Map.of();
        tools.put("toolsEnable", true);
        tools.put("web", web != null && web.isEnable());
        tools.put("exec", exec != null && exec.isEnable());
        tools.put("execSandbox", exec != null && exec.isSandbox());
        tools.put("mcp", mcp != null && !mcp.isEmpty());
        tools.put("restrictToWorkspace", tc != null && tc.isRestrictToWorkspace());
        return tools;
    }

    private List<Map<String, Object>> mcpServers(Config config) {
        Map<String, Object> raw = config.getTools() != null ? config.getTools().getMcpServers() : Map.of();
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(raw);
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Map.Entry<String, Config.MCPServerConfig> entry : parsed.entrySet()) {
            Config.MCPServerConfig cfg = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("type", cfg != null ? cfg.getType() : "");
            row.put("command", cfg != null ? cfg.getCommand() : "");
            row.put("url", cfg != null ? cfg.getUrl() : "");
            row.put("enabledTools", cfg != null ? cfg.getEnabledTools() : List.of());
            servers.add(row);
        }
        return servers;
    }

    private List<EnvReference> findEnvReferences(Object value) {
        List<EnvReference> refs = new ArrayList<>();
        collectEnvReferences("$", value, refs);
        return refs;
    }

    private void collectEnvReferences(String path, Object value, List<EnvReference> refs) {
        if (value instanceof String s) {
            Matcher matcher = ENV_PATTERN.matcher(s);
            while (matcher.find()) {
                refs.add(new EnvReference(path, matcher.group(1)));
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                collectEnvReferences(path + "." + key, entry.getValue(), refs);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectEnvReferences(path + "[" + i + "]", list.get(i), refs);
            }
        }
    }

    private boolean providerInferenceIsAmbiguous(Config rawConfig, Config resolvedConfig, String model, String providerName) {
        if (isBlank(model) || providerName == null) {
            return true;
        }
        int slash = model.indexOf('/');
        if (slash > 0 && ProviderRegistry.findByName(model.substring(0, slash)) != null) {
            return false;
        }
        if (ProviderRegistry.findByModelKeyword(model) != null) {
            return false;
        }
        String apiBase = rawConfig.getApiBase(model);
        if (!isBlank(apiBase) && ProviderRegistry.findByBaseKeyword(apiBase) != null) {
            return false;
        }
        String resolvedApiBase = resolvedConfig.getApiBase(model);
        if (!isBlank(resolvedApiBase) && ProviderRegistry.findByBaseKeyword(resolvedApiBase) != null) {
            return false;
        }
        return "openai".equalsIgnoreCase(providerName);
    }

    private static boolean requiresApiKey(ProviderSpec spec) {
        return spec != null && !spec.isLocal() && !spec.isDirect() && !spec.isOauth();
    }

    private static boolean hasPath(Map<String, Object> map, String... path) {
        Object cur = map;
        for (String segment : path) {
            if (!(cur instanceof Map<?, ?> rawMap)) {
                return false;
            }
            Map<String, Object> objectMap = copyObjectMap(rawMap);
            if (!objectMap.containsKey(segment)) {
                return false;
            }
            cur = objectMap.get(segment);
        }
        return true;
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static boolean looksLikePlaceholder(String value) {
        return value != null && value.contains("${") && value.contains("}");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean defaultCommandExists(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", "command -v " + command).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    public interface EnvLookup {
        String get(String name);
    }

    @FunctionalInterface
    public interface CommandAvailabilityChecker {
        boolean commandExists(String command);
    }

    private record EnvReference(String path, String name) {
    }
}
