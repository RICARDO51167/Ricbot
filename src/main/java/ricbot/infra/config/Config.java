package ricbot.infra.config;


import lombok.*;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;
import ricbot.integration.channel.WebSocketChannel;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 统一承载 ricbot 的运行配置。
 *
 * 主要目标：
 * 1. 统一承载全部运行配置
 * 2. 提供按 model 推导 provider 的能力
 * 3. 提供 workspace / api_base / provider config 访问方法
 *
 * 设计说明（重要）：
 * - 这是一个“巨型配置类”：agent/provider/tool/mcp/channel/gateway/api 等都在此文件中。
 *   这种写法对“快速跑起来/单文件查配置”很友好，但长期维护会面临可读性差、模块边界模糊、修改冲击面大等问题。
 * - 部分字段属于“接口先长出来，主链实现未完全接入”的状态：调用方不要默认认为所有配置都已生效。
 *   例如 ToolsConfig.mcpServers 仍是弱类型 Map；
 *   ExecToolConfig 的 sandbox 暴露为 String 属于兼容历史接口的折中。
 * - ProvidersConfig/ChannelsConfig 采用“静态枚举式字段”，扩展新 provider/channel 往往需要改这个类与相关 switch/asMap。
 *   这比动态注册式配置更直观，但扩展性较弱。
 */
@Data
public class Config {

    /** 代理配置 */
    private AgentsConfig agents = new AgentsConfig();
    /** 提供商配置 */
    private ProvidersConfig providers = new ProvidersConfig();
    /** 工具配置 */
    private ToolsConfig tools = new ToolsConfig();
    /** 渠道配置 */
    private ChannelsConfig channels = new ChannelsConfig();
    /** 网关配置 */
    private GatewayConfig gateway = new GatewayConfig();
    /** API 配置 */
    private ApiConfig api = new ApiConfig();
    /** 用户声明的模型能力覆盖，不做在线探测。 */
    private Map<String, ModelCapabilityOverride> modelCapabilities = new LinkedHashMap<>();

    public Config() {
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools != null ? tools : new ToolsConfig();
    }

    public void setApi(ApiConfig api) {
        this.api = api != null ? api : new ApiConfig();
    }

    public void setModelCapabilities(Map<String, ModelCapabilityOverride> modelCapabilities) {
        this.modelCapabilities = modelCapabilities != null ? modelCapabilities : new LinkedHashMap<>();
    }

    /**
     * 对应 Python: config.workspace_path
     * 获取工作空间路径，如果未配置则返回默认路径 ~/.ricbot/workspace
     */
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

    /**
     * 对应 Python: config.get_provider_name(model)
     *
     * 规则：
     * 1. 模型名前缀如果显式包含 provider，例如 anthropic/claude...
     * 2. 否则按 registry 的 keyword 匹配
     * 3. 再按 provider 是否已配置 apiBase / apiKey 做弱判断
     * 4. 最后 fallback openai
     *
     * 注意：
     * - 这是启发式推断而非严格协议，存在误判可能；尤其是模型名不规范、或 apiBase 指向代理/聚合网关时。
     * - 推荐在配置层显式指定 provider/model（例如 openai/gpt-4o）来避免歧义。
     */
    public String getProviderName(String model) {
        // 检查模型名称是否为空或空白，如果是则返回默认提供商 "openai"
        if (model == null || model.isBlank()) {
            return "openai";
        }

        // 去除模型名称前后的空白字符
        String normalized = model.trim();

        // 1) 检查是否有显式的 provider 前缀，例如 anthropic/xxx、openai/xxx
        int idx = normalized.indexOf('/');
        if (idx > 0) {
            // 提取前缀部分
            String prefix = normalized.substring(0, idx).trim();
            // 尝试直接通过前缀查找 ProviderSpec
            ProviderSpec direct = ProviderRegistry.findByName(prefix);
            if (direct != null) {
                // 如果找到匹配的 ProviderSpec，返回其名称
                return direct.getName();
            }

            // 处理某些别名情况
            String alias = normalizeProviderAlias(prefix);
            ProviderSpec aliasSpec = ProviderRegistry.findByName(alias);
            if (aliasSpec != null) {
                // 如果通过别名找到匹配的 ProviderSpec，返回其名称
                return aliasSpec.getName();
            }
        }

        // 2) 尝试按模型关键字匹配 ProviderSpec
        ProviderSpec byKeyword = ProviderRegistry.findByModelKeyword(normalized);
        if (byKeyword != null) {
            // 如果找到匹配的关键字，返回对应的 ProviderSpec 名称
            return byKeyword.getName();
        }

        // 3) 如果某些 provider 配置了 apiBase，尝试按 apiBase 识别
        for (Map.Entry<String, ProviderConfig> entry : providers.asMap().entrySet()) {
            ProviderConfig cfg = entry.getValue();
            // 跳过空的配置项
            if (cfg == null) {
                continue;
            }
            // 检查 apiBase 是否非空
            if (cfg.getApiBase() != null && !cfg.getApiBase().isBlank()) {
                // 尝试通过 apiBase 关键字查找 ProviderSpec
                ProviderSpec byBase = ProviderRegistry.findByBaseKeyword(cfg.getApiBase());
                if (byBase != null) {
                    // 如果找到匹配的 ProviderSpec，返回其名称
                    return byBase.getName();
                }
            }
        }

        // 4) 如果以上步骤都未找到匹配的 provider，则返回默认的 "openai"
        return "openai";
    }

