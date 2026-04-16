package ricbot.integration.llm.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对应 Python: ProviderSpec
 *
 * 主要目标：
 * 1. 描述一个 provider 的元数据
 * 2. 作为 registry 的单条配置项
 */
public class ProviderSpec {

    /**
     * 配置字段名，如 openai / anthropic / dashscope
     */
    private String name;

    /**
     * 用于模型名匹配的关键字
     */
    private List<String> keywords = new ArrayList<>();

    /**
     * API key 对应的环境变量
     */
    private String envKey = "";

    /**
     * 展示名称
     */
    private String displayName = "";

    /**
     * backend 类型:
     * openai_compat / anthropic / azure_openai / openai_codex / github_copilot
     */
    private String backend = "openai_compat";

    /**
     * 额外环境变量映射
     */
    private List<EnvExtra> envExtras = new ArrayList<>();

    /**
     * 是否是 gateway
     */
    private boolean gateway = false;

    /**
     * 是否本地 provider
     */
    private boolean local = false;

    /**
     * 通过 key 前缀检测
     */
    private String detectByKeyPrefix = "";

    /**
     * 通过 api_base 关键字检测
     */
    private String detectByBaseKeyword = "";

    /**
     * 默认 API base
     */
    private String defaultApiBase = "";

    /**
     * 是否发送前剥离 model 前缀
     */
    private boolean stripModelPrefix = false;

    /**
     * 是否支持 max_completion_tokens
     */
    private boolean supportsMaxCompletionTokens = false;

    /**
     * 模型级参数覆盖
     */
    private List<ModelOverride> modelOverrides = new ArrayList<>();

    /**
     * 是否 OAuth provider
     */
    private boolean oauth = false;

    /**
     * 是否 direct provider
     */
    private boolean direct = false;

    /**
     * 是否支持 prompt caching
     */
    private boolean supportsPromptCaching = false;

    public ProviderSpec() {
    }

    public ProviderSpec(
            String name,
            List<String> keywords,
            String envKey,
            String displayName,
            String backend
    ) {
        this.name = name;
        this.keywords = keywords != null ? keywords : new ArrayList<>();
        this.envKey = envKey;
        this.displayName = displayName;
        this.backend = backend != null ? backend : "openai_compat";
    }

    public String getLabel() {
        return (displayName != null && !displayName.isBlank())
                ? displayName
                : (name == null || name.isBlank() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }

    public String getName() {
        return name;
    }

    public ProviderSpec setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public ProviderSpec setKeywords(List<String> keywords) {
        this.keywords = keywords != null ? keywords : new ArrayList<>();
        return this;
    }

    public String getEnvKey() {
        return envKey;
    }

    public ProviderSpec setEnvKey(String envKey) {
        this.envKey = envKey;
        return this;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ProviderSpec setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public String getBackend() {
        return backend;
    }

    public ProviderSpec setBackend(String backend) {
        this.backend = backend;
        return this;
    }

    public List<EnvExtra> getEnvExtras() {
        return envExtras;
    }

    public ProviderSpec setEnvExtras(List<EnvExtra> envExtras) {
        this.envExtras = envExtras != null ? envExtras : new ArrayList<>();
        return this;
    }

    public boolean isGateway() {
        return gateway;
    }

    public ProviderSpec setGateway(boolean gateway) {
        this.gateway = gateway;
        return this;
    }

    public boolean isLocal() {
        return local;
    }

    public ProviderSpec setLocal(boolean local) {
        this.local = local;
        return this;
    }

    public String getDetectByKeyPrefix() {
        return detectByKeyPrefix;
    }

    public ProviderSpec setDetectByKeyPrefix(String detectByKeyPrefix) {
        this.detectByKeyPrefix = detectByKeyPrefix;
        return this;
    }

    public String getDetectByBaseKeyword() {
        return detectByBaseKeyword;
    }

    public ProviderSpec setDetectByBaseKeyword(String detectByBaseKeyword) {
        this.detectByBaseKeyword = detectByBaseKeyword;
        return this;
    }

    public String getDefaultApiBase() {
        return defaultApiBase;
    }

    public ProviderSpec setDefaultApiBase(String defaultApiBase) {
        this.defaultApiBase = defaultApiBase;
        return this;
    }

    public boolean isStripModelPrefix() {
        return stripModelPrefix;
    }

    public ProviderSpec setStripModelPrefix(boolean stripModelPrefix) {
        this.stripModelPrefix = stripModelPrefix;
        return this;
    }

    public boolean isSupportsMaxCompletionTokens() {
        return supportsMaxCompletionTokens;
    }

    public ProviderSpec setSupportsMaxCompletionTokens(boolean supportsMaxCompletionTokens) {
        this.supportsMaxCompletionTokens = supportsMaxCompletionTokens;
        return this;
    }

    public List<ModelOverride> getModelOverrides() {
        return modelOverrides;
    }

    public ProviderSpec setModelOverrides(List<ModelOverride> modelOverrides) {
        this.modelOverrides = modelOverrides != null ? modelOverrides : new ArrayList<>();
        return this;
    }

    public boolean isOauth() {
        return oauth;
    }

    public ProviderSpec setOauth(boolean oauth) {
        this.oauth = oauth;
        return this;
    }

    public boolean isDirect() {
        return direct;
    }

    public ProviderSpec setDirect(boolean direct) {
        this.direct = direct;
        return this;
    }

    public boolean isSupportsPromptCaching() {
        return supportsPromptCaching;
    }

    public ProviderSpec setSupportsPromptCaching(boolean supportsPromptCaching) {
        this.supportsPromptCaching = supportsPromptCaching;
        return this;
    }

    @Override
    public String toString() {
        return "ProviderSpec{" +
                "名称='" + name + '\'' +
                ", 后端='" + backend + '\'' +
                ", 默认API基地址='" + defaultApiBase + '\'' +
                '}';
    }

    /**
     * 对应 Python env_extras 元组项
     */
    public static class EnvExtra {
        private String envName;
        private String envValueTemplate;

        public EnvExtra() {
        }

        public EnvExtra(String envName, String envValueTemplate) {
            this.envName = envName;
            this.envValueTemplate = envValueTemplate;
        }

        public String getEnvName() {
            return envName;
        }

        public void setEnvName(String envName) {
            this.envName = envName;
        }

        public String getEnvValueTemplate() {
            return envValueTemplate;
        }

        public void setEnvValueTemplate(String envValueTemplate) {
            this.envValueTemplate = envValueTemplate;
        }
    }

    /**
     * 对应 Python model_overrides
     */
    public static class ModelOverride {
        private String pattern;
        private Map<String, Object> overrides;

        public ModelOverride() {
        }

        public ModelOverride(String pattern, Map<String, Object> overrides) {
            this.pattern = pattern;
            this.overrides = overrides;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public Map<String, Object> getOverrides() {
            return overrides;
        }

        public void setOverrides(Map<String, Object> overrides) {
            this.overrides = overrides;
        }
    }
}