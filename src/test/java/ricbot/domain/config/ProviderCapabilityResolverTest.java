package ricbot.domain.config;

import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCapabilityResolverTest {

    @Test
    void knownOpenAiCompatibleModel_reportsToolAndStreamingSupport() {
        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve("dashscope", "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1", null, null);

        assertEquals("dashscope", capability.providerName());
        assertEquals("true", capability.modelCapability().supportsToolCalling());
        assertEquals("true", capability.modelCapability().supportsStreaming());
        assertEquals("openai_compat", capability.modelCapability().apiMode());
        assertEquals(ProviderCapability.SOURCE_STATIC, capability.source());
    }

    @Test
    void unknownModel_keepsCapabilitiesUnknown() {
        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve("custom", "my-private-model", "http://localhost:9999/v1", null, null);

        assertEquals("UNKNOWN", capability.modelCapability().supportsToolCalling());
        assertEquals("UNKNOWN", capability.modelCapability().supportsStreaming());
        assertEquals("UNKNOWN", capability.modelCapability().supportsVision());
        assertEquals(ProviderCapability.SOURCE_HEURISTIC, capability.source());
    }

    @Test
    void knownNonChatModel_reportsNoToolCalling() {
        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve("openai", "text-embedding-3-small", "https://api.openai.com/v1", null, null);

        assertEquals("false", capability.modelCapability().supportsToolCalling());
        assertEquals("false", capability.modelCapability().supportsStreaming());
        assertEquals("false", capability.modelCapability().supportsVision());
        assertEquals(ProviderCapability.SOURCE_STATIC, capability.source());
    }

    @Test
    void contextWindow_prefersConfigThenFallsBackToHeuristic() {
        Config config = new Config();
        config.getAgents().getDefaults().setModel("gpt-4o-mini");
        config.getAgents().getDefaults().setContextWindowTokens(32_000);

        ProviderCapability fromConfig = new ProviderCapabilityResolver()
                .resolve(config, "openai", "gpt-4o-mini");
        assertEquals(32_000, fromConfig.modelCapability().contextWindowTokens());

        ProviderCapability fallback = new ProviderCapabilityResolver()
                .resolve("openai", "gpt-4o-mini", "https://api.openai.com/v1", null, null);
        assertTrue(fallback.modelCapability().contextWindowTokens() > 0);
    }

    @Test
    void overrideTrue_coversUnknownCapability() {
        Config config = new Config();
        config.getAgents().getDefaults().setModel("my-private-model");
        Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
        override.setSupportsToolCalling("true");
        config.getModelCapabilities().put("my-private-model", override);

        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve(config, "custom", "my-private-model");

        assertEquals("true", capability.modelCapability().supportsToolCalling());
        assertEquals("UNKNOWN", capability.modelCapability().supportsStreaming());
        assertEquals(ProviderCapability.SOURCE_MIXED, capability.source());
    }

    @Test
    void overrideFalse_coversStaticTrueCapability() {
        Config config = new Config();
        config.getAgents().getDefaults().setModel("qwen-plus");
        Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
        override.setSupportsToolCalling("false");
        config.getModelCapabilities().put("qwen-plus", override);

        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve(config, "dashscope", "qwen-plus");

        assertEquals("false", capability.modelCapability().supportsToolCalling());
        assertEquals("true", capability.modelCapability().supportsStreaming());
        assertEquals(ProviderCapability.SOURCE_MIXED, capability.source());
    }

    @Test
    void partialOverride_keepsOtherInferredFields() {
        Config config = new Config();
        config.getAgents().getDefaults().setModel("qwen-plus");
        Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
        override.setMaxOutputTokens(8192);
        config.getModelCapabilities().put("dashscope/qwen-plus", override);

        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve(config, "dashscope", "qwen-plus");

        assertEquals("true", capability.modelCapability().supportsToolCalling());
        assertEquals(8192, capability.modelCapability().maxOutputTokens());
        assertEquals(ProviderCapability.SOURCE_MIXED, capability.source());
    }

    @Test
    void completeOverride_reportsUserOverrideSource() {
        Config config = new Config();
        config.getAgents().getDefaults().setModel("private-model");
        Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
        override.setSupportsToolCalling("true");
        override.setSupportsStreaming("false");
        override.setSupportsVision("false");
        override.setSupportsJsonMode("true");
        override.setSupportsReasoningEffort("UNKNOWN");
        override.setContextWindowTokens(4096);
        override.setMaxOutputTokens(1024);
        override.setApiMode("openai-compatible");
        config.getModelCapabilities().put("private-model", override);

        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve(config, "custom", "private-model");

        assertEquals("true", capability.modelCapability().supportsToolCalling());
        assertEquals("false", capability.modelCapability().supportsStreaming());
        assertEquals(4096, capability.modelCapability().contextWindowTokens());
        assertEquals(ProviderCapability.SOURCE_USER_OVERRIDE, capability.source());
    }
}
