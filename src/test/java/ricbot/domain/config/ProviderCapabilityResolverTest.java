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
    }

    @Test
    void unknownModel_keepsCapabilitiesUnknown() {
        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve("custom", "my-private-model", "http://localhost:9999/v1", null, null);

        assertEquals("UNKNOWN", capability.modelCapability().supportsToolCalling());
        assertEquals("UNKNOWN", capability.modelCapability().supportsStreaming());
        assertEquals("UNKNOWN", capability.modelCapability().supportsVision());
    }

    @Test
    void knownNonChatModel_reportsNoToolCalling() {
        ProviderCapability capability = new ProviderCapabilityResolver()
                .resolve("openai", "text-embedding-3-small", "https://api.openai.com/v1", null, null);

        assertEquals("false", capability.modelCapability().supportsToolCalling());
        assertEquals("false", capability.modelCapability().supportsStreaming());
        assertEquals("false", capability.modelCapability().supportsVision());
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
}
