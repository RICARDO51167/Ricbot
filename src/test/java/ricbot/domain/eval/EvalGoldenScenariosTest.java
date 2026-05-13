package ricbot.domain.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalGoldenScenariosTest {
    @Test
    void goldenScenarios_passWithDeterministicProvider(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);
        Config config = config(workspace);
        EvalRecordingProvider recorder = new EvalRecordingProvider(new EvalSmokeProvider());
        AgentLoop loop = loop(workspace, recorder);
        try {
            EvalRunSummary summary = new EvalHarness(loop, config, recorder).run(new EvalOptions()
                    .setScenariosPath(Path.of("evals/golden.jsonl"))
                    .setOutputDir(Path.of("target", "eval-artifacts", "golden")));

            assertEquals(11, summary.getTotal());
            assertEquals(9, summary.getPassed(), "failures=" + summary.getFailuresByKind() + ", artifacts=" + summary.getArtifactDir());
            assertEquals(0, summary.getFailed(), "failures=" + summary.getFailuresByKind() + ", artifacts=" + summary.getArtifactDir());
            assertEquals(1, summary.getSkipped());
            assertEquals(1, summary.getExpectedFailed());
            assertEquals(0, summary.getUnexpectedPassed());
            assertEquals(16, summary.getTotalModelCalls());
            assertEquals(5, summary.getTotalToolCalls());
            assertEquals(1, summary.getTotalWorkspaceChanges());
            assertTrue(summary.getDurationP50Ms() >= 0);
            assertTrue(summary.getDurationP95Ms() >= summary.getDurationP50Ms());
        } finally {
            loop.stop();
        }
    }

    private static AgentLoop loop(Path workspace, LLMProvider provider) {
        return new AgentLoop(
                new MessageBus(),
                provider,
                workspace,
                "test-model",
                4,
                8_000,
                24,
                4_000,
                "none",
                new Config.WebToolsConfig(),
                execDisabled(),
                Map.of(),
                true,
                null,
                "UTC",
                false,
                List.of(),
                0,
                dreamDisabled()
        );
    }

    private static Config config(Path workspace) {
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("test-model");
        config.getAgents().getDefaults().setDream(dreamDisabled());
        config.getTools().setExec(execDisabled());
        return config;
    }

    private static Config.ExecToolConfig execDisabled() {
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        return exec;
    }

    private static Config.DreamConfig dreamDisabled() {
        Config.DreamConfig dream = new Config.DreamConfig();
        dream.setEnabled(false);
        return dream;
    }
}
