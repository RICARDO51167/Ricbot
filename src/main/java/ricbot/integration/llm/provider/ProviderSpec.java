package ricbot.integration.llm.provider;

import java.util.ArrayList;
import java.util.List;

/** Runtime metadata that is actually consumed by provider selection and adapters. */
public final class ProviderSpec {
    private String name;
    private List<String> keywords = new ArrayList<>();
    private String envKey = "";
    private String backend = "openai_compat";
    private boolean local;
    private String detectByBaseKeyword = "";
    private String defaultApiBase = "";
    private boolean stripModelPrefix;
    private boolean direct;

    public ProviderSpec(String name, List<String> keywords, String envKey, String backend) {
        this.name = name;
        this.keywords = keywords != null ? new ArrayList<>(keywords) : new ArrayList<>();
        this.envKey = envKey != null ? envKey : "";
        this.backend = backend != null ? backend : "openai_compat";
    }

    public String getName() { return name; }
    public List<String> getKeywords() { return List.copyOf(keywords); }
    public String getEnvKey() { return envKey; }
    public String getBackend() { return backend; }
    public boolean isLocal() { return local; }
    public String getDetectByBaseKeyword() { return detectByBaseKeyword; }
    public String getDefaultApiBase() { return defaultApiBase; }
    public boolean isStripModelPrefix() { return stripModelPrefix; }
    public boolean isDirect() { return direct; }

    public ProviderSpec setLocal(boolean local) {
        this.local = local;
        return this;
    }

    public ProviderSpec setDetectByBaseKeyword(String detectByBaseKeyword) {
        this.detectByBaseKeyword = detectByBaseKeyword != null ? detectByBaseKeyword : "";
        return this;
    }

    public ProviderSpec setDefaultApiBase(String defaultApiBase) {
        this.defaultApiBase = defaultApiBase != null ? defaultApiBase : "";
        return this;
    }

    public ProviderSpec setStripModelPrefix(boolean stripModelPrefix) {
        this.stripModelPrefix = stripModelPrefix;
        return this;
    }

    public ProviderSpec setDirect(boolean direct) {
        this.direct = direct;
        return this;
    }
}

