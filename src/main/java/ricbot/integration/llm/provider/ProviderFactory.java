package ricbot.integration.llm.provider;

import ricbot.infra.config.Config;
import ricbot.integration.llm.anthropic.AnthropicProvider;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.openai.OpenAICompatProvider;

import java.util.Map;

/**
 * Provider 工厂类
 */
public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static LLMProvider makeProvider(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }

        String model = config.getAgents().getDefaults().getModel();
        String providerName = config.getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);

        if (spec == null) {
            throw new IllegalStateException("未找到模型对应的 Provider 规格：model=" + model + "，provider=" + providerName);
        }

        Config.ProviderConfig providerConfig = config.getProvider(model);

        String apiKey = providerConfig != null ? providerConfig.getApiKey() : null;
        String apiBase = config.getApiBase(model);
        Map<String, String> extraHeaders = providerConfig != null ? providerConfig.getExtraHeaders() : null;

        String backend = spec.getBackend() != null ? spec.getBackend() : "openai_compat";

        return switch (backend) {
            case "openai_compat" -> buildOpenAICompatProvider(spec, model, apiKey, apiBase, extraHeaders);
            case "anthropic" -> new AnthropicProvider(apiKey, apiBase, model, extraHeaders);
            default -> throw new IllegalStateException(
                    "暂不支持的 Provider 后端：'" + backend + "'"
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

    private static void validateOpenAICompatConfig(
            ProviderSpec spec,
            String model,
            String apiKey,
            String apiBase
    ) {
        if (spec == null) {
            throw new IllegalArgumentException("必须提供 Provider 规格");
        }

        if (spec.isLocal()) {
            String effectiveBase = (apiBase != null && !apiBase.isBlank())
                    ? apiBase
                    : spec.getDefaultApiBase();

            if (effectiveBase == null || effectiveBase.isBlank()) {
                throw new IllegalStateException(
                        "本地 Provider '" + spec.getName() + "' 需要 api_base 或 default_api_base"
                );
            }
            return;
        }

        if (spec.isDirect()) {
            return;
        }

        if (spec.isOauth()) {
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Provider '" + spec.getName() + "' 未配置 API Key"
            );
        }
    }

}
