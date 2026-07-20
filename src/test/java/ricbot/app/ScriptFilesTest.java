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
    void mcpDocsAndModelCapabilityExample_existAndUseSafePlaceholders() throws Exception {
        List<Path> docs = List.of(Path.of("docs", "mcp", "mcp-diagnostics.md"));
        for (Path doc : docs) {
            assertTrue(Files.isRegularFile(doc), doc.toString());
            String content = Files.readString(doc);
            assertFalse(content.contains("sk-"), doc.toString());
            assertFalse(content.contains("Bearer "), doc.toString());
            assertFalse(content.contains("api.openai.com"), doc.toString());
        }
        assertTrue(Files.readString(docs.get(0)).contains("GET /console/api/mcp/diagnostics"));

        List<Path> examples = List.of(Path.of("config", "examples", "model-capabilities.json"));
        for (Path example : examples) {
            assertTrue(Files.isRegularFile(example), example.toString());
            String content = Files.readString(example);
            assertTrue(content.contains("\"api\""), example.toString());
            assertTrue(content.contains("\"host\": \"127.0.0.1\""), example.toString());
            assertTrue(content.contains("\"port\": 8000"), example.toString());
            assertTrue(content.contains("\"bearer_token\": \"${RICBOT_API_BEARER_TOKEN}\""), example.toString());
            assertFalse(content.contains("sk-"), example.toString());
            assertFalse(content.contains("Bearer "), example.toString());
            assertFalse(content.contains("api.openai.com"), example.toString());
        }
    }

    @Test
    void teamDemoDocsDocumentWorktreeVerifierLoop() throws Exception {
        List<Path> docs = List.of(
                Path.of("README.md"),
                Path.of("docs", "demo", "end-to-end-coding-agent.md")
        );
        for (Path doc : docs) {
            String content = Files.readString(doc);
            assertTrue(content.contains("/team run 给 README 增加一个很小的说明性修正 --worktree --verify"), doc.toString());
            assertTrue(content.contains("workerStatus=APPLIED"), doc.toString());
            assertTrue(content.contains("verifierStatus=PASS"), doc.toString());
            assertTrue(content.contains("reportHealth=HEALTHY"), doc.toString());
            assertTrue(content.contains("changedFiles=README.md"), doc.toString());
            assertTrue(content.contains("受限 AgentRun"), doc.toString());
            assertTrue(content.contains("AgentRunner"), doc.toString());
            assertFalse(content.contains("sk-"), doc.toString());
            assertFalse(content.contains("Bearer "), doc.toString());
        }
    }
}
