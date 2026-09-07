package ricbot.infra.config;


import lombok.*;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;

import java.nio.file.Path;
import java.util.*;

/**
 * 统一承载 ricbot 的运行配置。
 *
 * 主要目标：
 * 1. 统一承载全部运行配置
 * 2. 提供按 model 推导 provider 的能力
 * 3. 提供 workspace / api_base / provider config 访问方法
 *
 * 设计说明（重要）：
 * - 这是一个“巨型配置类”：agent/provider/tool 等都在此文件中。
 *   这种写法对“快速跑起来/单文件查配置”很友好，但长期维护会面临可读性差、模块边界模糊、修改冲击面大等问题。
 */
@Data
public class Config {

    /** 代理配置 */
    private AgentsConfig agents = new AgentsConfig();
    /** 提供商配置 */
    private ProvidersConfig providers = new ProvidersConfig();
    /** 工具配置 */
    private ToolsConfig tools = new ToolsConfig();
    private ModelCardsConfig modelCards = new ModelCardsConfig();
    /** 用户声明的模型能力覆盖，不做在线探测。 */
    private Map<String, ModelCapabilityOverride> modelCapabilities = new LinkedHashMap<>();

    public Config() {
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools != null ? tools : new ToolsConfig();
    }
    public void setModelCards(ModelCardsConfig modelCards) {
        this.modelCards = modelCards != null ? modelCards : new ModelCardsConfig();
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
        private int sessionTtlMinutes = 0;
        private BudgetConfig budget = new BudgetConfig();
        private ContextOffloadConfig contextOffload = new ContextOffloadConfig();
        private ContextManagementConfig contextManagement = new ContextManagementConfig();
        private ToolRuntimeConfig toolRuntime = new ToolRuntimeConfig();
        public void setBudget(BudgetConfig budget) { this.budget = budget != null ? budget : new BudgetConfig(); }
        public void setContextOffload(ContextOffloadConfig value) {
            this.contextOffload = value != null ? value : new ContextOffloadConfig();
        }
        public void setContextManagement(ContextManagementConfig value) {
            this.contextManagement = value != null ? value : new ContextManagementConfig();
        }
        public void setToolRuntime(ToolRuntimeConfig value) {
            this.toolRuntime = value != null ? value : new ToolRuntimeConfig();
        }
    }

    @Data
    public static class BudgetConfig {
        private Long maxTotalTokens;
        private Long maxCostMicrousd;
        private Long maxActiveSeconds;
        private Long maxToolCalls;
        private long finalizationTokens = 1024;
        private WorkerBudgetConfig worker = new WorkerBudgetConfig();
        public void setWorker(WorkerBudgetConfig worker) { this.worker = worker != null ? worker : new WorkerBudgetConfig(); }
    }

    @Data
    public static class WorkerBudgetConfig {
        private String allocation = "equal_share";
        private Long maxTotalTokens;
        private Long maxCostMicrousd;
        private Long maxActiveSeconds;
        private Long maxToolCalls;
    }

    @Data
    public static class ContextOffloadConfig {
        private boolean enabled = true;
        private int previewChars = 1200;
        private int readChunkChars = 16000;
        private long maxArtifactBytesPerTool = 67_108_864L;
    }

    @Data
    public static class ContextManagementConfig {
        private double triggerRatio = 0.80;
        private double warningRatio = 0.60;
        private double targetRatio = 0.60;
        private double recentReserveRatio = 0.10;
        private double safetyMarginRatio = 0.05;
        private int timeHintIntervalMinutes = 30;
    }

    @Data
    public static class ToolRuntimeConfig {
        private boolean strictSchema = true;
        private boolean requireReadReceipt = true;
        private int maxParallelReadCalls = 4;
        private boolean externalActionsEnabled = false;
    }

    @Data
    public static class ModelCardsConfig {
        private List<String> paths = new ArrayList<>();
        public void setPaths(List<String> paths) { this.paths = paths != null ? paths : new ArrayList<>(); }
    }

    // =========================================================
    // Providers
    // =========================================================

    public static class ProvidersConfig {
        private final Map<String, ProviderConfig> providers = new LinkedHashMap<>();

        public ProviderConfig get(String name) {
            String key = canonicalName(name);
            return key != null ? providers.get(key) : null;
        }

        public ProviderConfig getOrCreate(String name) {
            String key = canonicalName(name);
            return key != null ? providers.computeIfAbsent(key, ignored -> new ProviderConfig()) : null;
        }

        public void put(String name, ProviderConfig config) {
            String key = canonicalName(name);
            if (key != null) providers.put(key, config != null ? config : new ProviderConfig());
        }

        public Map<String, ProviderConfig> asMap() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(providers));
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
        private ExecToolConfig exec = new ExecToolConfig();
        private boolean restrictToWorkspace = false;

        public void setExec(ExecToolConfig exec) {
            this.exec = exec != null ? exec : new ExecToolConfig();
        }

    }

    @Data
    public static class ExecToolConfig {
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
    }

}
