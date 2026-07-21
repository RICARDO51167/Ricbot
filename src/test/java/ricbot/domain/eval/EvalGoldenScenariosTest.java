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

            assertEquals(12, summary.getTotal());
            assertEquals(11, summary.getPassed(), "failures=" + summary.getFailuresByKind() + ", artifacts=" + summary.getArtifactDir());
            assertEquals(0, summary.getFailed(), "failures=" + summary.getFailuresByKind() + ", artifacts=" + summary.getArtifactDir());
            assertEquals(0, summary.getSkipped());
            assertEquals(1, summary.getExpectedFailed());
            assertEquals(0, summary.getUnexpectedPassed());
            assertEquals(21, summary.getTotalModelCalls());
            assertEquals(8, summary.getTotalToolCalls());
            assertEquals(3, summary.getTotalWorkspaceChanges());
            assertTrue(summary.getDurationP50Ms() >= 0);
            assertTrue(summary.getDurationP95Ms() >= summary.getDurationP50Ms());
        } finally {
            loop.stop();
        }
    }

    private static AgentLoop loop(Path workspace, LLMProvider provider) {
        Config config = EvalSmokeRuntime.config(workspace.toString());
        return new AgentLoop(
                new MessageBus(),
                provider,
                workspace,
                config.getAgents().getDefaults().getModel(),
                4,
                8_000,
                24,
                4_000,
                "none",
                config.getTools().getExec(),
                true,
                null,
                "UTC",
                false,
                0
        );
    }

    private static Config config(Path workspace) {
        return EvalSmokeRuntime.config(workspace.toString());
    }

    private static Config.ExecToolConfig execDisabled() {
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        exec.setApprovalEnabled(false);
        return exec;
    }

}
