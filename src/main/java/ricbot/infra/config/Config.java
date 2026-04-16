package ricbot.infra.config;


import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;
import ricbot.integration.channel.DingTalkChannel;
import ricbot.integration.channel.FeishuChannel;
import ricbot.integration.channel.WecomChannel;
import ricbot.integration.channel.QQChannel;
import ricbot.integration.channel.WeixinChannel;
import ricbot.integration.channel.EmailChannel;
import ricbot.integration.channel.WebSocketChannel;

import java.nio.file.Path;
import java.util.*;

/**
 * Ricbot 运行配置类
 */
public class Config {

    private AgentsConfig agents = new AgentsConfig();
    private ProvidersConfig providers = new ProvidersConfig();
    private ToolsConfig tools = new ToolsConfig();
    private ChannelsConfig channels = new ChannelsConfig();
    private GatewayConfig gateway = new GatewayConfig();
    private ApiConfig api = new ApiConfig();

    public Config() {
    }

    public AgentsConfig getAgents() {
        return agents;
    }

    public void setAgents(AgentsConfig agents) {
        this.agents = agents != null ? agents : new AgentsConfig();
    }

    public ProvidersConfig getProviders() {
        return providers;
    }

    public void setProviders(ProvidersConfig providers) {
        this.providers = providers != null ? providers : new ProvidersConfig();
    }

    public ToolsConfig getTools() {
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools != null ? tools : new ToolsConfig();
    }

    public ChannelsConfig getChannels() {
        return channels;
    }

    public void setChannels(ChannelsConfig channels) {
        this.channels = channels != null ? channels : new ChannelsConfig();
    }

    public GatewayConfig getGateway() {
        return gateway;
    }

    public void setGateway(GatewayConfig gateway) {
        this.gateway = gateway != null ? gateway : new GatewayConfig();
    }

    public ApiConfig getApi() {
        return api;
    }

    public void setApi(ApiConfig api) {
        this.api = api != null ? api : new ApiConfig();
    }

    public Path getWorkspacePath() {
        String raw = agents != null && agents.getDefaults() != null
                ? agents.getDefaults().getWorkspace()
                : null;

        if (raw == null || raw.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".ricbot", "workspace")
                    .toAbsolutePath()
                    .normalize();
        }

