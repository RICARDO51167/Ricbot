package ricbot.infra.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.GraphExecutionState;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRuntimeMigratorTest {
    @TempDir Path workspace;

    @Test
    void importsValidatesAndArchivesRuntimeV2() throws Exception {
        GraphExecutionState state = GraphExecutionState.initial("legacy-graph", "legacy-run", "node", Map.of());
        Path checkpoint = workspace.resolve(".ricbot/runtime-v2/runs/legacy/checkpoint.json");
        Files.createDirectories(checkpoint.getParent());
        new ObjectMapper().findAndRegisterModules().writeValue(checkpoint.toFile(), state);
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(workspace);

        LegacyRuntimeMigrator.Result result = new LegacyRuntimeMigrator(workspace, runtime).migrateIfNeeded();

        assertTrue(result.imported());
        assertTrue(runtime.replay("legacy-run", Long.MAX_VALUE).projectionMatches());
        assertFalse(Files.exists(workspace.resolve(".ricbot/runtime-v2")));
        assertTrue(Files.isDirectory(result.archive().resolve("runtime-v2")));
        assertFalse(new LegacyRuntimeMigrator(workspace, runtime).migrateIfNeeded().imported());
    }
}
