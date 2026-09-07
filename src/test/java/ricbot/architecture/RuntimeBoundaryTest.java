package ricbot.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import ricbot.domain.runtime.RunState;
import ricbot.domain.runtime.RuntimePhase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNoImports(MAIN, List.of("/team", "team_context", "ModelInvocationConfirmation"));
    }

    @Test
    void durableKernelHasNoAdapterOrConcreteImplementationDependencies() throws Exception {
        assertNoImports(MAIN.resolve("ricbot/domain/runtime"), List.of(
                "import ricbot.app.cli",
                "import ricbot.domain.memory",
                "import ricbot.integration.llm",
                "import ricbot.tool",
                "import ricbot.domain.change",
                "import ricbot.domain.workspace",
                "import org.eclipse.jgit",
                "import java.sql"
        ));
    }

    @Test
    void compiledDurableKernelApiExposesOnlyJdkAndDomainTypes() throws Exception {
        Path classes = Path.of("target/classes/ricbot/domain/runtime").toAbsolutePath().normalize();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.class")).toList()) {
                String relative = classes.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                Class<?> type = Class.forName("ricbot.domain.runtime."
                        + relative.substring(0, relative.length() - ".class".length()));
                for (Field field : type.getDeclaredFields()) assertAllowedApiType(type, field.getType());
                for (Method method : type.getDeclaredMethods()) {
                    assertAllowedApiType(type, method.getReturnType());
                    for (Class<?> parameter : method.getParameterTypes()) assertAllowedApiType(type, parameter);
                }
                for (Executable constructor : type.getDeclaredConstructors()) {
                    for (Class<?> parameter : constructor.getParameterTypes()) assertAllowedApiType(type, parameter);
                }
            }
        }
    }

    @Test
    void runtimeGraphAndVersionAreClosedAndExact() {
        assertEquals(Set.of("INGEST", "CONTEXT", "MODEL", "TOOLS", "WAIT", "COMPACT", "DELEGATE", "TERMINAL"),
                Arrays.stream(RuntimePhase.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals("ricbot-durable-runtime-v6", RunState.GRAPH_VERSION);
    }

    @Test
    void productionArtifactIntegrationUsesTheNeutralPortThroughEffectRuntime() throws Exception {
        String phase = Files.readString(MAIN.resolve(
                "ricbot/application/runtime/ChangeActionPhaseHandler.java"));
        String adapter = Files.readString(MAIN.resolve(
                "ricbot/integration/artifact/GitChangeSetArtifactIntegrator.java"));
        assertTrue(phase.contains("ArtifactDelta delta"));
        assertTrue(phase.contains("ArtifactIntegrator integrator"));
        assertTrue(phase.contains("new EffectRuntime"));
        assertTrue(adapter.contains("implements ArtifactIntegrator"));
    }

    @Test
    void legacyGraphAndTaskKernelTypesStayDeleted() throws Exception {
        assertFalse(Files.exists(MAIN.resolve("ricbot/domain/agent/graph")));
        assertFalse(Files.exists(MAIN.resolve("ricbot/domain/task")));
        try (var files = Files.walk(MAIN)) {
            List<String> forbiddenNames = List.of("TaskStatus.java", "AgentGraphFactory.java",
                    "TeamAgentGraphFactory.java", "UnifiedAgentGraphFactory.java", "AgentRuntime.java");
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(forbiddenNames.contains(file.getFileName().toString()),
                        "removed runtime type returned: " + file);
            }
        }
    }

    @Test
    void ephemeralObservationTypesAreAbsentFromDurableStateAndEvents() throws Exception {
        for (String file : List.of("RunState.java", "RuntimeEvent.java", "ChannelWrite.java")) {
            String source = Files.readString(MAIN.resolve("ricbot/domain/runtime").resolve(file));
            for (String forbidden : List.of("ThinkingDelta", "StreamingDelta", "ToolProgress", "thinkingDelta",
                    "streamingDelta", "toolProgress")) {
                assertFalse(source.contains(forbidden), file + " persists ephemeral observation " + forbidden);
            }
        }
    }

    @Test
    void schemaV3PersistsRequiredRuntimeFacts() throws Exception {
        String source = Files.readString(MAIN.resolve("ricbot/infra/runtime/SqliteDurableRuntimeStore.java"));
        for (String table : List.of("runs", "activations", "channel_writes", "inbox",
                "model_invocations", "effects", "resource_leases", "budget_reservations",
                "run_relations", "runtime_events", "runtime_transactions")) {
            assertTrue(source.contains("CREATE TABLE IF NOT EXISTS " + table), "missing durable table " + table);
        }
        for (String required : List.of("layout_fingerprint", "transaction_sequence")) {
            assertTrue(source.contains(required), "missing schema-v3 field " + required);
        }
        assertTrue(source.contains("ricbot-durable-runtime-v6") ||
                Files.readString(MAIN.resolve("ricbot/domain/runtime/RunState.java"))
                        .contains("ricbot-durable-runtime-v6"));
    }

    @Test
    void phaseExecutorIsOnlyAFixedRouterAndAllHandlersAreExplicit() throws Exception {
        Path runtime = MAIN.resolve("ricbot/application/runtime");
        assertFalse(Files.exists(runtime.resolve("AgentPhaseServices.java")),
                "centralized AgentPhaseServices must stay removed");
        String router = Files.readString(runtime.resolve("AgentPhaseExecutor.java"));
        assertTrue(router.lines().count() < 140, "phase router grew business behavior");
        for (String handler : List.of("IngestPhaseHandler", "ContextCompactPhaseHandler",
                "ModelPhaseHandler", "ToolEffectPhaseHandler", "DelegatePhaseHandler",
                "ChangeActionPhaseHandler")) {
            assertTrue(Files.isRegularFile(runtime.resolve(handler + ".java")),
                    "missing fixed phase handler " + handler);
            assertTrue(router.contains("new " + handler), "phase router does not register " + handler);
        }
    }

    @Test
    void sqliteStoreDelegatesToTransactionAndLedgerComponents() throws Exception {
        Path runtime = MAIN.resolve("ricbot/infra/runtime");
        String store = Files.readString(runtime.resolve("SqliteDurableRuntimeStore.java"));
        for (String component : List.of("SqliteRuntimeTransactionFacade", "SqliteRuntimeJournal",
                "SqliteRuntimeModelLedger", "SqliteRuntimeEffectResourceLedger",
                "SqliteRuntimeBudgetLedger", "SqliteRuntimeForkQueries")) {
            assertTrue(Files.isRegularFile(runtime.resolve(component + ".java")),
                    "missing SQLite component " + component);
            assertTrue(store.contains(component), "store does not delegate to " + component);
        }
        for (String forbidden : List.of("RuntimeReducer", "new RunState", "RunState.initial")) {
            assertFalse(store.contains(forbidden), "SQLite store performs domain reduction: " + forbidden);
        }
    }

    @Test
    void legacySideEffectRuntimeCannotReturn() throws Exception {
        List<String> forbiddenNames = List.of("SideEffectStore.java", "SideEffectRecord.java",
                "SideEffectCoordinator.java", "SideEffectApplicationService.java",
                "AuditedSideEffectStore.java", "SideEffectConfirmationRequiredException.java");
        try (var files = Files.walk(MAIN)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(forbiddenNames.contains(file.getFileName().toString()),
                        "legacy SideEffect type returned: " + file);
                String normalized = file.toString().replace(java.io.File.separatorChar, '/');
                boolean runtimeSource = normalized.contains("/ricbot/application/runtime/")
                        || normalized.contains("/ricbot/infra/runtime/")
                        || normalized.contains("/ricbot/domain/agent/")
                        || normalized.contains("/ricbot/app/cli/");
                if (!runtimeSource || file.endsWith("SqliteRuntimeStore.java")) continue;
                String source = Files.readString(file);
                assertFalse(source.contains("/side-effect"), "legacy SideEffect command returned: " + file);
                assertFalse(source.contains("side_effects"), "legacy SideEffect table returned: " + file);
            }
        }
    }

    @Test
    void cliUsesTheUnifiedEffectAndDurableCancellationCommands() throws Exception {
        String commands = Files.readString(MAIN.resolve("ricbot/domain/agent/AgentCommands.java"));
        assertFalse(commands.contains("/side-effect"));
        assertTrue(commands.contains("/run effect-confirm <runId> <effectId> <succeeded|failed>"));
        int persistCancellation = commands.indexOf("cancellations.cancelSession");
        int interruptFutures = commands.indexOf("activeTaskRemover.apply", persistCancellation);
        assertTrue(persistCancellation >= 0 && interruptFutures > persistCancellation,
                "/stop must persist cancellation before interrupting local Futures");
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

    private static void assertAllowedApiType(Class<?> owner, Class<?> referenced) {
        Class<?> value = referenced;
        while (value.isArray()) value = value.getComponentType();
        if (value.isPrimitive()) return;
        String name = value.getName();
        assertFalse(name.startsWith("ricbot.app.cli.")
                        || name.startsWith("ricbot.domain.memory.")
                        || name.startsWith("ricbot.integration.")
                        || name.startsWith("ricbot.tool.")
                        || name.startsWith("ricbot.domain.change.")
                        || name.startsWith("org.eclipse.jgit.")
                        || name.startsWith("java.sql."),
                owner.getName() + " exposes forbidden API type " + name);
    }
}
