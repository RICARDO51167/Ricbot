package ricbot.domain.config;

import ricbot.infra.config.Config;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;

import java.util.Locale;
import java.util.Map;

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
        ProviderCapability inferred = resolve(providerName, model, apiBase, contextWindow, maxOutput);
        return applyOverride(inferred, config, providerName, model);
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
        } else if ("openai_compat".equalsIgnoreCase(backend)) {
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

        String source = inferredSource(spec, lowerModel, resolvedProvider, toolCalling, streaming, vision, jsonMode);
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
                ),
                source
        );
    }

    private ProviderCapability applyOverride(ProviderCapability inferred, Config config, String providerName, String model) {
        Config.ModelCapabilityOverride override = findOverride(config, providerName, model);
        if (override == null || !override.hasAnyField() || inferred == null || inferred.modelCapability() == null) {
            return inferred;
        }
        ModelCapability base = inferred.modelCapability();
        ModelCapability merged = new ModelCapability(
                base.model(),
                firstNonNull(override.getSupportsToolCalling(), base.supportsToolCalling()),
                firstNonNull(override.getSupportsStreaming(), base.supportsStreaming()),
                firstNonNull(override.getSupportsVision(), base.supportsVision()),
                firstNonNull(override.getSupportsJsonMode(), base.supportsJsonMode()),
                firstNonNull(override.getSupportsReasoningEffort(), base.supportsReasoningEffort()),
                override.getContextWindowTokens() != null ? override.getContextWindowTokens() : base.contextWindowTokens(),
                override.getMaxOutputTokens() != null ? override.getMaxOutputTokens() : base.maxOutputTokens(),
                firstNonNull(override.getApiMode(), base.apiMode())
        );
        String source = override.isComplete()
                ? ProviderCapability.SOURCE_USER_OVERRIDE
                : ProviderCapability.SOURCE_MIXED;
        return new ProviderCapability(inferred.providerName(), merged, source);
    }

    public Config.ModelCapabilityOverride findOverride(Config config, String providerName, String model) {
        if (config == null || config.getModelCapabilities() == null || config.getModelCapabilities().isEmpty()) {
            return null;
        }
        Map<String, Config.ModelCapabilityOverride> overrides = config.getModelCapabilities();
        for (String key : overrideKeys(providerName, model)) {
            Config.ModelCapabilityOverride override = overrides.get(key);
            if (override != null) {
                return override;
            }
        }
        return null;
    }

    public boolean overrideAppliesTo(String overrideKey, String providerName, String model) {
        if (overrideKey == null || overrideKey.isBlank()) {
            return false;
        }
        for (String key : overrideKeys(providerName, model)) {
            if (overrideKey.trim().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String[] overrideKeys(String providerName, String model) {
        String normalizedModel = model != null ? model.trim() : "";
        String normalizedProvider = providerName != null ? providerName.trim() : "";
        int slash = normalizedModel.indexOf('/');
        String bareModel = slash > 0 ? normalizedModel.substring(slash + 1) : normalizedModel;
        String providerFromModel = slash > 0 ? normalizedModel.substring(0, slash) : normalizedProvider;
        return new String[] {
                normalizedModel,
                bareModel,
                !providerFromModel.isBlank() && !bareModel.isBlank() ? providerFromModel + "/" + bareModel : ""
        };
    }

    private static String firstNonNull(String override, String fallback) {
        return override != null ? override : fallback;
    }

    private static String inferredSource(
            ProviderSpec spec,
            String lowerModel,
            String providerName,
            String toolCalling,
            String streaming,
            String vision,
            String jsonMode
    ) {
        if (spec == null) {
            return ProviderCapability.SOURCE_HEURISTIC;
        }
        if (isNonChatModel(lowerModel)
                || looksKnownOpenAiCompatibleChatModel(lowerModel, providerName)
                || looksAnthropicVisionCapable(lowerModel)
                || looksVisionCapable(lowerModel)
                || TRUE.equals(toolCalling)
                || TRUE.equals(streaming)
                || TRUE.equals(jsonMode)
                || FALSE.equals(vision)) {
            return ProviderCapability.SOURCE_STATIC;
        }
        return ProviderCapability.SOURCE_HEURISTIC;
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
