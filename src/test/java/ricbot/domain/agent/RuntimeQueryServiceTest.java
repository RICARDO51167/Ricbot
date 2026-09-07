package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.application.runtime.FixedPhaseExecutor;
import ricbot.application.runtime.LocalDurableAgentRuntime;
import ricbot.domain.runtime.RunSpec;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.infra.runtime.SqliteDurableRuntimeStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeQueryServiceTest {
    @Test
    void reportSupportsTerminalRunWithoutWaitReason(@TempDir Path workspace) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(workspace.resolve(".ricbot/runtime.db"));
             LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                     new FixedPhaseExecutor(Map.of(RuntimePhase.MODEL, context ->
                             new ricbot.domain.runtime.PhaseResult(List.of(),
                                     List.of(new RuntimeCommand.Complete(Map.of("ok", true)))))))) {
            runtime.start(new RunSpec("report-run", "", "report-run", "", List.of(),
                    "report", "default", 16, Map.of()));

            Map<String, Object> report = new RuntimeQueryService(workspace, runtime).report("report-run");

            assertNull(report.get("waitReason"));
        }
    }
}
