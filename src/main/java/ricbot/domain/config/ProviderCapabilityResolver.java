package ricbot.domain.config;

import ricbot.infra.config.Config;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;

import java.util.Locale;

/**
 * 静态/启发式 Provider capability 解析器。
 * <p>
 * 这里不访问真实模型，只给 Config Doctor 与运行时策略提供保守参考。
 */
public final class ProviderCapabilityResolver {
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String UNKNOWN = "UNKNOWN";

    public ProviderCapability resolve(Config config, String providerName, String model) {
        Config.AgentDefaults defaults = config != null && config.getAgents() != null
                ? config.getAgents().getDefaults()
                : new Config.AgentDefaults();
        String apiBase = config != null ? config.getApiBase(model) : null;
        Integer contextWindow = defaults != null ? defaults.getContextWindowTokens() : null;
        Integer maxOutput = defaults != null ? defaults.getMaxTokens() : null;
        return resolve(providerName, model, apiBase, contextWindow, maxOutput);
    }

    public ProviderCapability resolve(
            String providerName,
            String model,
            String apiBase,
            Integer contextWindowTokens,
            Integer maxOutputTokens
    ) {
        String normalizedModel = model != null ? model.trim() : "";
        String lowerModel = normalizedModel.toLowerCase(Locale.ROOT);
        String resolvedProvider = resolveProviderName(providerName, normalizedModel, apiBase);
        ProviderSpec spec = ProviderRegistry.findByName(resolvedProvider);
        String backend = spec != null && spec.getBackend() != null && !spec.getBackend().isBlank()
                ? spec.getBackend()
                : UNKNOWN;

        String toolCalling = UNKNOWN;
        String streaming = UNKNOWN;
        String vision = looksVisionCapable(lowerModel) ? TRUE : UNKNOWN;
        String jsonMode = UNKNOWN;
        String reasoningEffort = UNKNOWN;

        if (isNonChatModel(lowerModel)) {
            toolCalling = FALSE;
            streaming = FALSE;
            vision = FALSE;
            jsonMode = FALSE;
            reasoningEffort = FALSE;
        } else if ("openai_compat".equalsIgnoreCase(backend) || "azure_openai".equalsIgnoreCase(backend)) {
            if (looksKnownOpenAiCompatibleChatModel(lowerModel, resolvedProvider)) {
                toolCalling = TRUE;
                streaming = TRUE;
                jsonMode = TRUE;
            }
            reasoningEffort = supportsOpenAiReasoning(lowerModel) ? TRUE : FALSE;
        } else if ("anthropic".equalsIgnoreCase(backend)) {
            toolCalling = TRUE;
            streaming = TRUE;
            jsonMode = UNKNOWN;
            reasoningEffort = FALSE;
            if (looksAnthropicVisionCapable(lowerModel)) {
                vision = TRUE;
            }
        }

        int contextWindow = contextWindowTokens != null && contextWindowTokens > 0
                ? contextWindowTokens
                : inferContextWindow(lowerModel);
        int maxOutput = maxOutputTokens != null && maxOutputTokens > 0 ? maxOutputTokens : -1;

        return new ProviderCapability(
                resolvedProvider != null && !resolvedProvider.isBlank() ? resolvedProvider : UNKNOWN,
                new ModelCapability(
                        normalizedModel,
                        toolCalling,
                        streaming,
                        vision,
                        jsonMode,
                        reasoningEffort,
                        contextWindow,
                        maxOutput,
                        backend
                )
        );
    }

    public String resolveProviderName(String providerName, String model, String apiBase) {
        if (providerName != null && !providerName.isBlank()) {
            return providerName.trim();
        }
        ProviderSpec byBase = ProviderRegistry.findByBaseKeyword(apiBase);
        if (byBase != null) {
            return byBase.getName();
        }
        ProviderSpec byModel = ProviderRegistry.findByModelKeyword(model);
        if (byModel != null) {
            return byModel.getName();
        }
        return UNKNOWN;
    }

    private static boolean isNonChatModel(String model) {
        return model != null && (model.contains("embedding")
                || model.contains("text-embedding")
                || model.contains("rerank")
                || model.contains("moderation")
                || model.contains("whisper")
                || model.contains("tts")
                || model.contains("speech"));
    }

    private static boolean looksKnownOpenAiCompatibleChatModel(String model, String providerName) {
        if (model == null || model.isBlank()) {
            return false;
        }
        if (model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")) {
            return true;
        }
        if (model.contains("chat")
                || model.contains("qwen")
                || model.contains("deepseek")
                || model.contains("kimi")
                || model.contains("moonshot")
                || model.contains("glm")
                || model.contains("gemini")
                || model.contains("mistral")
                || model.contains("llama")
                || model.contains("doubao")
                || model.contains("ernie")
                || model.contains("yi-")) {
            return true;
        }
        return providerName != null && ("dashscope".equalsIgnoreCase(providerName)
                || "deepseek".equalsIgnoreCase(providerName)
                || "openrouter".equalsIgnoreCase(providerName)
                || "gemini".equalsIgnoreCase(providerName));
    }

    private static boolean supportsOpenAiReasoning(String model) {
        return model != null && (model.startsWith("o1")
                || model.startsWith("o3")
                || model.startsWith("o4")
                || model.contains("reasoner")
                || model.contains("deepseek-r1"));
    }

    private static boolean looksVisionCapable(String model) {
        return model != null && (model.contains("vision")
                || model.contains("vl")
                || model.contains("gpt-4o")
                || model.contains("gemini")
                || model.contains("qwen-vl")
                || model.contains("claude-3"));
    }

    private static boolean looksAnthropicVisionCapable(String model) {
        return model != null && model.contains("claude-3");
    }

    private static int inferContextWindow(String model) {
        if (model == null || model.isBlank()) {
            return -1;
        }
        if (model.contains("claude")) {
            return 200_000;
        }
        if (model.contains("gpt-4o") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")) {
            return 128_000;
        }
        if (model.contains("qwen") || model.contains("deepseek") || model.contains("gemini")) {
            return 128_000;
        }
        return -1;
    }
}
