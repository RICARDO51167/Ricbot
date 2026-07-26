package ricbot.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java").toAbsolutePath().normalize();

    @Test
    void domainDoesNotDependOnCliOrTransportAdapters() throws Exception {
        assertNoImports(
                MAIN.resolve("ricbot/domain"),
                List.of(
                        "import ricbot.app.cli",
                        "import ricbot.integration.channel"
                )
        );
    }

    @Test
    void productionRuntimeDoesNotDependOnEvalPackage() throws Exception {
        for (String root : List.of(
                "ricbot/domain/agent",
                "ricbot/domain/worker",
                "ricbot/domain/team",
                "ricbot/app/bootstrap",
                "ricbot/tool",
                "ricbot/integration/llm",
                "ricbot/integration/mcp"
        )) {
            assertNoImports(MAIN.resolve(root), List.of("import ricbot.domain.eval"));
        }
    }

    @Test
    void removedRuntimeFrameworksStayRemoved() {
        for (String removed : List.of(
                "ricbot/integration/channel/BaseChannel.java",
                "ricbot/integration/channel/ChannelManager.java",
                "ricbot/integration/channel/ChannelRegistry.java",
                "ricbot/integration/api/RicbotApiAppContext.java",
                "ricbot/integration/api/RicbotApiServer.java",
                "ricbot/integration/api/RicbotApiSupport.java",
                "ricbot/integration/api/RuntimeConstants.java",
                "ricbot/integration/api/console/ConsoleController.java",
                "ricbot/integration/api/RicbotWebUiHandler.java",
                "ricbot/integration/llm/azure/AzureOpenAIProvider.java",
                "ricbot/domain/agent/AgentRunner.java",
                "ricbot/domain/agent/SpawnWorkerService.java",
                "ricbot/tool/agent/SpawnTool.java",
                "ricbot/domain/agent/AutoCompact.java",
                "ricbot/domain/memory/Consolidator.java"
        )) {
            assertFalse(Files.exists(MAIN.resolve(removed)), "removed runtime type returned: " + removed);
        }
        for (String removedDirectory : List.of(
                "ricbot/application/team",
                "ricbot/domain/team",
                "ricbot/domain/worker"
        )) {
            assertFalse(Files.exists(MAIN.resolve(removedDirectory)),
                    "removed orchestration directory returned: " + removedDirectory);
        }
    }

    @Test
    void legacyTeamCommandIsAbsentFromProductionSources() throws Exception {
        assertNoImports(MAIN, List.of("/team"));
    }

    private static void assertNoImports(Path root, List<String> forbidden) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String dependency : forbidden) {
                    assertFalse(
                            source.contains(dependency),
                            file + " imports forbidden runtime dependency " + dependency
                    );
                }
            }
        }
    }
}
