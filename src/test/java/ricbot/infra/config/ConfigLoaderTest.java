package ricbot.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

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
    void saveConfig_setsOwnerOnlyPermissionsWhenSupported(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("saved-config.json");

        ConfigLoader.saveConfig(new Config(), configPath);

        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configPath);
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions
            );
        } catch (UnsupportedOperationException ignored) {
            assertTrue(Files.exists(configPath));
        }
    }

    @Test
    void resolveConfigEnvVars_keepsMissingPlaceholdersButResolvesPresentOnes() {
        Config config = new Config();
        config.getProviders().getOrCreate("openai").setApiKey("${PATH}");

        Config resolved = ConfigLoader.resolveConfigEnvVars(config);

        assertNotEquals("${PATH}", resolved.getProviders().get("openai").getApiKey());
    }

    @Test
    void loadConfig_readsModelCapabilityOverrides(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("capabilities.json");
        Files.writeString(configPath, """
                {
                  "model_capabilities": {
                    "qwen-plus": {
                      "supportsToolCalling": true,
                      "supportsStreaming": "UNKNOWN",
                      "supportsVision": false,
                      "supportsJsonMode": true,
                      "supportsReasoningEffort": false,
                      "contextWindowTokens": 131072,
                      "maxOutputTokens": 8192,
                      "apiMode": "openai-compatible"
                    }
                  }
                }
                """);

        Config config = ConfigLoader.loadConfig(configPath);
        Config.ModelCapabilityOverride override = config.getModelCapabilities().get("qwen-plus");

        assertNotNull(override);
        assertEquals("true", override.getSupportsToolCalling());
        assertEquals("UNKNOWN", override.getSupportsStreaming());
        assertEquals("false", override.getSupportsVision());
        assertEquals(131072, override.getContextWindowTokens());
        assertEquals(8192, override.getMaxOutputTokens());
        assertEquals("openai-compatible", override.getApiMode());
    }

    @Test
    void loadConfig_withoutCapabilityOverridesKeepsOldConfigBehavior(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("old.json");
        Files.writeString(configPath, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini"}},
                  "providers": {"openai": {"api_key": "sk-test"}}
                }
                """);

        Config config = ConfigLoader.loadConfig(configPath);

        assertTrue(config.getModelCapabilities().isEmpty());
        assertEquals("gpt-4o-mini", config.getAgents().getDefaults().getModel());
        assertEquals("sk-test", config.getProviders().get("openai").getApiKey());
    }

    @Test
    void roundTripsExecutionBackendConfiguration(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("execution.json");
        Files.writeString(configPath, """
                {
                  "tools": {
                    "exec": {
                      "backend": "docker",
                      "fallback_backend": "local",
                      "allow_backend_fallback": true,
                      "docker_image": "eclipse-temurin:21-jdk",
                      "docker_network_enabled": false,
                      "approval_enabled": true
                    }
                  }
                }
                """);

        Config loaded = ConfigLoader.loadConfig(configPath);
        Config.ExecToolConfig exec = loaded.getTools().getExec();
        assertEquals("docker", exec.getBackend());
        assertEquals("local", exec.getFallbackBackend());
        assertTrue(exec.isAllowBackendFallback());
        assertEquals("eclipse-temurin:21-jdk", exec.getDockerImage());
        assertFalse(exec.isDockerNetworkEnabled());

        ConfigLoader.saveConfig(loaded, configPath);
        assertEquals("docker", ConfigLoader.loadConfig(configPath).getTools().getExec().getBackend());
    }

    @Test
    void loadConfig_ignoresInvalidCapabilityTokenCounts(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("invalid-capabilities.json");
        Files.writeString(configPath, """
                {
                  "model_capabilities": {
                    "private-model": {
                      "supportsToolCalling": true,
                      "contextWindowTokens": 0,
                      "maxOutputTokens": -1
                    }
                  }
                }
                """);

        Config config = ConfigLoader.loadConfig(configPath);
        Config.ModelCapabilityOverride override = config.getModelCapabilities().get("private-model");

        assertNotNull(override);
        assertEquals("true", override.getSupportsToolCalling());
        assertNull(override.getContextWindowTokens());
        assertNull(override.getMaxOutputTokens());
    }

}
