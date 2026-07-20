package ricbot.infra.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void providersConfig_supportsKnownAliasesWithoutChangingStoredSection() {
        Config.ProvidersConfig providers = new Config.ProvidersConfig();
        Config.ProviderConfig openai = new Config.ProviderConfig();
        openai.setApiKey("k");

        providers.put("openai_compat", openai);

        assertSame(openai, providers.get("openai"));
        assertSame(openai, providers.get("openai_compat"));
        assertTrue(providers.asMap().containsKey("openai"));
        assertFalse(providers.asMap().containsKey("openai_compat"));
    }

    @Test
    void providersConfig_getOrCreateCanonicalizesUnknownProviders() {
        Config.ProvidersConfig providers = new Config.ProvidersConfig();

        Config.ProviderConfig created = providers.getOrCreate("  MyProvider  ");
        created.setApiBase("https://example.com");

        assertSame(created, providers.get("myprovider"));
        assertSame(created, providers.asMap().get("myprovider"));
    }

    @Test
    void channelsConfig_getSectionAndIsEnabled_areCaseInsensitiveAndNullSafe() {
        Config.ChannelsConfig channels = new Config.ChannelsConfig();
        channels.getWebsocket().setEnabled(true);

        assertSame(channels.getWebsocket(), channels.getSection(" WebSocket "));
        assertTrue(channels.isEnabled("websocket"));
        assertNull(channels.getSection(null));
        assertNull(channels.getSection("   "));
        assertFalse(channels.isEnabled(null));
        assertFalse(channels.isEnabled("unknown"));
    }

    @Test
    void nullSafeLeafConfigSetters_keepDefaultsUsable() {
        Config.ProviderConfig provider = new Config.ProviderConfig();
        provider.setExtraHeaders(null);
        assertNotNull(provider.getExtraHeaders());

        Config.ApiConfig api = new Config.ApiConfig();
        api.setBearerToken(null);
        assertEquals("", api.getBearerToken());
    }
}