    /**
     * 对应 Python: config.get_provider(model)
     *
     * 根据 model 找到对应 provider 的配置段。
     */
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

    /**
     * 对应 Python: config.get_api_base(model)
     * 获取指定模型的 API 基础地址
     */
    public String getApiBase(String model) {
        String providerName = getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        ProviderConfig cfg = getProvider(model);

        if (cfg.getApiBase() != null && !cfg.getApiBase().isBlank()) {
            return cfg.getApiBase();
        }
        return spec != null ? spec.getDefaultApiBase() : null;
    }

    /**
     * 标准化提供商别名
     * 将常见的别名映射到标准的提供商名称
     */
    private String normalizeProviderAlias(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return switch (p) {
            case "claude" -> "anthropic";
            case "gpt" -> "openai";
            case "copilot" -> "github_copilot";
            default -> p;
        };
    }

    /**
     * 检查提供商配置是否为空
     * 如果 apiKey, apiBase 和 extraHeaders 都为空，则认为配置为空
     */
    private static boolean isBlankProviderConfig(ProviderConfig cfg) {
        if (cfg == null) {
            return true;
        }
        boolean noKey = cfg.getApiKey() == null || cfg.getApiKey().isBlank();
        boolean noBase = cfg.getApiBase() == null || cfg.getApiBase().isBlank();
        boolean noHeaders = cfg.getExtraHeaders() == null || cfg.getExtraHeaders().isEmpty();
        return noKey && noBase && noHeaders;
    }

    // =========================================================
    // Nested configs
    // =========================================================

    /**
     * 代理配置类
     * 包含代理的默认设置
     */
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
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentDefaults {
        /**
         * 默认值偏“开发环境方便启动”取向，不保证对生产环境都是最稳妥的选择。
         * 例如默认 model、contextWindowTokens、maxToolResultChars 等，需要结合部署环境调整。
         */
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
        public void setDisabledSkills(List<String> disabledSkills) {
            this.disabledSkills = disabledSkills != null ? disabledSkills : new ArrayList<>();
        }
    }

    // =========================================================
    // Providers
    // =========================================================

    @Data
    public static class ProvidersConfig {
        /**
         * ProvidersConfig 采用“静态字段枚举”的配置形态：
         * - 优点：结构直观、序列化简单；
         * - 缺点：扩展新 provider 需要改类字段、get(name) 与 asMap() 的 switch/映射，扩展性一般。
         */
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

        private static final Map<String, KnownProvider> KNOWN_PROVIDER_INDEX = buildKnownProviderIndex();

        public ProviderConfig get(String name) {
            String key = canonicalName(name);
            if (key == null) {
                return null;
            }
            ProviderConfig known = getKnownProvider(key);
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
            if (!setKnownProvider(key, value)) {
                extra.put(key, value);
            }
        }

