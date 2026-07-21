package ricbot.domain.eval;

import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.MessageBus;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Console 专用 smoke eval runner。
 * <p>
 * 只运行仓库内固定 golden smoke，不接受外部路径或真实模型 provider。
 */
public class ConsoleEvalSmokeService {
    public static final Path SCENARIOS_PATH = Path.of("evals", "golden.jsonl");
    public static final Path SMOKE_WORKSPACE = Path.of("target", "eval-console-smoke-workspace");

    private final Path consoleWorkspace;

    public ConsoleEvalSmokeService(Path consoleWorkspace) {
        this.consoleWorkspace = consoleWorkspace != null
                ? consoleWorkspace.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), ".ricbot", "workspace").toAbsolutePath().normalize();
    }

    public EvalRunSummary runSmoke() throws Exception {
        Path scenarios = SCENARIOS_PATH.toAbsolutePath().normalize();
        if (!Files.isRegularFile(scenarios)) {
            throw new IllegalStateException("fixed smoke scenarios not found: " + scenarios);
        }
        Path smokeWorkspace = SMOKE_WORKSPACE.toAbsolutePath().normalize();
        Path outputDir = consoleWorkspace.resolve(".ricbot").resolve("evals").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        Config config = EvalSmokeRuntime.config(smokeWorkspace.toString());
        MessageBus bus = new MessageBus();
        EvalRecordingProvider recorder = new EvalRecordingProvider(new EvalSmokeProvider());
        AgentLoop loop = createSmokeLoop(config, bus, recorder);
        EvalOptions options = new EvalOptions()
                .setScenariosPath(scenarios)
                .setOutputDir(outputDir)
                .setSessionPrefix("console-smoke")
                .setRestoreWorkspace(true)
                .setRestoreSession(true);
        try {
            return new EvalHarness(loop, config, recorder).run(options);
        } finally {
            loop.stop();
        }
    }

    private AgentLoop createSmokeLoop(Config config, MessageBus bus, EvalRecordingProvider recorder) {
        Config.AgentDefaults defaults = config.getAgents().getDefaults();
        return new AgentLoop(
                bus,
                recorder,
                config.getWorkspacePath(),
                defaults.getModel(),
                defaults.getMaxToolIterations(),
                defaults.getContextWindowTokens(),
                defaults.getContextBlockLimit(),
                defaults.getMaxToolResultChars(),
                defaults.getProviderRetryMode(),
                config.getTools().getWeb(),
                config.getTools().getExec(),
                config.getTools().getMcpServers(),
                config.getTools().isRestrictToWorkspace(),
                new SessionManager(config.getWorkspacePath()),
                defaults.getTimezone(),
                false,
                defaults.getDisabledSkills(),
                0
        );
    }
}
