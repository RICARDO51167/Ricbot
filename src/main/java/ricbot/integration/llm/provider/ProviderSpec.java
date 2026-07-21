package ricbot.integration.llm.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider 规格定义
 */
public class ProviderSpec {

    private String name;

    private List<String> keywords = new ArrayList<>();

    private String envKey = "";

    private String displayName = "";

    private String backend = "openai_compat";

    private List<EnvExtra> envExtras = new ArrayList<>();

    private boolean local = false;

    private String detectByKeyPrefix = "";

    private String detectByBaseKeyword = "";

    private String defaultApiBase = "";

    private boolean stripModelPrefix = false;

    private boolean supportsMaxCompletionTokens = false;

    private List<ModelOverride> modelOverrides = new ArrayList<>();

    private boolean oauth = false;

    private boolean direct = false;

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
