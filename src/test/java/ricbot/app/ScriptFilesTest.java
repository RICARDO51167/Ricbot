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
    void gatewayWebhookDocsAndExamples_existAndUseSafePlaceholders() throws Exception {
        List<Path> docs = List.of(
                Path.of("docs", "gateway", "feishu-webhook.md"),
                Path.of("docs", "gateway", "dingtalk-webhook.md"),
                Path.of("docs", "gateway", "wecom-webhook.md"),
                Path.of("docs", "mcp", "mcp-diagnostics.md")
        );
        for (Path doc : docs) {
            assertTrue(Files.isRegularFile(doc), doc.toString());
            String content = Files.readString(doc);
            assertFalse(content.contains("sk-"), doc.toString());
            assertFalse(content.contains("Bearer "), doc.toString());
            assertFalse(content.contains("api.openai.com"), doc.toString());
        }
        assertTrue(Files.readString(docs.get(0)).contains("POST /webhook/feishu"));
        assertTrue(Files.readString(docs.get(1)).contains("POST /webhook/dingtalk"));
        assertTrue(Files.readString(docs.get(2)).contains("POST /webhook/wecom"));
        assertTrue(Files.readString(docs.get(3)).contains("GET /console/api/mcp/diagnostics"));

        List<Path> examples = List.of(
                Path.of("config", "examples", "feishu-webhook.json"),
                Path.of("config", "examples", "dingtalk-webhook.json"),
                Path.of("config", "examples", "wecom-webhook.json"),
                Path.of("config", "examples", "model-capabilities.json")
        );
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
            assertFalse(content.contains("feishu-token"), example.toString());
            assertFalse(content.contains("ding-secret"), example.toString());
            assertFalse(content.contains("wecom-token"), example.toString());
            assertFalse(content.contains("ricbot-smoke-"), example.toString());
        }
    }

    @Test
    void webhookSmokeScript_hasShebangAndNoRealSecrets() throws Exception {
        Path script = Path.of("scripts", "webhook-smoke.sh");
        assertTrue(Files.isRegularFile(script));
        String content = Files.readString(script);
        assertTrue(content.startsWith("#!/usr/bin/env sh"));
        assertTrue(content.contains("RICBOT_BASE_URL"));
        assertTrue(content.contains("/webhook/feishu"));
        assertTrue(content.contains("/webhook/dingtalk"));
        assertTrue(content.contains("/webhook/wecom"));
        assertFalse(content.contains("sk-"));
        assertFalse(content.contains("Bearer "));
        assertFalse(content.contains("api.openai.com"));
    }
}