        public Map<String, ProviderConfig> asMap() {
            Map<String, ProviderConfig> map = new LinkedHashMap<>();
            putKnownProviders(map);
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

        private ProviderConfig getKnownProvider(String key) {
            KnownProvider provider = KNOWN_PROVIDER_INDEX.get(key);
            return provider != null ? provider.getter().apply(this) : null;
        }

        private boolean setKnownProvider(String key, ProviderConfig value) {
            KnownProvider provider = KNOWN_PROVIDER_INDEX.get(key);
            if (provider == null) {
                return false;
            }
            provider.setter().accept(this, value);
            return true;
        }

        private void putKnownProviders(Map<String, ProviderConfig> target) {
            for (KnownProvider provider : KnownProvider.values()) {
                target.put(provider.canonicalName(), provider.getter().apply(this));
            }
        }

        private static boolean isKnownName(String key) {
            return KNOWN_PROVIDER_INDEX.containsKey(key);
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

        private static Map<String, KnownProvider> buildKnownProviderIndex() {
            Map<String, KnownProvider> index = new LinkedHashMap<>();
            for (KnownProvider provider : KnownProvider.values()) {
                index.put(provider.canonicalName(), provider);
                for (String alias : provider.aliases()) {
                    index.put(alias, provider);
                }
            }
            return Collections.unmodifiableMap(index);
        }

        private enum KnownProvider {
            OPENAI("openai", cfg -> cfg.openai, (cfg, value) -> cfg.openai = value, List.of("openai_compat")),
            ANTHROPIC("anthropic", cfg -> cfg.anthropic, (cfg, value) -> cfg.anthropic = value, List.of()),
            AZURE_OPENAI("azure_openai", cfg -> cfg.azure_openai, (cfg, value) -> cfg.azure_openai = value, List.of()),
            OPENAI_CODEX("openai_codex", cfg -> cfg.openai_codex, (cfg, value) -> cfg.openai_codex = value, List.of()),
            GITHUB_COPILOT("github_copilot", cfg -> cfg.github_copilot, (cfg, value) -> cfg.github_copilot = value, List.of()),
            OPENROUTER("openrouter", cfg -> cfg.openrouter, (cfg, value) -> cfg.openrouter = value, List.of()),
            DEEPSEEK("deepseek", cfg -> cfg.deepseek, (cfg, value) -> cfg.deepseek = value, List.of()),
            DASHSCOPE("dashscope", cfg -> cfg.dashscope, (cfg, value) -> cfg.dashscope = value, List.of()),
            MOONSHOT("moonshot", cfg -> cfg.moonshot, (cfg, value) -> cfg.moonshot = value, List.of()),
            ZHIPU("zhipu", cfg -> cfg.zhipu, (cfg, value) -> cfg.zhipu = value, List.of()),
            MINIMAX("minimax", cfg -> cfg.minimax, (cfg, value) -> cfg.minimax = value, List.of()),
            MISTRAL("mistral", cfg -> cfg.mistral, (cfg, value) -> cfg.mistral = value, List.of()),
            GROQ("groq", cfg -> cfg.groq, (cfg, value) -> cfg.groq = value, List.of()),
            CUSTOM("custom", cfg -> cfg.custom, (cfg, value) -> cfg.custom = value, List.of());

            private final String canonicalName;
            private final Function<ProvidersConfig, ProviderConfig> getter;
            private final BiConsumer<ProvidersConfig, ProviderConfig> setter;
            private final List<String> aliases;

            KnownProvider(
                    String canonicalName,
                    Function<ProvidersConfig, ProviderConfig> getter,
                    BiConsumer<ProvidersConfig, ProviderConfig> setter,
                    List<String> aliases
            ) {
                this.canonicalName = canonicalName;
                this.getter = getter;
                this.setter = setter;
                this.aliases = aliases;
            }

            private String canonicalName() {
                return canonicalName;
            }

            private Function<ProvidersConfig, ProviderConfig> getter() {
                return getter;
            }

            private BiConsumer<ProvidersConfig, ProviderConfig> setter() {
                return setter;
            }

            private List<String> aliases() {
                return aliases;
            }
        }
    }

    @Data
    public static class ProviderConfig {
        private String apiKey;
        private String apiBase;
        private Map<String, String> extraHeaders = new LinkedHashMap<>();

        public ProviderConfig() {
        }

        public void setExtraHeaders(Map<String, String> extraHeaders) {
            this.extraHeaders = extraHeaders != null ? extraHeaders : new LinkedHashMap<>();
        }
    }

    @Data
    public static class ModelCapabilityOverride {
        private String supportsToolCalling;
        private String supportsStreaming;
        private String supportsVision;
        private String supportsJsonMode;
        private String supportsReasoningEffort;
        private Integer contextWindowTokens;
        private Integer maxOutputTokens;
        private String apiMode;

        public boolean hasAnyField() {
            return supportsToolCalling != null
                    || supportsStreaming != null
                    || supportsVision != null
                    || supportsJsonMode != null
                    || supportsReasoningEffort != null
                    || contextWindowTokens != null
                    || maxOutputTokens != null
                    || apiMode != null;
        }

        public boolean isComplete() {
            return supportsToolCalling != null
                    && supportsStreaming != null
                    && supportsVision != null
                    && supportsJsonMode != null
                    && supportsReasoningEffort != null
                    && contextWindowTokens != null
                    && maxOutputTokens != null
                    && apiMode != null;
        }
    }

    // =========================================================
    // Tools
    // =========================================================
    @Data
    public static class ToolsConfig {
        /**
         * 注意：mcpServers 仍是弱类型 Map<String, Object>，解析由 MCPAdapters 承担。
         * 这意味着配置表达能力更灵活，但编译期约束弱、易传错结构、错误更晚暴露。
         */
        private WebToolsConfig web = new WebToolsConfig();
        private ExecToolConfig exec = new ExecToolConfig();
        private boolean restrictToWorkspace = false;
        private List<String> ssrfWhitelist = new ArrayList<>();
        private Map<String, Object> mcpServers = new LinkedHashMap<>();

        public void setWeb(WebToolsConfig web) {
            this.web = web != null ? web : new WebToolsConfig();
        }

        public void setExec(ExecToolConfig exec) {
            this.exec = exec != null ? exec : new ExecToolConfig();
        }

        public void setSsrfWhitelist(List<String> ssrfWhitelist) {
            this.ssrfWhitelist = ssrfWhitelist != null ? ssrfWhitelist : new ArrayList<>();
        }

        public void setMcpServers(Map<String, Object> mcpServers) {
            this.mcpServers = mcpServers != null ? mcpServers : new LinkedHashMap<>();
        }
    }
    @Data
    public static class MCPServerConfig {
        private String type = "stdio"; // stdio or sse
        private String url; // for sse
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private List<String> enabledTools = new ArrayList<>();
        private int toolTimeout = 60;

        public void setArgs(List<String> args) {
            this.args = args != null ? args : new ArrayList<>();
        }

        public void setEnv(Map<String, String> env) {
            this.env = env != null ? env : new HashMap<>();
        }

        public void setEnabledTools(List<String> enabledTools) {
            this.enabledTools = enabledTools != null ? enabledTools : new ArrayList<>();
        }
    }

    @Data
    public static class WebToolsConfig {
        private boolean enable = true;
        private String proxy;
        private int maxChars = 50000;
        private WebSearchConfig search = new WebSearchConfig();

        public void setSearch(WebSearchConfig search) {
            this.search = search != null ? search : new WebSearchConfig();
        }
    }

    @Data
    public static class WebSearchConfig {
        private String provider = "duckduckgo";
        private String apiKey;
        private String baseUrl;
        private int maxResults = 5;
        private int timeout = 10;

    }

    @Data
    public static class ExecToolConfig {
        /**
         * 兼容说明：
         * - 字段 sandbox 是 boolean，但 getSandbox() 返回 String，是为了兼容历史上 “sandbox 以字符串表示模式” 的使用方式。
         * - 建议新代码优先使用 isSandbox()/setSandbox(boolean)。
         */
        private boolean enable = true;
        private int timeout = 60;
        private boolean sandbox = false;
        private boolean approvalEnabled = true;
        private String backend = "local";
        private String fallbackBackend = "local";
        private boolean allowBackendFallback = false;
        private String dockerImage = "eclipse-temurin:17-jdk";
        private boolean dockerNetworkEnabled = false;
        private String pathAppend = "";
        private List<String> allowedEnvKeys = new ArrayList<>();
        public void setAllowedEnvKeys(List<String> allowedEnvKeys) {
            this.allowedEnvKeys = allowedEnvKeys != null ? allowedEnvKeys : new ArrayList<>();
        }

        public String getSandbox() {
            return sandbox ? "sandbox" : "";
        }

        public boolean isSandbox() {
            return sandbox;
        }
    }

    // =========================================================
    // Channels
    // =========================================================

    @Data
    public static class ChannelsConfig {
        private boolean sendProgress = true;
        private boolean sendToolHints = true;
        private String transcriptionProvider = "groq";
        private int sendMaxRetries = 3;

        private WebSocketChannel.WebSocketConfig websocket = new WebSocketChannel.WebSocketConfig();

        public Object getSection(String name) {
            String key = canonicalChannelName(name);
            if (key == null) {
                return null;
            }
            return switch (key) {
                case "websocket" -> websocket;
                default -> null;
            };
        }

        public boolean isEnabled(String name) {
            Object section = getSection(name);
            if (section instanceof WebSocketChannel.WebSocketConfig c) return c.isEnabled();
            return false;
        }

        private static String canonicalChannelName(String name) {
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

    // =========================================================
    // Gateway / API
    // =========================================================

    @Data
    public static class GatewayConfig {
        private int port = 8000;
    }

    @Getter
    @Setter
    public static class ApiConfig {
        /**
         * API 配置当前为薄壳字段集合。host/port/timeout 的默认值更偏开发环境本地部署。
         */
        private String host = "127.0.0.1";
        private int port = 0;
        private double timeout = 120.0;
        private String bearerToken = "";

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken != null ? bearerToken : "";
        }
    }
}
