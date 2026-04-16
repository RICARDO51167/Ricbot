package ricbot.integration.llm.provider;

import ricbot.infra.config.Config;
import ricbot.integration.llm.anthropic.AnthropicProvider;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.azure.AzureOpenAIProvider;
import ricbot.integration.llm.openai.OpenAICompatProvider;

import java.util.Map;

/**
 * Provider 工厂类
 */
public final class ProviderFactory {

    // 私有构造函数，防止外部实例化
    private ProviderFactory() {
    }

    /**
     * 根据配置创建对应的 LLM Provider 实例
     *
     * @param config 系统配置对象
     * @return LLMProvider 实例
     */
    public static LLMProvider makeProvider(Config config) {
        // 校验配置对象是否为空
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }

        // 获取默认模型名称
        String model = config.getAgents().getDefaults().getModel();
        // 根据模型名称获取对应的 Provider 名称
        String providerName = config.getProviderName(model);
        // 从注册表中查找对应的 Provider 规格定义
        ProviderSpec spec = ProviderRegistry.findByName(providerName);

        // 如果未找到对应的规格定义，抛出异常
        if (spec == null) {
            throw new IllegalStateException("未找到模型对应的 Provider 规格：model=" + model + "，provider=" + providerName);
        }

        // 获取特定模型的 Provider 配置信息
        Config.ProviderConfig providerConfig = config.getProvider(model);

        // 提取 API Key，如果配置不存在则为 null
        String apiKey = providerConfig != null ? providerConfig.getApiKey() : null;
        // 获取 API 基础地址
        String apiBase = config.getApiBase(model);
        // 提取额外的请求头信息，如果配置不存在则为 null
        Map<String, String> extraHeaders = providerConfig != null ? providerConfig.getExtraHeaders() : null;

        // 确定后端类型，默认为 openai_compat
        String backend = spec.getBackend() != null ? spec.getBackend() : "openai_compat";

        // 根据后端类型创建具体的 Provider 实例
        return switch (backend) {
            case "openai_compat" -> buildOpenAICompatProvider(spec, model, apiKey, apiBase, extraHeaders);
            case "anthropic" -> new AnthropicProvider(apiKey, apiBase, model, extraHeaders);
            case "azure_openai" -> new AzureOpenAIProvider(apiKey, apiBase, model);
            default -> throw new IllegalStateException(
                    "暂不支持的 Provider 后端：'" + backend + "'"
            );
        };
    }

    /**
     * 构建 OpenAI 兼容协议的 Provider 实例
     *
     * @param spec          Provider 规格定义
     * @param model         模型名称
     * @param apiKey        API Key
     * @param apiBase       API 基础地址
     * @param extraHeaders  额外请求头
     * @return OpenAICompatProvider 实例
     */
    private static LLMProvider buildOpenAICompatProvider(
            ProviderSpec spec,
            String model,
            String apiKey,
            String apiBase,
            Map<String, String> extraHeaders
    ) {
        // 验证 OpenAI 兼容配置的有效性
        validateOpenAICompatConfig(spec, model, apiKey, apiBase);

        // 创建并返回 OpenAI 兼容 Provider 实例
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
     * 验证 OpenAI 兼容 Provider 的配置参数
     *
     * @param spec     Provider 规格定义
     * @param model    模型名称
     * @param apiKey   API Key
     * @param apiBase  API 基础地址
     */
    private static void validateOpenAICompatConfig(
            ProviderSpec spec,
            String model,
            String apiKey,
            String apiBase
    ) {
        // 校验规格定义是否存在
        if (spec == null) {
            throw new IllegalArgumentException("必须提供 Provider 规格");
        }

        // 本地 Provider: api_base 或默认 base 可用即可
        if (spec.isLocal()) {
            // 确定有效的 API 基础地址：优先使用传入的 apiBase，否则使用规格中定义的默认值
            String effectiveBase = (apiBase != null && !apiBase.isBlank())
                    ? apiBase
                    : spec.getDefaultApiBase();

            // 如果最终确定的基础地址仍为空，则抛出异常
            if (effectiveBase == null || effectiveBase.isBlank()) {
                throw new IllegalStateException(
                        "本地 Provider '" + spec.getName() + "' 需要 api_base 或 default_api_base"
                );
            }
            return;
        }

        // 直连 Provider: 允许用户完全自定义，无需额外校验
        if (spec.isDirect()) {
            return;
        }

        // OAuth Provider: 不依赖 API Key，无需校验 Key
        if (spec.isOauth()) {
            return;
        }

        // 普通 Provider 必须有 Key，除非是特殊无 Key 本地情况
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Provider '" + spec.getName() + "' 未配置 API Key"
            );
        }
    }

}
