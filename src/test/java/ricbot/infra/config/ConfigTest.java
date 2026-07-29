package ricbot.infra.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConfigTest {

    @Test
    void providersConfig_getOrCreateCanonicalizesUnknownProviders() {
        Config.ProvidersConfig providers = new Config.ProvidersConfig();

        Config.ProviderConfig created = providers.getOrCreate("  MyProvider  ");
        created.setApiBase("https://example.com");

        assertSame(created, providers.get("myprovider"));
        assertSame(created, providers.asMap().get("myprovider"));
    }

    @Test
    void nullSafeLeafConfigSetters_keepDefaultsUsable() {
        Config.ProviderConfig provider = new Config.ProviderConfig();
        provider.setExtraHeaders(null);
        assertNotNull(provider.getExtraHeaders());
    }
}
