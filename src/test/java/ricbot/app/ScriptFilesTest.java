package ricbot.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptFilesTest {

    @Test
    void releaseCheckScript_hasShebangAndNoRealSecrets() throws Exception {
        Path script = Path.of("scripts", "release-check.sh");
        assertTrue(Files.isRegularFile(script));
        String content = Files.readString(script);
        assertTrue(content.startsWith("#!/usr/bin/env sh"));
        assertFalse(content.contains("sk-"));
        assertFalse(content.contains("Bearer "));
        assertFalse(content.contains("api.openai.com"));
        assertTrue(content.contains(".ricbot/eval-baselines/golden"));
        assertTrue(content.contains("## Baseline"));
        assertTrue(content.contains("## Eval Compare"));
        assertTrue(content.contains("## Final Decision"));
        assertTrue(content.contains("warning_reasons"));
        assertTrue(content.contains("config doctor missing API key"));
        assertTrue(content.contains("eval compare has new cases"));
        assertTrue(content.contains("baseline missing"));
        Process process = new ProcessBuilder("sh", "-n", script.toString()).start();
        String stderr = new String(process.getErrorStream().readAllBytes());
        assertTrue(process.waitFor() == 0, stderr);
    }

    @Test
    void evalBaselineScript_supportsCreateAndShow() throws Exception {
        Path script = Path.of("scripts", "eval-baseline.sh");
        assertTrue(Files.isRegularFile(script));
        String content = Files.readString(script);
        assertTrue(content.startsWith("#!/usr/bin/env sh"));
        assertTrue(content.contains("create_baseline"));
        assertTrue(content.contains("show_baseline"));
        assertTrue(content.contains(".ricbot/eval-baselines"));
        assertFalse(content.contains("sk-"));
        assertFalse(content.contains("Bearer "));
        assertFalse(content.contains("api.openai.com"));
    }

    @Test
    void modelCapabilityExample_existsAndUsesSafePlaceholders() throws Exception {
        List<Path> examples = List.of(Path.of("config", "examples", "model-capabilities.json"));
        for (Path example : examples) {
            assertTrue(Files.isRegularFile(example), example.toString());
            String content = Files.readString(example);
            assertTrue(content.contains("\"model_capabilities\""), example.toString());
            assertTrue(content.contains("\"supportsToolCalling\""), example.toString());
            assertTrue(content.contains("\"contextWindowTokens\""), example.toString());
            assertFalse(content.contains("\"bearer_token\""), example.toString());
            assertFalse(content.contains("sk-"), example.toString());
            assertFalse(content.contains("Bearer "), example.toString());
            assertFalse(content.contains("api.openai.com"), example.toString());
        }
    }

}
