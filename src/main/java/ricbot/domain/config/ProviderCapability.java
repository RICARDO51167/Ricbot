package ricbot.domain.config;

import ricbot.infra.config.Config;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ProviderCapability(String providerName, ModelCapability modelCapability) {
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String UNKNOWN = "UNKNOWN";

    public static ProviderCapability infer(Config config, String providerName, String model) {
        Config.AgentDefaults defaults = config != null && config.getAgents() != null
                ? config.getAgents().getDefaults()
                : new Config.AgentDefaults();
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        String backend = spec != null && spec.getBackend() != null && !spec.getBackend().isBlank()
                ? spec.getBackend()
                : UNKNOWN;
        String normalizedModel = model != null ? model : "";
        String lowerModel = normalizedModel.toLowerCase(Locale.ROOT);

        String toolCalling = UNKNOWN;
        String streaming = UNKNOWN;
        String jsonMode = UNKNOWN;
        String reasoningEffort = UNKNOWN;

        if ("openai_compat".equalsIgnoreCase(backend)) {
            toolCalling = TRUE;
            streaming = TRUE;
            jsonMode = TRUE;
            reasoningEffort = supportsOpenAiReasoning(lowerModel) ? TRUE : FALSE;
        } else if ("anthropic".equalsIgnoreCase(backend)) {
            toolCalling = TRUE;
            streaming = TRUE;
            jsonMode = UNKNOWN;
            reasoningEffort = FALSE;
        } else if ("azure_openai".equalsIgnoreCase(backend)) {
            toolCalling = TRUE;
            streaming = TRUE;
            jsonMode = TRUE;
            reasoningEffort = supportsOpenAiReasoning(lowerModel) ? TRUE : FALSE;
        }

        String vision = looksVisionCapable(lowerModel) ? TRUE : UNKNOWN;
        int contextWindow = defaults.getContextWindowTokens() != null ? defaults.getContextWindowTokens() : -1;
        int maxOutput = defaults.getMaxTokens() > 0 ? defaults.getMaxTokens() : -1;

        return new ProviderCapability(
                providerName != null && !providerName.isBlank() ? providerName : UNKNOWN,
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

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("providerName", providerName);
        map.putAll(modelCapability.toMap());
        return map;
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
}
