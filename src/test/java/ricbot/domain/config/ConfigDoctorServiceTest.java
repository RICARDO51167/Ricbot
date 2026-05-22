package ricbot.domain.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDoctorServiceTest {

    @Test
    void minimalConfig_reportsOk(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {
                    "restrictToWorkspace": true,
                    "web": {"enable": false},
                    "exec": {"enable": false},
                    "mcpServers": {}
                  }
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("OK", report.status());
        assertEquals("openai", report.getInferredProvider());
        assertTrue(report.isApiKeyPresent());
        assertTrue(report.getWarnings().isEmpty(), report.getWarnings().toString());
    }

    @Test
    void missingProviderApiKeyEnv_reportsError(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "${MISSING_OPENAI_KEY}"}},
                  "tools": {"restrictToWorkspace": true, "web": {"enable": false}, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("ERROR", report.status());
        assertTrue(report.getErrors().stream().anyMatch(s -> s.contains("MISSING_OPENAI_KEY")), report.getErrors().toString());
        assertFalse(report.isApiKeyPresent());
    }

    @Test
    void apiPortDifferentFromGateway_reportsEffectiveDifference(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "gateway": {"port": 8000},
                  "api": {"port": 9000},
                  "tools": {"restrictToWorkspace": true, "web": {"enable": false}, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals(9000, report.getEffectivePorts().get("actualApiPort"));
        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("gateway.port") && s.contains("api.port")), report.getWarnings().toString());
    }

    @Test
    void webMaxChars_reportsIgnoredField(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {
                    "restrictToWorkspace": true,
                    "web": {"enable": true, "max_chars": 1234},
                    "exec": {"enable": false}
                  }
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("WARNING", report.status());
        assertTrue(report.getIgnoredFields().stream().anyMatch(s -> s.contains("tools.web.max_chars")), report.getIgnoredFields().toString());
    }

    @Test
    void restrictToWorkspaceFalse_reportsSecurityWarning(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {"restrictToWorkspace": false, "web": {"enable": false}, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("restrictToWorkspace=false")), report.getWarnings().toString());
    }

    @Test
    void providerCapability_fallsBackToUnknownWhereHeuristicCannotKnow(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "my-private-model", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {"restrictToWorkspace": true, "web": {"enable": false}, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("openai", report.getInferredProvider());
        assertEquals("UNKNOWN", report.getProviderCapability().modelCapability().supportsVision());
        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("推断不明确")), report.getWarnings().toString());
    }

    @Test
    void unknownMcpType_reportsWarning(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {
                    "restrictToWorkspace": true,
                    "web": {"enable": false},
                    "exec": {"enable": false},
                    "mcpServers": {"bad": {"type": "mystery", "command": "node"}}
                  }
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("未知 type")), report.getWarnings().toString());
    }

    @Test
    void sandboxEnabledWithoutSandboxCommand_reportsWarning(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {
                    "restrictToWorkspace": true,
                    "web": {"enable": false},
                    "exec": {"enable": true, "sandbox": true}
                  }
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = new ConfigDoctorService(name -> null, command -> false)
                .diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("sandbox-exec") && s.contains("bwrap")), report.getWarnings().toString());
    }

    private static ConfigDoctorService doctor() {
        return new ConfigDoctorService(name -> null, command -> true);
    }

    private static Path writeConfig(Path tempDir, String json) throws Exception {
        Path configPath = tempDir.resolve("ricbot.config.json");
        Files.writeString(configPath, json);
        return configPath;
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }
}
