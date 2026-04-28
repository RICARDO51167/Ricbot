package ricbot.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @AfterEach
    void cleanup() {
        ConfigLoader.setConfigPath(null);
        System.clearProperty("ricbot.config");
    }

    @Test
    void setConfigPath_normalizesConfiguredPath(@TempDir Path tempDir) {
        Path configured = tempDir.resolve("nested").resolve("..").resolve("config.json");

        ConfigLoader.setConfigPath(configured);

        assertEquals(configured.toAbsolutePath().normalize(), ConfigLoader.getConfigPath());
    }

    @Test
    void loadConfig_withInvalidJsonFallsBackToDefault(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("ricbot.config.json");
        Files.writeString(configPath, "{ invalid json");

        Config config = ConfigLoader.loadConfig(configPath);

        assertNotNull(config);
        assertNotNull(config.getAgents());
        assertEquals(new Config().getAgents().getDefaults().getModel(), config.getAgents().getDefaults().getModel());
    }

    @Test
    void saveConfig_withNullConfigWritesDefaultConfig(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("saved-config.json");

        ConfigLoader.saveConfig(null, configPath);

        assertTrue(Files.exists(configPath));
        Config reloaded = ConfigLoader.loadConfig(configPath);
        assertNotNull(reloaded);
        assertEquals(new Config().getAgents().getDefaults().getModel(), reloaded.getAgents().getDefaults().getModel());
    }

    @Test
    void resolveConfigEnvVars_keepsMissingPlaceholdersButResolvesPresentOnes() {
        Config config = new Config();
        config.getProviders().getOpenai().setApiKey("${PATH}");
        config.getChannels().getQq().setAppId("${DEFINITELY_MISSING_RICBOT_ENV}");

        Config resolved = ConfigLoader.resolveConfigEnvVars(config);

        assertNotEquals("${PATH}", resolved.getProviders().getOpenai().getApiKey());
        assertEquals("${DEFINITELY_MISSING_RICBOT_ENV}", resolved.getChannels().getQq().getAppId());
    }
}
