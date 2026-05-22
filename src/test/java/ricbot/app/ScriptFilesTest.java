package ricbot.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
    }
}
