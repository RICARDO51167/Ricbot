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
                    "exec": {"enable": false}
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
                  "tools": {"restrictToWorkspace": true, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("ERROR", report.status());
        assertTrue(report.getErrors().stream().anyMatch(s -> s.contains("MISSING_OPENAI_KEY")), report.getErrors().toString());
        assertFalse(report.isApiKeyPresent());
    }

    @Test
    void restrictToWorkspaceFalse_reportsSecurityWarning(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {"restrictToWorkspace": false, "exec": {"enable": false}}
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
                  "tools": {"restrictToWorkspace": true, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("openai", report.getInferredProvider());
        assertEquals("UNKNOWN", report.getProviderCapability().modelCapability().supportsVision());
        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("推断不明确")), report.getWarnings().toString());
    }

    @Test
    void providerCapability_appliesUserOverrideAndReportsMixedSource(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "qwen-plus", "workspace": "%s"}},
                  "providers": {"dashscope": {"api_key": "sk-test"}},
                  "model_capabilities": {
                    "qwen-plus": {
                      "supportsToolCalling": false,
                      "maxOutputTokens": 8192
                    }
                  },
                  "tools": {"restrictToWorkspace": true, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("false", report.getProviderCapability().modelCapability().supportsToolCalling());
        assertEquals(8192, report.getProviderCapability().modelCapability().maxOutputTokens());
        assertEquals(ProviderCapability.SOURCE_MIXED, report.getProviderCapability().source());
        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("用户 override")), report.getWarnings().toString());
    }

    @Test
    void providerCapability_completeUserOverrideReportsUserOverrideSource(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "private-model", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "model_capabilities": {
                    "private-model": {
                      "supportsToolCalling": true,
                      "supportsStreaming": false,
                      "supportsVision": false,
                      "supportsJsonMode": true,
                      "supportsReasoningEffort": "UNKNOWN",
                      "contextWindowTokens": 4096,
                      "maxOutputTokens": 1024,
                      "apiMode": "openai-compatible"
                    }
                  },
                  "tools": {"restrictToWorkspace": true, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("true", report.getProviderCapability().modelCapability().supportsToolCalling());
        assertEquals("false", report.getProviderCapability().modelCapability().supportsStreaming());
        assertEquals(ProviderCapability.SOURCE_USER_OVERRIDE, report.getProviderCapability().source());
    }

    @Test
    void invalidCapabilityTokenCounts_reportWarning(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "private-model", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "model_capabilities": {
                    "private-model": {
                      "supportsToolCalling": true,
                      "contextWindowTokens": 0,
                      "maxOutputTokens": -3
                    },
                    "unused-model": {
                      "supportsToolCalling": true,
                      "contextWindowTokens": "bad"
                    }
                  },
                  "tools": {"restrictToWorkspace": true, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("contextWindowTokens") && s.contains("正数")), report.getWarnings().toString());
        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("maxOutputTokens") && s.contains("正数")), report.getWarnings().toString());
        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("unused-model") && s.contains("未被默认模型使用")), report.getWarnings().toString());
    }

    @Test
    void sandboxEnabledWithoutSandboxCommand_reportsWarning(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s"}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {
                    "restrictToWorkspace": true,
                    "exec": {"enable": true, "sandbox": true}
                  }
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = new ConfigDoctorService(name -> null, command -> false)
                .diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertTrue(report.getWarnings().stream().anyMatch(s -> s.contains("sandbox-exec") && s.contains("bwrap")), report.getWarnings().toString());
    }

    @Test
    void costBudgetWithoutModelPrice_isRejected(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir, """
                {
                  "agents": {"defaults": {"model": "gpt-4o-mini", "workspace": "%s",
                    "budget": {"max_cost_microusd": 10000}}},
                  "providers": {"openai": {"api_key": "sk-test"}},
                  "tools": {"restrictToWorkspace": true, "exec": {"enable": false}}
                }
                """.formatted(jsonPath(tempDir.resolve("workspace"))));

        ConfigDoctorReport report = doctor().diagnose(ConfigLoader.loadConfig(configPath), configPath);

        assertEquals("ERROR", report.status());
        assertTrue(report.getErrorCodes().contains("MISSING_MODEL_PRICE"), report.getErrors().toString());
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
