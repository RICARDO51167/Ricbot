package ricbot.llm.api;

import ricbot.infra.config.Config;
import ricbot.llm.anthropic.AnthropicProvider;
import ricbot.llm.azure.AzureOpenAIProvider;

import java.util.Map;

/**
 * Provider 工厂类
 */
public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static LLMProvider makeProvider(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("Config must not be null");
        }

        String model = config.getAgents().getDefaults().getModel();
        String providerName = config.getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);

        if (spec == null) {
            throw new IllegalStateException("No provider spec found for model: " + model + ", provider=" + providerName);
        }

        Config.ProviderConfig providerConfig = config.getProvider(model);

        String apiKey = providerConfig != null ? providerConfig.getApiKey() : null;
        String apiBase = config.getApiBase(model);
        Map<String, String> extraHeaders = providerConfig != null ? providerConfig.getExtraHeaders() : null;

        String backend = spec.getBackend() != null ? spec.getBackend() : "openai_compat";

        return switch (backend) {
            case "openai_compat" -> buildOpenAICompatProvider(spec, model, apiKey, apiBase, extraHeaders);
            case "anthropic" -> new AnthropicProvider(apiKey, apiBase, model, extraHeaders);
            case "azure_openai" -> new AzureOpenAIProvider(apiKey, apiBase, model);
            default -> throw new IllegalStateException(
                    "Provider backend '" + backend + "' is not supported yet."
            );
        };
    }

    private static LLMProvider buildOpenAICompatProvider(
            ProviderSpec spec,
            String model,
            String apiKey,
            String apiBase,
            Map<String, String> extraHeaders
    ) {
        validateOpenAICompatConfig(spec, model, apiKey, apiBase);

        return new OpenAICompatProvider(
                apiKey,
                apiBase,
                model,
                extraHeaders,
                spec
        );
    }

    /**
     * 对应 Python _make_provider 里的基础校验逻辑
     */
    private static void validateOpenAICompatConfig(
            ProviderSpec spec,
            String model,
            String apiKey,
            String apiBase
    ) {
        if (spec == null) {
            throw new IllegalArgumentException("Provider spec is required");
        }

        // local provider: api_base 或默认 base 可用即可
        if (spec.isLocal()) {
            String effectiveBase = (apiBase != null && !apiBase.isBlank())
                    ? apiBase
                    : spec.getDefaultApiBase();

            if (effectiveBase == null || effectiveBase.isBlank()) {
                throw new IllegalStateException(
                        "Local provider '" + spec.getName() + "' requires api_base or default_api_base"
                );
            }
            return;
        }

        // direct provider: 允许用户完全自定义
        if (spec.isDirect()) {
            return;
        }

        // oauth provider: 不依赖 api key
        if (spec.isOauth()) {
            return;
        }

        // 普通 provider 必须有 key，除非是特殊无 key 本地情况
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + spec.getName() + "'"
            );
        }
    }

    /**
     * 工具方法：根据名称直接拿 spec
     */
    public static ProviderSpec findSpec(String name) {
        return ProviderRegistry.findByName(name);
    }

    /**
     * 工具方法：列出所有 provider 名称
     */
    public static java.util.List<String> listProviderNames() {
        return ProviderRegistry.providerNames();
    }

    /**
     * 判断是不是 OpenAI 兼容 provider
     */
    public static boolean isOpenAICompat(String providerName) {
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        return spec != null && "openai_compat".equalsIgnoreCase(spec.getBackend());
    }

    /**
     * 给 status / 调试用
     */
    public static String describeResolvedProvider(Config config) {
        if (config == null) {
            return "unknown";
        }

        String model = config.getAgents().getDefaults().getModel();
        String providerName = config.getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);

        if (spec == null) {
            return "provider=<unresolved>, model=" + model;
        }

        return "provider=" + spec.getName()
                + ", backend=" + spec.getBackend()
                + ", model=" + model
                + ", apiBase=" + config.getApiBase(model);
    }
}
