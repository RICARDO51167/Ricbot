package ricbot.app.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.config.ConfigLoader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCommandsTest {

    @AfterEach
    void cleanup() {
        ConfigLoader.setConfigPath(null);
        System.clearProperty("ricbot.config");
    }

    @Test
    void configDoctor_printsHumanReadableReport(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir);

        String out = captureStdout(() -> CliCommands.main(new String[]{
                "config", "doctor", "-c", configPath.toString()
        }));

        assertTrue(out.contains("ricbot config doctor"), out);
        assertTrue(out.contains("status: OK"), out);
        assertTrue(out.contains("provider capability"), out);
        assertTrue(out.contains("supportsToolCalling"), out);
    }

    @Test
    void configDoctorJson_printsReportMap(@TempDir Path tempDir) throws Exception {
        Path configPath = writeConfig(tempDir);

        String out = captureStdout(() -> CliCommands.main(new String[]{
                "config", "doctor", "-c", configPath.toString(), "--json"
        }));

        assertTrue(out.contains("\"status\":\"OK\""), out);
        assertTrue(out.contains("\"providerCapability\""), out);
        assertTrue(out.contains("\"effectivePorts\""), out);
    }

    private static Path writeConfig(Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("ricbot.config.json");
        Files.writeString(configPath, """
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
                """.formatted(tempDir.resolve("workspace").toAbsolutePath().normalize()));
        return configPath;
    }

    private static String captureStdout(ThrowingRunnable runnable) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