        return Path.of(raw).toAbsolutePath().normalize();
    }

    public String getProviderName(String model) {
        if (model == null || model.isBlank()) {
            return "openai";
        }

        String normalized = model.trim();

        int idx = normalized.indexOf('/');
        if (idx > 0) {
            String prefix = normalized.substring(0, idx).trim();
            ProviderSpec direct = ProviderRegistry.findByName(prefix);
            if (direct != null) {
                return direct.getName();
            }

            String alias = normalizeProviderAlias(prefix);
            ProviderSpec aliasSpec = ProviderRegistry.findByName(alias);
            if (aliasSpec != null) {
                return aliasSpec.getName();
            }
        }

        ProviderSpec byKeyword = ProviderRegistry.findByModelKeyword(normalized);
        if (byKeyword != null) {
            return byKeyword.getName();
        }

        for (Map.Entry<String, ProviderConfig> entry : providers.asMap().entrySet()) {
            ProviderConfig cfg = entry.getValue();
            if (cfg == null) {
                continue;
            }
            if (cfg.getApiBase() != null && !cfg.getApiBase().isBlank()) {
                ProviderSpec byBase = ProviderRegistry.findByBaseKeyword(cfg.getApiBase());
                if (byBase != null) {
                    return byBase.getName();
                }
            }
        }

        return "openai";
    }

    public ProviderConfig getProvider(String model) {
        String providerName = getProviderName(model);
        ProviderConfig cfg = providers.get(providerName);
        if (isBlankProviderConfig(cfg)) {
            ProviderSpec spec = ProviderRegistry.findByName(providerName);
            if (spec != null
                    && "openai_compat".equalsIgnoreCase(spec.getBackend())
                    && !"openai".equalsIgnoreCase(providerName)) {
                ProviderConfig fallback = providers.get("openai");
                if (!isBlankProviderConfig(fallback)) {
                    return fallback;
                }
            }
        }
        return cfg != null ? cfg : new ProviderConfig();
    }

    public String getApiBase(String model) {
        String providerName = getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        ProviderConfig cfg = getProvider(model);

        if (cfg.getApiBase() != null && !cfg.getApiBase().isBlank()) {
            return cfg.getApiBase();
        }
        return spec != null ? spec.getDefaultApiBase() : null;
    }

    private String normalizeProviderAlias(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return switch (p) {
            case "claude" -> "anthropic";
            case "gpt" -> "openai";
            case "copilot" -> "github_copilot";
            default -> p;
        };
    }

    private static boolean isBlankProviderConfig(ProviderConfig cfg) {
        if (cfg == null) {
            return true;
        }
        boolean noKey = cfg.getApiKey() == null || cfg.getApiKey().isBlank();
        boolean noBase = cfg.getApiBase() == null || cfg.getApiBase().isBlank();
        boolean noHeaders = cfg.getExtraHeaders() == null || cfg.getExtraHeaders().isEmpty();
        return noKey && noBase && noHeaders;
    }

    public static class AgentsConfig {
        private AgentDefaults defaults = new AgentDefaults();

        public AgentsConfig() {
        }

        public AgentDefaults getDefaults() {
            return defaults;
        }

        public void setDefaults(AgentDefaults defaults) {
            this.defaults = defaults != null ? defaults : new AgentDefaults();
        }
    }

    public static class AgentDefaults {
        private String model = "gpt-4o";
        private String workspace = Path.of(System.getProperty("user.home"), ".ricbot", "workspace").toString();
        private double temperature = 0.1;
        private int maxTokens = 4096;
        private String reasoningEffort;
        private int maxToolIterations = 20;
        private Integer contextWindowTokens = 64000;
        private Integer contextBlockLimit = 200;
        private int maxToolResultChars = 16000;
        private String providerRetryMode = "standard";
        private String timezone = "UTC";
        private boolean unifiedSession = false;
        private List<String> disabledSkills = new ArrayList<>();
        private int sessionTtlMinutes = 0;
        private DreamConfig dream = new DreamConfig();

        public AgentDefaults() {
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public int getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(int maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }

        public Integer getContextWindowTokens() {
            return contextWindowTokens;
        }

        public void setContextWindowTokens(Integer contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
        }

        public Integer getContextBlockLimit() {
            return contextBlockLimit;
        }

        public void setContextBlockLimit(Integer contextBlockLimit) {
            this.contextBlockLimit = contextBlockLimit;
        }

        public int getMaxToolResultChars() {
            return maxToolResultChars;
        }

        public void setMaxToolResultChars(int maxToolResultChars) {
            this.maxToolResultChars = maxToolResultChars;
        }

        public String getProviderRetryMode() {
            return providerRetryMode;
        }

        public void setProviderRetryMode(String providerRetryMode) {
            this.providerRetryMode = providerRetryMode;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public boolean isUnifiedSession() {
            return unifiedSession;
        }

        public void setUnifiedSession(boolean unifiedSession) {
            this.unifiedSession = unifiedSession;
        }

        public List<String> getDisabledSkills() {
            return disabledSkills;
        }

        public void setDisabledSkills(List<String> disabledSkills) {
            this.disabledSkills = disabledSkills != null ? disabledSkills : new ArrayList<>();
        }

        public int getSessionTtlMinutes() {
            return sessionTtlMinutes;
        }

        public void setSessionTtlMinutes(int sessionTtlMinutes) {
            this.sessionTtlMinutes = sessionTtlMinutes;
        }

        public DreamConfig getDream() {
            return dream;
        }

        public void setDream(DreamConfig dream) {
            this.dream = dream != null ? dream : new DreamConfig();
        }
    }

    public static class DreamConfig {
        private boolean enabled = true;
        private String modelOverride;
        private int maxBatchSize = 20;
        private int maxIterations = 5;
        private String cron = "0 3 * * *";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModelOverride() {
            return modelOverride;
        }

        public void setModelOverride(String modelOverride) {
            this.modelOverride = modelOverride;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String buildSchedule(String timezone) {
            if (cron == null) {
                return null;
            }
            if (timezone == null || timezone.isBlank()) {
                return cron;
            }
            return cron + " @ " + timezone.trim();
        }

        public String describeSchedule() {
            return cron != null ? cron : "disabled";
        }
    }

    public static class ProvidersConfig {
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig anthropic = new ProviderConfig();
        private ProviderConfig azure_openai = new ProviderConfig();
        private ProviderConfig openai_codex = new ProviderConfig();
        private ProviderConfig github_copilot = new ProviderConfig();
        private ProviderConfig openrouter = new ProviderConfig();
        private ProviderConfig deepseek = new ProviderConfig();
        private ProviderConfig dashscope = new ProviderConfig();
        private ProviderConfig moonshot = new ProviderConfig();
        private ProviderConfig zhipu = new ProviderConfig();
        private ProviderConfig minimax = new ProviderConfig();
        private ProviderConfig mistral = new ProviderConfig();
        private ProviderConfig groq = new ProviderConfig();
        private ProviderConfig custom = new ProviderConfig();
        private Map<String, ProviderConfig> extra = new LinkedHashMap<>();

        public ProviderConfig getOpenai() {
            return openai;
        }

        public void setOpenai(ProviderConfig openai) {
            this.openai = openai;
        }

        public ProviderConfig getAnthropic() {
            return anthropic;
        }

        public void setAnthropic(ProviderConfig anthropic) {
            this.anthropic = anthropic;
        }

        public ProviderConfig getAzure_openai() {
            return azure_openai;
        }

        public void setAzure_openai(ProviderConfig azure_openai) {
            this.azure_openai = azure_openai;
        }

        public ProviderConfig getOpenai_codex() {
            return openai_codex;
        }

        public void setOpenai_codex(ProviderConfig openai_codex) {
            this.openai_codex = openai_codex;
        }

        public ProviderConfig getGithub_copilot() {
            return github_copilot;
        }

        public void setGithub_copilot(ProviderConfig github_copilot) {
            this.github_copilot = github_copilot;
        }

        public ProviderConfig getOpenrouter() {
            return openrouter;
        }

        public void setOpenrouter(ProviderConfig openrouter) {
            this.openrouter = openrouter;
        }

        public ProviderConfig getDeepseek() {
            return deepseek;
        }

        public void setDeepseek(ProviderConfig deepseek) {
            this.deepseek = deepseek;
        }

        public ProviderConfig getDashscope() {
            return dashscope;
        }

        public void setDashscope(ProviderConfig dashscope) {
            this.dashscope = dashscope;
        }

        public ProviderConfig getMoonshot() {
            return moonshot;
        }

        public void setMoonshot(ProviderConfig moonshot) {
            this.moonshot = moonshot;
        }

        public ProviderConfig getZhipu() {
            return zhipu;
        }

        public void setZhipu(ProviderConfig zhipu) {
            this.zhipu = zhipu;
        }

        public ProviderConfig getMinimax() {
            return minimax;
        }

        public void setMinimax(ProviderConfig minimax) {
            this.minimax = minimax;
        }

        public ProviderConfig getMistral() {
            return mistral;
        }

        public void setMistral(ProviderConfig mistral) {
            this.mistral = mistral;
        }

        public ProviderConfig getGroq() {
            return groq;
        }

        public void setGroq(ProviderConfig groq) {
            this.groq = groq;
        }

        public ProviderConfig getCustom() {
            return custom;
        }

        public void setCustom(ProviderConfig custom) {
            this.custom = custom;
        }

        public Map<String, ProviderConfig> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, ProviderConfig> extra) {
            this.extra = extra != null ? new LinkedHashMap<>(extra) : new LinkedHashMap<>();
        }

        public ProviderConfig get(String name) {
            String key = canonicalName(name);
            if (key == null) {
                return null;
            }
            ProviderConfig known = switch (key) {
                case "openai" -> openai;
                case "openai_compat" -> openai;
                case "anthropic" -> anthropic;
                case "azure_openai" -> azure_openai;
                case "openai_codex" -> openai_codex;
                case "github_copilot" -> github_copilot;
                case "openrouter" -> openrouter;
                case "deepseek" -> deepseek;
                case "dashscope" -> dashscope;
                case "moonshot" -> moonshot;
                case "zhipu" -> zhipu;
                case "minimax" -> minimax;
                case "mistral" -> mistral;
                case "groq" -> groq;
                case "custom" -> custom;
                default -> null;
            };
            if (known != null) {
                return known;
            }
            return extra.get(key);
        }

        public ProviderConfig getOrCreate(String name) {
            String key = canonicalName(name);
            if (key == null) {
                return null;
            }
            ProviderConfig existing = get(key);
            if (existing != null) {
                return existing;
            }
            ProviderConfig created = new ProviderConfig();
            extra.put(key, created);
            return created;
        }

        public void put(String name, ProviderConfig config) {
            String key = canonicalName(name);
            if (key == null) {
                return;
            }
            ProviderConfig value = config != null ? config : new ProviderConfig();
            switch (key) {
                case "openai", "openai_compat" -> openai = value;
                case "anthropic" -> anthropic = value;
                case "azure_openai" -> azure_openai = value;
                case "openai_codex" -> openai_codex = value;
                case "github_copilot" -> github_copilot = value;
                case "openrouter" -> openrouter = value;
                case "deepseek" -> deepseek = value;
                case "dashscope" -> dashscope = value;
                case "moonshot" -> moonshot = value;
                case "zhipu" -> zhipu = value;
                case "minimax" -> minimax = value;
                case "mistral" -> mistral = value;
                case "groq" -> groq = value;
                case "custom" -> custom = value;
                default -> extra.put(key, value);
            }
        }

        public Map<String, ProviderConfig> asMap() {
            Map<String, ProviderConfig> map = new LinkedHashMap<>();
            map.put("openai", openai);
            map.put("anthropic", anthropic);
            map.put("azure_openai", azure_openai);
            map.put("openai_codex", openai_codex);
            map.put("github_copilot", github_copilot);
            map.put("openrouter", openrouter);
            map.put("deepseek", deepseek);
            map.put("dashscope", dashscope);
            map.put("moonshot", moonshot);
            map.put("zhipu", zhipu);
            map.put("minimax", minimax);
            map.put("mistral", mistral);
            map.put("groq", groq);
            map.put("custom", custom);
            if (extra != null && !extra.isEmpty()) {
                for (Map.Entry<String, ProviderConfig> entry : extra.entrySet()) {
                    String key = canonicalName(entry.getKey());
                    if (key != null && !isKnownName(key)) {
                        map.put(key, entry.getValue());
                    }
                }
            }
            return map;
        }

        private static boolean isKnownName(String key) {
            return switch (key) {
                case "openai",
                     "openai_compat",
                     "anthropic",
                     "azure_openai",
                     "openai_codex",
                     "github_copilot",
                     "openrouter",
                     "deepseek",
                     "dashscope",
                     "moonshot",
                     "zhipu",
                     "minimax",
                     "mistral",
                     "groq",
                     "custom" -> true;
                default -> false;
            };
        }

        private static String canonicalName(String name) {
            if (name == null) {
                return null;
            }
            String key = name.trim();
            if (key.isBlank()) {
                return null;
            }
            return key.toLowerCase(Locale.ROOT);
        }
    }

    public static class ProviderConfig {
        private String apiKey;
        private String apiBase;
        private Map<String, String> extraHeaders = new LinkedHashMap<>();

        public ProviderConfig() {
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public Map<String, String> getExtraHeaders() {
            return extraHeaders;
        }

        public void setExtraHeaders(Map<String, String> extraHeaders) {
            this.extraHeaders = extraHeaders != null ? extraHeaders : new LinkedHashMap<>();
        }
    }

    public static class ToolsConfig {
        private WebToolsConfig web = new WebToolsConfig();
        private ExecToolConfig exec = new ExecToolConfig();
        private boolean restrictToWorkspace = false;
        private List<String> ssrfWhitelist = new ArrayList<>();
        private Map<String, Object> mcpServers = new LinkedHashMap<>();

        public WebToolsConfig getWeb() {
            return web;
        }

        public void setWeb(WebToolsConfig web) {
            this.web = web != null ? web : new WebToolsConfig();
        }

        public ExecToolConfig getExec() {
            return exec;
        }

        public void setExec(ExecToolConfig exec) {
            this.exec = exec != null ? exec : new ExecToolConfig();
        }

        public boolean isRestrictToWorkspace() {
            return restrictToWorkspace;
        }

        public void setRestrictToWorkspace(boolean restrictToWorkspace) {
            this.restrictToWorkspace = restrictToWorkspace;
        }

        public List<String> getSsrfWhitelist() {
            return ssrfWhitelist;
        }

        public void setSsrfWhitelist(List<String> ssrfWhitelist) {
            this.ssrfWhitelist = ssrfWhitelist != null ? ssrfWhitelist : new ArrayList<>();
        }

        public Map<String, Object> getMcpServers() {
            return mcpServers;
        }

        public void setMcpServers(Map<String, Object> mcpServers) {
            this.mcpServers = mcpServers != null ? mcpServers : new LinkedHashMap<>();
        }

        public Map<String, MCPServerConfig> getMcpServerConfigs() {
            Map<String, MCPServerConfig> out = new LinkedHashMap<>();
            if (mcpServers == null || mcpServers.isEmpty()) {
                return out;
            }

            for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object raw = entry.getValue();
                if (raw instanceof MCPServerConfig cfg) {
                    out.put(name, cfg);
                    continue;
                }
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }

                Map<String, Object> cfg = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    cfg.put(String.valueOf(e.getKey()), e.getValue());
                }

                MCPServerConfig server = new MCPServerConfig();
                server.setType(stringValue(cfg, "type", server.getType()));
                server.setUrl(stringValue(cfg, "url", server.getUrl()));
                server.setCommand(stringValue(cfg, "command", server.getCommand()));
                server.setArgs(stringListValue(cfg, "args", server.getArgs()));
                server.setEnv(stringMapValue(cfg, "env", server.getEnv()));
                server.setEnabledTools(stringListValue(
                        cfg,
                        "enabled_tools",
                        stringListValue(cfg, "enabledTools", server.getEnabledTools())
                ));
                server.setToolTimeout(intValue(cfg, "tool_timeout", intValue(cfg, "toolTimeout", server.getToolTimeout())));

                out.put(name, server);
            }

            return out;
        }

        private static String stringValue(Map<String, Object> map, String key, String def) {
            Object v = map.get(key);
            if (v == null) {
                return def;
            }
            String s = String.valueOf(v);
            return s.isBlank() ? def : s;
        }

        private static int intValue(Map<String, Object> map, String key, int def) {
            Object v = map.get(key);
            if (v == null) {
                return def;
            }
            if (v instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (Exception e) {
                return def;
            }
        }

        private static List<String> stringListValue(Map<String, Object> map, String key, List<String> def) {
            Object v = map.get(key);
            if (v == null) {
                return def != null ? def : new ArrayList<>();
            }
            if (v instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
                return out;
            }
            if (v instanceof String s && !s.isBlank()) {
                return List.of(s);
            }
            return def != null ? def : new ArrayList<>();
        }

        private static Map<String, String> stringMapValue(Map<String, Object> map, String key, Map<String, String> def) {
            Object v = map.get(key);
            if (v == null) {
                return def != null ? def : new HashMap<>();
            }
            if (v instanceof Map<?, ?> raw) {
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
                }
                return out;
            }
            return def != null ? def : new HashMap<>();
        }
    }

    public static class MCPServerConfig {
        private String type = "stdio";
        private String url;
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private List<String> enabledTools = new ArrayList<>();
        private int toolTimeout = 60;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args != null ? args : new ArrayList<>();
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env != null ? env : new HashMap<>();
        }

        public List<String> getEnabledTools() {
            return enabledTools;
        }

        public void setEnabledTools(List<String> enabledTools) {
            this.enabledTools = enabledTools != null ? enabledTools : new ArrayList<>();
        }

        public int getToolTimeout() {
            return toolTimeout;
        }

        public void setToolTimeout(int toolTimeout) {
            this.toolTimeout = toolTimeout;
        }
    }

    public static class WebToolsConfig {
        private boolean enable = true;
        private String proxy;
        private int maxChars = 50000;
        private WebSearchConfig search = new WebSearchConfig();

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public String getProxy() {
            return proxy;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public WebSearchConfig getSearch() {
            return search;
        }

        public void setSearch(WebSearchConfig search) {
            this.search = search != null ? search : new WebSearchConfig();
        }
    }

    public static class WebSearchConfig {
        private String provider = "duckduckgo";
        private String apiKey;
        private String baseUrl;
        private int maxResults = 5;
        private int timeout = 10;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }
    }

    public static class ExecToolConfig {
        private boolean enable = true;
        private int timeout = 60;
        private boolean sandbox = false;
        private String pathAppend = "";
        private List<String> allowedEnvKeys = new ArrayList<>();

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }

        public boolean isSandbox() {
            return sandbox;
        }

        public void setSandbox(boolean sandbox) {
            this.sandbox = sandbox;
        }

        public String getPathAppend() {
            return pathAppend;
        }

        public void setPathAppend(String pathAppend) {
            this.pathAppend = pathAppend;
        }

        public List<String> getAllowedEnvKeys() {
            return allowedEnvKeys;
        }

        public void setAllowedEnvKeys(List<String> allowedEnvKeys) {
            this.allowedEnvKeys = allowedEnvKeys != null ? allowedEnvKeys : new ArrayList<>();
        }

        public String getSandbox() {
            return sandbox ? "sandbox" : "";
        }
    }

    public static class ChannelsConfig {
        private boolean sendProgress = true;
        private boolean sendToolHints = true;
        private String transcriptionProvider = "groq";
        private int sendMaxRetries = 3;

        private FeishuChannel.FeishuConfig feishu = new FeishuChannel.FeishuConfig();
        private DingTalkChannel.DingTalkConfig dingtalk = new DingTalkChannel.DingTalkConfig();
        private WecomChannel.WecomConfig wecom = new WecomChannel.WecomConfig();
        private QQChannel.QQConfig qq = new QQChannel.QQConfig();
        private WeixinChannel.WeixinConfig weixin = new WeixinChannel.WeixinConfig();
        private EmailChannel.EmailConfig email = new EmailChannel.EmailConfig();
        private WebSocketChannel.WebSocketConfig websocket = new WebSocketChannel.WebSocketConfig();

        public boolean isSendProgress() {
            return sendProgress;
        }

        public void setSendProgress(boolean sendProgress) {
            this.sendProgress = sendProgress;
        }

        public boolean isSendToolHints() {
            return sendToolHints;
        }

        public void setSendToolHints(boolean sendToolHints) {
            this.sendToolHints = sendToolHints;
        }

        public String getTranscriptionProvider() {
            return transcriptionProvider;
        }

        public void setTranscriptionProvider(String transcriptionProvider) {
            this.transcriptionProvider = transcriptionProvider;
        }

        public int getSendMaxRetries() {
            return sendMaxRetries;
        }

        public void setSendMaxRetries(int sendMaxRetries) {
            this.sendMaxRetries = sendMaxRetries;
        }

        public FeishuChannel.FeishuConfig getFeishu() { return feishu; }
        public void setFeishu(FeishuChannel.FeishuConfig feishu) { this.feishu = feishu; }

        public DingTalkChannel.DingTalkConfig getDingtalk() { return dingtalk; }
        public void setDingtalk(DingTalkChannel.DingTalkConfig dingtalk) { this.dingtalk = dingtalk; }

        public WecomChannel.WecomConfig getWecom() { return wecom; }
        public void setWecom(WecomChannel.WecomConfig wecom) { this.wecom = wecom; }

        public QQChannel.QQConfig getQq() { return qq; }
        public void setQq(QQChannel.QQConfig qq) { this.qq = qq; }

        public WeixinChannel.WeixinConfig getWeixin() { return weixin; }
        public void setWeixin(WeixinChannel.WeixinConfig weixin) { this.weixin = weixin; }

        public EmailChannel.EmailConfig getEmail() { return email; }
        public void setEmail(EmailChannel.EmailConfig email) { this.email = email; }

        public WebSocketChannel.WebSocketConfig getWebsocket() { return websocket; }
        public void setWebsocket(WebSocketChannel.WebSocketConfig websocket) { this.websocket = websocket; }

        public Object getSection(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "feishu" -> feishu;
                case "dingtalk" -> dingtalk;
                case "wecom" -> wecom;
                case "qq" -> qq;
                case "weixin" -> weixin;
                case "email" -> email;
                case "websocket" -> websocket;
                default -> null;
            };
        }

        public boolean isEnabled(String name) {
            Object section = getSection(name);
            if (section instanceof FeishuChannel.FeishuConfig c) return c.isEnabled();
            if (section instanceof DingTalkChannel.DingTalkConfig c) return c.isEnabled();
            if (section instanceof WecomChannel.WecomConfig c) return c.isEnabled();
            if (section instanceof QQChannel.QQConfig c) return c.isEnabled();
            if (section instanceof WeixinChannel.WeixinConfig c) return c.isEnabled();
            if (section instanceof EmailChannel.EmailConfig c) return c.isEnabled();
            if (section instanceof WebSocketChannel.WebSocketConfig c) return c.isEnabled();
            return false;
        }
    }

    public static class GatewayConfig {
        private int port = 8000;
        private HeartbeatConfig heartbeat = new HeartbeatConfig();

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public HeartbeatConfig getHeartbeat() {
            return heartbeat;
        }

        public void setHeartbeat(HeartbeatConfig heartbeat) {
            this.heartbeat = heartbeat != null ? heartbeat : new HeartbeatConfig();
        }
    }

    public static class HeartbeatConfig {
        private boolean enabled = true;
        private int intervalS = 60;
        private int keepRecentMessages = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalS() {
            return intervalS;
        }

        public void setIntervalS(int intervalS) {
            this.intervalS = intervalS;
        }

        public int getKeepRecentMessages() {
            return keepRecentMessages;
        }

        public void setKeepRecentMessages(int keepRecentMessages) {
            this.keepRecentMessages = keepRecentMessages;
        }
    }

    public static class ApiConfig {
        private String host = "127.0.0.1";
        private int port = 8080;
        private double timeout = 120.0;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public double getTimeout() {
            return timeout;
        }

        public void setTimeout(double timeout) {
            this.timeout = timeout;
        }
    }
}
