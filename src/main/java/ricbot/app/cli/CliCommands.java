package ricbot.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import ricbot.app.bootstrap.Bootstrapper; // 导入 Bootstrapper 类，用于初始化核心组件
import ricbot.domain.agent.AgentLoop; // 导入 AgentLoop 类，用于运行 Agent 逻辑

import ricbot.domain.message.InboundMessage; // 导入入站消息类
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus; // 导入消息总线类，用于消息传递
import ricbot.domain.message.OutboundMessage; // 导出现站消息类
import ricbot.domain.eval.EvalHarness;
import ricbot.domain.eval.EvalCompareRunner;
import ricbot.domain.eval.EvalComparisonResult;
import ricbot.domain.eval.EvalOptions;
import ricbot.domain.eval.EvalRecordingProvider;
import ricbot.domain.eval.EvalReplayRunner;
import ricbot.domain.eval.EvalRunSummary;
import ricbot.domain.eval.EvalLintResult;
import ricbot.domain.eval.EvalScenarioLinter;
import ricbot.domain.eval.EvalSmokeProvider;
import ricbot.domain.eval.EvalSmokeRuntime;
import ricbot.domain.eval.EvalCaseResult;
import ricbot.domain.eval.EvalMatrixRunner;
import ricbot.domain.eval.EvalMatrixSpec;
import ricbot.domain.eval.EvalModelTarget;
import ricbot.domain.config.ConfigDoctorReport;
import ricbot.domain.config.ConfigDoctorService;
import ricbot.infra.config.Config; // 导入配置类
import ricbot.infra.config.ConfigLoader; // 导入配置加载器
import ricbot.infra.config.RuntimePaths; // 导入运行时路径工具类
import ricbot.integration.llm.provider.ProviderRegistry; // 导入提供商注册表类
import ricbot.integration.llm.provider.ProviderSpec; // 导入提供商规范类
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path; // 导入 Path 类，用于文件路径操作
import java.util.*; // 导入 Java 集合框架
// 导入线程池服务接口
// 导入线程池工厂类


/**
 * 对应 Python: commands.py
 *
 * 主要目标：
 * 1. CLI 根入口
 * 2. 组织 onboard / agent / status / provider 等命令
 * 3. interactive chat 模式
 *
 * 说明：
 * Python 版用 typer + prompt_toolkit + rich。
 * Java 版这里先用最直接的命令分发风格。
 */
public final class CliCommands {

    private static final Bootstrapper BOOTSTRAPPER = new Bootstrapper(); // 静态初始化 Bootstrapper 实例
    private static final Logger log = LoggerFactory.getLogger(CliCommands.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private CliCommands() {
        // 私有构造函数，防止实例化
    }

    /**
     * CLI 主入口方法。
     * 解析命令行参数并分发到对应的子命令处理逻辑。
     *
     * @param args 命令行参数
     * @throws Exception 执行过程中可能抛出的异常
     */
    public static void main(String[] args) throws Exception {
        initLogging(args);
        if (args.length == 0) { // 如果没有提供参数
            printHelp(); // 打印帮助信息
            return; // 退出程序
        }

        List<String> argv = Arrays.asList(args); // 将参数数组转换为列表
        String cmd = argv.get(0); // 获取第一个参数作为命令

        switch (cmd) { // 根据命令进行分发
            case "--version", "-v" -> printVersion(); // 版本命令
            case "onboard" -> onboard(argv.subList(1, argv.size())); // onboarding 命令，传递剩余参数
            case "agent" -> agent(argv.subList(1, argv.size())); // agent 命令，传递剩余参数
            case "config" -> config(argv.subList(1, argv.size()));
            case "eval" -> eval(argv.subList(1, argv.size()));
            case "status" -> status(); // 状态命令
            case "provider" -> provider(argv.subList(1, argv.size())); // 提供商管理命令，传递剩余参数
            case "tools" -> tools(argv.subList(1, argv.size()));
            default -> { // 未知命令
                System.out.println("未知命令：" + cmd); // 打印未知命令提示
                printHelp(); // 打印帮助信息
            }
        }
    }

    private static void config(List<String> args) throws Exception {
        if (args.isEmpty() || !"doctor".equals(args.get(0))) {
            System.out.println("用法：ricbot config doctor [-c config/ricbot.config.json] [--workspace dir] [--json]");
            return;
        }

        List<String> doctorArgs = args.subList(1, args.size());
        String configPath = optionValue(doctorArgs, "--config", "-c");
        String workspace = optionValue(doctorArgs, "--workspace", "-w");
        boolean json = hasFlag(doctorArgs, "--json");

        Config loaded = loadRuntimeConfig(configPath, workspace);
        Path resolvedPath = configPath != null && !configPath.isBlank()
                ? Path.of(configPath).toAbsolutePath().normalize()
                : ConfigLoader.getConfigPath();
        ConfigDoctorReport report = new ConfigDoctorService().diagnose(loaded, resolvedPath);

        if (json) {
            System.out.println(MAPPER.writeValueAsString(report.toMap()));
            return;
        }

        System.out.print(renderConfigDoctorReport(report));
    }

    private static void eval(List<String> args) throws Exception {
        if (!args.isEmpty() && "matrix".equals(args.get(0))) {
            evalMatrix(args.subList(1, args.size()));
            return;
        }
        if (!args.isEmpty() && "lint".equals(args.get(0))) {
            evalLint(args.subList(1, args.size()));
            return;
        }
        if (!args.isEmpty() && "smoke".equals(args.get(0))) {
            evalSmoke(args.subList(1, args.size()));
            return;
        }
        if (!args.isEmpty() && "compare".equals(args.get(0))) {
            evalCompare(args.subList(1, args.size()));
            return;
        }
        if (!args.isEmpty() && "replay".equals(args.get(0))) {
            evalReplay(args.subList(1, args.size()));
            return;
        }
        String scenarios = optionValue(args, "--scenarios", "-s");
        String out = optionValue(args, "--out", "-o");
        String configPath = optionValue(args, "--config", "-c");
        String workspace = optionValue(args, "--workspace", "-w");
        String sessionPrefix = optionValue(args, "--session-prefix", null);
        Integer limit = optionIntValue(args, "--limit", null);
        boolean failFast = hasFlag(args, "--fail-fast");
        boolean allowUnsafeWorkspaceClean = hasFlag(args, "--allow-unsafe-workspace-clean");
        boolean noRestoreWorkspace = hasFlag(args, "--no-restore-workspace");
        boolean noRestoreSession = hasFlag(args, "--no-restore-session");
        List<String> includeTags = optionValues(args, "--tag", "-t");
        List<String> excludeTags = optionValues(args, "--exclude-tag", null);

        if (scenarios == null || scenarios.isBlank()) {
            System.out.println("用法：ricbot eval --scenarios scenarios.jsonl [--out dir] [--config path] [--workspace dir] [--tag name] [--exclude-tag name] [--limit n] [--fail-fast] [--allow-unsafe-workspace-clean] [--no-restore-workspace] [--no-restore-session]");
            System.out.println("场景 JSONL 示例：{\"id\":\"hello\",\"input\":\"say hello\",\"expected_contains\":[\"hello\"]}");
            return;
        }

        Config config = loadRuntimeConfig(configPath, workspace);
        Config resolvedConfig = resolveAndPrintEffectiveConfig(configPath, config);

        MessageBus bus = new MessageBus();
        var provider = BOOTSTRAPPER.createProvider(resolvedConfig);
        EvalRecordingProvider recordingProvider = new EvalRecordingProvider(provider);
        AgentLoop agentLoop = BOOTSTRAPPER.createAgentLoop(resolvedConfig, bus, recordingProvider);

        EvalOptions options = new EvalOptions()
                .setScenariosPath(Path.of(scenarios))
                .setOutputDir(out != null && !out.isBlank() ? Path.of(out) : null)
                .setSessionPrefix(sessionPrefix)
                .setLimit(limit != null ? limit : 0)
                .setFailFast(failFast)
                .setAllowUnsafeWorkspaceClean(allowUnsafeWorkspaceClean)
                .setRestoreWorkspace(!noRestoreWorkspace)
                .setRestoreSession(!noRestoreSession)
                .setIncludeTags(includeTags)
                .setExcludeTags(excludeTags);

        try {
            EvalRunSummary summary = new EvalHarness(agentLoop, resolvedConfig, recordingProvider).run(options);
            System.out.println("ricbot eval");
            System.out.println("run_id: " + summary.getRunId());
            System.out.println("total: " + summary.getTotal());
            System.out.println("passed: " + summary.getPassed());
            System.out.println("failed: " + summary.getFailed());
            System.out.println("skipped: " + summary.getSkipped());
            System.out.println("expected_failed: " + summary.getExpectedFailed());
            System.out.println("unexpected_passed: " + summary.getUnexpectedPassed());
            if (!summary.getFailuresByKind().isEmpty()) {
                System.out.println("failures_by_kind: " + summary.getFailuresByKind());
            }
            System.out.println("artifacts: " + summary.getArtifactDir());
            System.out.println("report: " + Path.of(summary.getArtifactDir()).resolve("report.md"));
            if (summary.getFailed() > 0) {
                System.exit(2);
            }
        } finally {
            agentLoop.stop();
        }
    }

    private static void evalMatrix(List<String> args) throws Exception {
        String specPath = optionValue(args, "--spec", null);
        String scenarios = optionValue(args, "--scenarios", "-s");
        String configPath = optionValue(args, "--config", "-c");
        String workspace = optionValue(args, "--workspace", "-w");
        String out = optionValue(args, "--out", "-o");
        Integer limit = optionIntValue(args, "--limit", null);
        boolean allowUnsafeWorkspaceClean = hasFlag(args, "--allow-unsafe-workspace-clean");
        if (specPath == null || specPath.isBlank() || scenarios == null || scenarios.isBlank()) {
            System.out.println("用法：ricbot eval matrix --spec matrix.json --scenarios scenarios.jsonl [--config path] [--workspace dir] [--out dir] [--limit n]");
            return;
        }

        EvalMatrixSpec spec = MAPPER.readValue(Path.of(specPath).toFile(), EvalMatrixSpec.class);
        Path matrixRoot = out != null && !out.isBlank()
                ? Path.of(out).toAbsolutePath().normalize()
                : Path.of("eval-artifacts", "matrix-" + System.currentTimeMillis()).toAbsolutePath().normalize();
        Files.createDirectories(matrixRoot);
        EvalMatrixRunner.MatrixReport report = new EvalMatrixRunner().run(spec, (target, repetition) -> {
            Config config = loadRuntimeConfig(configPath, workspace);
            config.getAgents().getDefaults().setModel(targetModel(target));
            MessageBus bus = new MessageBus();
            var provider = BOOTSTRAPPER.createProvider(config);
            EvalRecordingProvider recorder = new EvalRecordingProvider(provider);
            AgentLoop loop = BOOTSTRAPPER.createAgentLoop(config, bus, recorder);
            Path cellOut = matrixRoot.resolve(safeMatrixId(target.id())).resolve("run-" + (repetition + 1));
            EvalOptions options = new EvalOptions()
                    .setScenariosPath(Path.of(scenarios))
                    .setOutputDir(cellOut)
                    .setLimit(limit != null ? limit : 0)
                    .setSessionPrefix("matrix:" + safeMatrixId(target.id()) + ":" + repetition)
                    .setAllowUnsafeWorkspaceClean(allowUnsafeWorkspaceClean);
            try {
                EvalRunSummary summary = new EvalHarness(loop, config, recorder).run(options);
                return new EvalMatrixRunner.EvalRunData(summary, readMatrixCases(Path.of(summary.getArtifactDir())));
            } finally {
                loop.stop();
            }
        });
        Files.writeString(matrixRoot.resolve("matrix-report.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        Files.writeString(matrixRoot.resolve("matrix-report.md"), renderMatrixReport(report));
        System.out.println("ricbot eval matrix");
        System.out.println("recommended: " + report.recommendedTargetId());
        System.out.println("cells: " + report.cells().size());
        System.out.println("report: " + matrixRoot.resolve("matrix-report.md"));
    }

    private static List<EvalCaseResult> readMatrixCases(Path artifactDir) throws Exception {
        Path cases = artifactDir.resolve("cases.jsonl");
        if (!Files.isRegularFile(cases)) return List.of();
        List<EvalCaseResult> results = new ArrayList<>();
        for (String line : Files.readAllLines(cases)) {
            if (!line.isBlank()) results.add(MAPPER.readValue(line, EvalCaseResult.class));
        }
        return results;
    }

    private static String targetModel(EvalModelTarget target) {
        if (target.model().contains("/") || target.provider().isBlank()) return target.model();
        return target.provider() + "/" + target.model();
    }

    private static String safeMatrixId(String value) {
        String safe = value != null ? value.replaceAll("[^A-Za-z0-9._-]+", "-") : "";
        return safe.isBlank() ? "target" : safe;
    }

    private static String renderMatrixReport(EvalMatrixRunner.MatrixReport report) {
        StringBuilder out = new StringBuilder("# Ricbot Eval Matrix\n\n");
        out.append("Recommended: `").append(report.recommendedTargetId()).append("`\n\n");
        out.append("| Target | Pass rate | P95 ms | Cost USD | Long trajectory |\n");
        out.append("|---|---:|---:|---:|---:|\n");
        for (EvalMatrixRunner.CellReport cell : report.cells()) {
            out.append("| ").append(cell.target().id())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f%%", cell.passRate() * 100d))
                    .append(" | ").append(cell.durationP95Ms())
                    .append(" | ").append(String.format(Locale.ROOT, "%.6f", cell.estimatedCostUsd()))
                    .append(" | ").append(cell.longTrajectoryPassed()).append("/").append(cell.longTrajectoryCases())
                    .append(" |\n");
        }
        return out.toString();
    }

    private static void evalLint(List<String> args) throws Exception {
        String scenarios = optionValue(args, "--scenarios", "-s");
        String out = optionValue(args, "--out", "-o");
        if (scenarios == null || scenarios.isBlank()) {
            System.out.println("用法：ricbot eval lint --scenarios scenarios.jsonl [--out dir]");
            return;
        }

        EvalLintResult result = new EvalScenarioLinter().lint(
                Path.of(scenarios),
                out != null && !out.isBlank() ? Path.of(out) : null
        );
        System.out.println("ricbot eval lint");
        System.out.println("status: " + result.getStatus());
        System.out.println("total_scenarios: " + result.getTotalScenarios());
        System.out.println("errors: " + result.getErrors());
        System.out.println("warnings: " + result.getWarnings());
        System.out.println("artifacts: " + result.getArtifactDir());
        System.out.println("report: " + Path.of(result.getArtifactDir()).resolve("lint-report.md"));
        if (result.getErrors() > 0) {
            System.exit(2);
        }
    }

    private static void evalSmoke(List<String> args) throws Exception {
        String scenarios = optionValue(args, "--scenarios", "-s");
        String out = optionValue(args, "--out", "-o");
        String workspace = optionValue(args, "--workspace", "-w");
        Integer limit = optionIntValue(args, "--limit", null);
        boolean failFast = hasFlag(args, "--fail-fast");
        boolean noRestoreWorkspace = hasFlag(args, "--no-restore-workspace");
        boolean noRestoreSession = hasFlag(args, "--no-restore-session");
        List<String> includeTags = optionValues(args, "--tag", "-t");
        List<String> excludeTags = optionValues(args, "--exclude-tag", null);

        if (scenarios == null || scenarios.isBlank()) {
            scenarios = "evals/golden.jsonl";
        }
        if (workspace == null || workspace.isBlank()) {
            workspace = EvalSmokeRuntime.DEFAULT_WORKSPACE;
        }

        Config config = EvalSmokeRuntime.config(workspace);

        MessageBus bus = new MessageBus();
        EvalRecordingProvider recorder = new EvalRecordingProvider(new EvalSmokeProvider());
        AgentLoop agentLoop = BOOTSTRAPPER.createAgentLoop(config, bus, recorder);
        EvalOptions options = new EvalOptions()
                .setScenariosPath(Path.of(scenarios))
                .setOutputDir(out != null && !out.isBlank() ? Path.of(out) : EvalSmokeRuntime.DEFAULT_ARTIFACTS)
                .setLimit(limit != null ? limit : 0)
                .setFailFast(failFast)
                .setRestoreWorkspace(!noRestoreWorkspace)
                .setRestoreSession(!noRestoreSession)
                .setIncludeTags(includeTags)
                .setExcludeTags(excludeTags);
        try {
            EvalRunSummary summary = new EvalHarness(agentLoop, config, recorder).run(options);
            System.out.println("ricbot eval smoke");
            System.out.println("run_id: " + summary.getRunId());
            System.out.println("total: " + summary.getTotal());
            System.out.println("passed: " + summary.getPassed());
            System.out.println("failed: " + summary.getFailed());
            System.out.println("skipped: " + summary.getSkipped());
            System.out.println("expected_failed: " + summary.getExpectedFailed());
            System.out.println("unexpected_passed: " + summary.getUnexpectedPassed());
            if (!summary.getFailuresByKind().isEmpty()) {
                System.out.println("failures_by_kind: " + summary.getFailuresByKind());
            }
            System.out.println("artifacts: " + summary.getArtifactDir());
            System.out.println("report: " + Path.of(summary.getArtifactDir()).resolve("report.md"));
            if (summary.getFailed() > 0) {
                System.exit(2);
            }
        } finally {
            agentLoop.stop();
        }
    }

    private static void evalCompare(List<String> args) throws Exception {
        String baseline = optionValue(args, "--baseline", null);
        String candidate = optionValue(args, "--candidate", null);
        String out = optionValue(args, "--out", "-o");

        if (baseline == null || baseline.isBlank() || candidate == null || candidate.isBlank()) {
            System.out.println("用法：ricbot eval compare --baseline path/to/old-run --candidate path/to/new-run [--out dir]");
            return;
        }

        EvalComparisonResult result = new EvalCompareRunner().compare(
                Path.of(baseline),
                Path.of(candidate),
                out != null && !out.isBlank() ? Path.of(out) : null
        );
        System.out.println("ricbot eval compare");
        System.out.println("status: " + result.getStatus());
        System.out.println("baseline_total: " + result.getBaselineTotal());
        System.out.println("candidate_total: " + result.getCandidateTotal());
        System.out.println("regressions: " + result.getRegressions());
        System.out.println("improvements: " + result.getImprovements());
        System.out.println("missing_cases: " + result.getMissingCases());
        System.out.println("new_cases: " + result.getNewCases());
        System.out.println("artifacts: " + result.getArtifactDir());
        System.out.println("report: " + Path.of(result.getArtifactDir()).resolve("comparison-report.md"));
        if (result.getRegressions() > 0) {
            System.exit(2);
        }
    }

    private static void evalReplay(List<String> args) throws Exception {
        String casePath = optionValue(args, "--case", null);
        String runDir = optionValue(args, "--run", null);
        String out = optionValue(args, "--out", "-o");
        String configPath = optionValue(args, "--config", "-c");
        String workspace = optionValue(args, "--workspace", "-w");
        Integer limit = optionIntValue(args, "--limit", null);
        boolean failFast = hasFlag(args, "--fail-fast");
        boolean allowUnsafeWorkspaceClean = hasFlag(args, "--allow-unsafe-workspace-clean");
        boolean noRestoreWorkspace = hasFlag(args, "--no-restore-workspace");
        boolean noRestoreSession = hasFlag(args, "--no-restore-session");

        if ((casePath == null || casePath.isBlank()) && (runDir == null || runDir.isBlank())) {
            System.out.println("用法：ricbot eval replay --case path/to/cases/foo.json [--out dir] [--config path] [--workspace dir] [--allow-unsafe-workspace-clean] [--no-restore-workspace] [--no-restore-session]");
            System.out.println("或：ricbot eval replay --run path/to/eval-run-dir [--limit n] [--fail-fast] [--allow-unsafe-workspace-clean] [--no-restore-workspace] [--no-restore-session]");
            return;
        }

        boolean smokeReplay = isSmokeReplay(runDir, casePath);
        Config resolvedConfig;
        if (smokeReplay) {
            resolvedConfig = EvalSmokeRuntime.config(workspace);
        } else {
            Config config = loadRuntimeConfig(configPath, workspace);
            resolvedConfig = resolveAndPrintEffectiveConfig(configPath, config);
        }
        List<Path> cases = EvalReplayRunner.resolveCaseArtifacts(
                casePath != null && !casePath.isBlank() ? Path.of(casePath) : null,
                runDir != null && !runDir.isBlank() ? Path.of(runDir) : null,
                limit != null ? limit : 0
        );

        EvalOptions options = new EvalOptions()
                .setOutputDir(out != null && !out.isBlank() ? Path.of(out) : null)
                .setLimit(limit != null ? limit : 0)
                .setFailFast(failFast)
                .setAllowUnsafeWorkspaceClean(allowUnsafeWorkspaceClean)
                .setRestoreWorkspace(!noRestoreWorkspace)
                .setRestoreSession(!noRestoreSession);

        EvalReplayRunner replayRunner = new EvalReplayRunner(
                resolvedConfig,
                provider -> BOOTSTRAPPER.createAgentLoop(resolvedConfig, new MessageBus(), provider)
        );
        EvalRunSummary summary = replayRunner.replay(cases, options);
        System.out.println("ricbot eval replay");
        System.out.println("run_id: " + summary.getRunId());
        System.out.println("total: " + summary.getTotal());
        System.out.println("passed: " + summary.getPassed());
        System.out.println("failed: " + summary.getFailed());
        System.out.println("skipped: " + summary.getSkipped());
        System.out.println("expected_failed: " + summary.getExpectedFailed());
        System.out.println("unexpected_passed: " + summary.getUnexpectedPassed());
        if (!summary.getFailuresByKind().isEmpty()) {
            System.out.println("failures_by_kind: " + summary.getFailuresByKind());
        }
        System.out.println("artifacts: " + summary.getArtifactDir());
        System.out.println("report: " + Path.of(summary.getArtifactDir()).resolve("report.md"));
        if (summary.getFailed() > 0) {
            System.exit(2);
        }
    }

    private static boolean isSmokeReplay(String runDir, String casePath) {
        Path manifestPath = replayManifestPath(runDir, casePath);
        if (manifestPath == null) {
            return false;
        }
        if (!Files.exists(manifestPath)) {
            return false;
        }
        try {
            Map<?, ?> manifest = MAPPER.readValue(manifestPath.toFile(), Map.class);
            Object providerMode = manifest.get("provider_mode");
            return EvalSmokeRuntime.PROVIDER_MODE.equals(String.valueOf(providerMode));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path replayManifestPath(String runDir, String casePath) {
        if (runDir != null && !runDir.isBlank()) {
            return Path.of(runDir).resolve("manifest.json");
        }
        if (casePath == null || casePath.isBlank()) {
            return null;
        }
        Path parent = Path.of(casePath).toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getParent() == null) {
            return null;
        }
        return parent.getParent().resolve("manifest.json");
    }

    private static void initLogging(String[] args) {
        RuntimePaths.configureWorkspaceLogFile(RuntimePaths.workspaceOption(args), null);
    }

    // =========================================================
    // onboard
    // =========================================================

    /**
     * 初始化配置向导或加载现有配置。
     * 支持指定工作空间、配置文件路径，并可启动交互式向导。
     *
     * @param args 命令行参数列表
     */
    private static void onboard(List<String> args) {
        String workspace = optionValue(args, "--workspace", "-w"); // 获取工作空间参数
        String configPath = optionValue(args, "--config", "-c"); // 获取配置文件路径参数

        Config config; // 声明配置对象
        Path resolvedConfigPath; // 声明解析后的配置路径

        if (configPath != null) { // 如果指定了配置路径
            resolvedConfigPath = Path.of(configPath).toAbsolutePath().normalize(); // 解析为绝对路径并规范化
            ConfigLoader.setConfigPath(resolvedConfigPath); // 设置配置加载器的路径
            System.out.println("使用配置文件：" + resolvedConfigPath); // 打印使用的配置路径
        } else { // 如果未指定配置路径
            resolvedConfigPath = ConfigLoader.getConfigPath(); // 获取默认配置路径
        }

        if (resolvedConfigPath.toFile().exists()) { // 如果配置文件存在
            config = ConfigLoader.loadConfig(resolvedConfigPath); // 加载现有配置
        } else { // 如果配置文件不存在
            config = new Config(); // 创建新的配置对象
        }

        if (workspace != null && !workspace.isBlank()) { // 如果指定了工作空间且不为空
            config.getAgents().getDefaults().setWorkspace(workspace); // 设置默认工作空间
        }

        OnboardWizard.OnboardResult result = OnboardWizard.runOnboard(config); // 运行交互式向导
        if (result.shouldSave()) {
            Config finalConfig = result.config();
            ConfigLoader.saveConfig(finalConfig, resolvedConfigPath); // 保存配置到文件

            Path workspacePath = RuntimePaths.getWorkspacePath(finalConfig.getAgents().getDefaults().getWorkspace()); // 获取工作空间路径
            System.out.println("工作区：" + workspacePath); // 打印工作空间路径
            System.out.println("配置已保存到：" + resolvedConfigPath);
            System.out.println("ricbot 已就绪！"); // 打印就绪信息
            return;
        }

        System.out.println("已取消，配置未保存。");
    }

    // =========================================================
    // agent
    // =========================================================

    /**
     * 运行 Agent 交互模式或直接处理单条消息。
     *
     * @param args 命令行参数，支持 --message, --session, --config, --workspace, --no-markdown
     * @throws Exception 执行 Agent 逻辑时可能抛出的异常
     */
    private static void agent(List<String> args) throws Exception {
        String message = optionValue(args, "--message", "-m"); // 获取消息内容参数
        String sessionId = optionValue(args, "--session", "-s"); // 获取会话 ID 参数
        String configPath = optionValue(args, "--config", "-c"); // 获取配置文件路径参数
        String workspace = optionValue(args, "--workspace", "-w"); // 获取工作空间参数
        boolean markdown = !hasFlag(args, "--no-markdown"); // 检查是否禁用 Markdown 渲染，默认为 true

        if (sessionId == null || sessionId.isBlank()) { // 如果会话 ID 为空
            sessionId = "cli:direct"; // 设置默认会话 ID
        }

        Config config = loadRuntimeConfig(configPath, workspace); // 加载运行时配置
        Config resolvedConfig = resolveAndPrintEffectiveConfig(configPath, config); // 解析并打印生效的配置

        MessageBus bus = new MessageBus(); // 创建消息总线实例
        var provider = BOOTSTRAPPER.createProvider(resolvedConfig); // 创建 LLM 提供商实例

        AgentLoop agentLoop = BOOTSTRAPPER.createAgentLoop(resolvedConfig, bus, provider); // 创建 Agent 循环实例

        if (message != null && !message.isBlank()) { // 如果提供了消息内容（非交互模式）
            StreamRenderer renderer = new StreamRenderer(markdown, true); // 创建流式渲染器
            try {
                OutboundMessage response = runSingleMessageViaBus(agentLoop, bus, message, sessionId, "cli", "direct", renderer); // 通过消息总线运行单条消息
                if (!renderer.isStreamed()) { // 如果没有进行流式输出
                    renderer.close(); // 关闭渲染器
                    printAgentResponse(
                            response != null ? response.getContent() : "", // 获取响应内容
                            markdown, // Markdown 渲染标志
                            response != null ? response.getMetadata() : null // 获取元数据
                    );
                }
                return; // 结束方法
            } finally {
                renderer.close(); // 确保渲染器被关闭
            }
        }

        interactiveAgent(agentLoop, bus, sessionId, markdown); // 进入交互式 Agent 模式
    }

    /**
     * 启动交互式 Agent 聊天会话。
     *
     * @param agentLoop Agent 循环实例
     * @param bus       消息总线
     * @param sessionId 会话 ID
     * @param markdown  是否渲染 Markdown
     * @throws Exception 交互过程中可能抛出的异常
     */
    private static void interactiveAgent(AgentLoop agentLoop, MessageBus bus, String sessionId, boolean markdown) throws Exception {
        Scanner scanner = new Scanner(System.in); // 创建扫描器用于读取用户输入

        String cliChannel; // 声明 CLI 通道
        String cliChatId; // 声明 CLI 聊天 ID
        if (sessionId.contains(":")) { // 如果会话 ID 包含冒号
            String[] parts = sessionId.split(":", 2); // 分割会话 ID
            cliChannel = parts[0]; // 获取通道部分
            cliChatId = parts[1]; // 获取聊天 ID 部分
        } else { // 如果会话 ID 不包含冒号
            cliChannel = "cli"; // 默认通道为 cli
            cliChatId = sessionId; // 聊天 ID 为整个会话 ID
        }

        agentLoop.start();

        try {
            System.out.println("交互模式（输入 exit 或按 Ctrl+C 退出）"); // 打印交互模式提示

            while (true) { // 主循环
                System.out.print("你："); // 打印提示符
                String input = safeReadLine(scanner); // 安全读取一行输入
                if (input == null) { // 如果读取失败（例如 EOF）
                    System.out.println("再见！"); // 打印告别信息
                    break; // 退出循环
                }

                String command = input.trim(); // 去除输入两端空白
                if (command.isBlank()) { // 如果输入为空
                    continue; // 跳过本次循环
                }
                if (isExitCommand(command)) { // 如果是退出命令
                    System.out.println("再见！"); // 打印告别信息
                    break; // 退出循环
                }

                StreamRenderer renderer = new StreamRenderer(markdown, true); // 创建流式渲染器

                InboundMessage inbound = InboundMessages.of(
                        cliChannel,
                        "user",
                        cliChatId,
                        input,
                        List.of(),
                        Map.of("_wants_stream", true),
                        null,
                        null
                );

                bus.publishInbound(inbound); // 发布入站消息到总线

                // 这里做一个最简版 outbound 消费
                while (true) { // 内部循环，等待出站消息
                    OutboundMessage msg = bus.pollOutbound(1000); // 轮询出站消息，超时 1000ms
                    if (msg == null) { // 如果超时
                        continue; // 继续等待
                    }

                    Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap(); // 获取元数据

                    if (Boolean.TRUE.equals(meta.get("_stream_delta"))) { // 如果是流式增量
                        renderer.onDelta(msg.getContent()); // 渲染增量内容
                        continue; // 继续等待下一条消息
                    }

                    if (Boolean.TRUE.equals(meta.get("_stream_end"))) { // 如果是流式结束
                        renderer.onEnd(Boolean.TRUE.equals(meta.get("_resuming"))); // 处理流式结束
                        continue; // 继续等待下一条消息
                    }

                    if (Boolean.TRUE.equals(meta.get("_streamed"))) { // 如果标记为已流式传输完成
                        break; // 跳出内部循环
                    }

                    if (Boolean.TRUE.equals(meta.get("_progress"))) { // 如果是进度消息
                        continue; // 忽略进度消息
                    }

                    if (msg.getContent() != null && !msg.getContent().isBlank()) { // 如果有非空内容且非流式
                        if (!renderer.isStreamed()) { // 如果之前没有进行流式输出
                            renderer.close(); // 关闭渲染器
                            printAgentResponse(msg.getContent(), markdown, msg.getMetadata()); // 打印完整响应
                        }
                        break; // 跳出内部循环
                    }
                }
            }
        } finally {
            agentLoop.stop();
        }
    }

    // =========================================================
    // status
    // =========================================================

    /**
     * 显示当前 ricbot 的状态信息（配置、工作空间、模型等）。
     */
    private static void status() {
        Config config = ConfigLoader.loadConfig(); // 加载配置
        Path configPath = ConfigLoader.getConfigPath(); // 获取配置路径
        Path workspace = config.getWorkspacePath(); // 获取工作空间路径

        System.out.println("ricbot 状态"); // 打印标题
        System.out.println("配置文件：" + configPath + (configPath.toFile().exists() ? " ✓" : " ✗")); // 打印配置状态
        System.out.println("工作区：" + workspace + (workspace.toFile().exists() ? " ✓" : " ✗")); // 打印工作空间状态
        System.out.println("模型：" + config.getAgents().getDefaults().getModel()); // 打印默认模型
    }

    /**
     * 显示当前已注册和启用的工具列表及其配置信息。
     * 支持指定配置文件和工作空间，解析环境变量，并根据安全策略初始化文件系统工具和执行工具。
     *
     * @param args 命令行参数，支持 --config, --workspace
     */
    private static void tools(List<String> args) {
        // 从命令行参数中获取配置文件路径
        String configPath = optionValue(args, "--config", "-c");
        // 从命令行参数中获取工作空间覆盖路径
        String workspaceOverride = optionValue(args, "--workspace", "-w");

        // 加载运行时配置
        Config config = loadRuntimeConfig(configPath, workspaceOverride);
        // 声明解析后的配置对象，初始化为原始配置
        Config resolved = config;
        // 标记环境变量是否成功解析
        boolean envResolved = false;
        try {
            // 尝试解析配置中的环境变量占位符
            resolved = ConfigLoader.resolveConfigEnvVars(config);
            // 标记解析成功
            envResolved = true;
        } catch (Exception ignored) {
            // 忽略解析异常，保持原配置
        }

        Path workspace = resolved.getWorkspacePath();
        boolean restrictToWorkspace = resolved.getTools().isRestrictToWorkspace();
        Config.ExecToolConfig execConfig = resolved.getTools().getExec() != null ? resolved.getTools().getExec() : new Config.ExecToolConfig();

        Path allowedDir = BuiltinToolRegistrar.allowedDir(workspace, restrictToWorkspace, execConfig);

        ToolRegistry registry = new ToolRegistry();
        BuiltinToolRegistrar.registerFileAndSearchTools(registry, workspace, allowedDir);
        BuiltinToolRegistrar.registerExecTool(registry, workspace, restrictToWorkspace, execConfig);

        // 打印标题
        System.out.println("ricbot 工具");
        // 打印工作区路径
        System.out.println("工作区：" + workspace);
        // 打印是否限制在工作空间
        System.out.println("restrictToWorkspace：" + restrictToWorkspace);
        // 打印允许的基目录
        System.out.println("允许的基目录：" + (allowedDir != null ? allowedDir : "（不限制）"));
        // 打印执行工具的详细配置
        System.out.println("exec：enable=" + execConfig.isEnable()
                + ", sandbox=" + execConfig.isSandbox()
                + ", timeout=" + execConfig.getTimeout()
                + ", allowed_env_keys=" + (execConfig.getAllowedEnvKeys() != null ? execConfig.getAllowedEnvKeys().size() : 0));
        // 打印环境变量解析状态
        System.out.println("配置环境变量已解析：" + envResolved);
        // 打印空行
        System.out.println();
        // 打印已启用工具标题
        System.out.println("已启用的工具：");

        // 获取所有已注册工具的名称列表
        List<String> names = new ArrayList<>(registry.toolNames());
        // 对工具名称进行排序
        names.sort(String::compareTo);
        
        // 遍历排序后的工具名称
        for (String name : names) {
            // 获取工具实例
            var tool = registry.get(name);
            // 如果工具不存在，跳过
            if (tool == null) {
                continue;
            }
            
            // 初始化标志字符串
            String flags = "";
            // 如果是只读工具，添加 read-only 标志
            if (tool.effectPolicy().readOnly()) {
                flags = flags.isEmpty() ? "(read-only" : flags + ", read-only";
            }
            // 如果是独占工具，添加 exclusive 标志
            if (tool.effectPolicy().concurrency() == ricbot.tool.api.ToolEffectPolicy.Concurrency.EXCLUSIVE_WORKSPACE) {
                flags = flags.isEmpty() ? "(exclusive" : flags + ", exclusive";
            }
            // 如果有标志，闭合括号
            if (!flags.isEmpty()) {
                flags = flags + ")";
            }
            
            // 打印工具名称、描述及标志
            System.out.println("  - " + tool.getName() + " — " + tool.getDescription() + (flags.isEmpty() ? "" : " " + flags));
        }

    }

    // =========================================================
    // provider
    // =========================================================

    /**
     * 管理 LLM 提供商命令（如 login）。
     *
     * @param args 命令行参数
     */
    private static void provider(List<String> args) {
        if (args.isEmpty()) { // 如果没有参数
            System.out.println("用法：provider login <provider>"); // 打印用法提示
            return; // 退出方法
        }

        Path configPath = null;
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("--config".equals(arg) || "-c".equals(arg)) && i + 1 < args.size()) {
                configPath = Path.of(args.get(i + 1)).toAbsolutePath().normalize();
                i++;
                continue;
            }
            normalized.add(arg);
        }

        if (normalized.isEmpty()) {
            System.out.println("用法：provider login <provider> [api_key] [api_base] [--config path]");
            return;
        }

        String sub = normalized.get(0); // 获取子命令
        if (!"login".equals(sub)) { // 如果不是 login 子命令
            System.out.println("未知的 provider 子命令：" + sub); // 打印未知子命令提示
            return; // 退出方法
        }

        if (normalized.size() < 2) { // 如果缺少提供商名称
            System.out.println("用法：provider login <provider> [api_key] [api_base] [--config path]"); // 打印用法提示
            return; // 退出方法
        }

        String provider = normalized.get(1); // 获取提供商名称
        String apiKey = normalized.size() >= 3 ? normalized.get(2) : null;
        String apiBase = normalized.size() >= 4 ? normalized.get(3) : null;
        providerLogin(provider, apiKey, apiBase, configPath); // 执行登录逻辑
    }

    /**
     * 为支持 API Key 的 provider 写入配置；OAuth provider 给出明确提示。
     *
     * @param provider 提供商名称
     */
    private static void providerLogin(String provider, String apiKey, String apiBase, Path configPath) {
        ProviderSpec spec = ProviderRegistry.findByName(provider);
        if (spec == null) {
            System.out.println("未知 provider：" + provider);
            return;
        }

        if (spec.isOauth()) {
            System.out.println("Provider '" + provider + "' 依赖 OAuth/浏览器登录。");
            System.out.println("当前 Java 版本尚未内置该 provider 的 OAuth 流程；如你已经拿到可用 token，可直接写入对应 provider 配置。");
            return;
        }

        String resolvedKey = apiKey;
        if ((resolvedKey == null || resolvedKey.isBlank()) && spec.getEnvKey() != null && !spec.getEnvKey().isBlank()) {
            resolvedKey = System.getenv(spec.getEnvKey());
        }
        if (resolvedKey == null || resolvedKey.isBlank()) {
            resolvedKey = readSecret("请输入 " + provider + " 的 API Key: ");
        }
        if (resolvedKey == null || resolvedKey.isBlank()) {
            System.out.println("未提供 API Key，已取消。");
            return;
        }

        Config config = ConfigLoader.loadConfig(configPath);
        Config.ProviderConfig providerConfig = config.getProviders().getOrCreate(spec.getName());
        providerConfig.setApiKey(resolvedKey);

        String resolvedBase = apiBase;
        if ((resolvedBase == null || resolvedBase.isBlank())
                && (providerConfig.getApiBase() == null || providerConfig.getApiBase().isBlank())
                && spec.getDefaultApiBase() != null
                && !spec.getDefaultApiBase().isBlank()) {
            resolvedBase = spec.getDefaultApiBase();
        }
        if (resolvedBase != null && !resolvedBase.isBlank()) {
            providerConfig.setApiBase(resolvedBase);
        }

        config.getProviders().put(spec.getName(), providerConfig);
        Path target = configPath != null ? configPath : ConfigLoader.getConfigPath();
        ConfigLoader.saveConfig(config, target);

        System.out.println("已写入 provider 配置：");
        System.out.println("  provider = " + spec.getName());
        System.out.println("  config   = " + target);
        if (providerConfig.getApiBase() != null && !providerConfig.getApiBase().isBlank()) {
            System.out.println("  api_base = " + providerConfig.getApiBase());
        }
        if (spec.getEnvKey() != null && !spec.getEnvKey().isBlank()) {
            System.out.println("提示：你也可以改用环境变量 " + spec.getEnvKey() + " 管理密钥。");
        }
    }

    private static String readSecret(String prompt) {
        try {
            Console console = System.console();
            if (console != null) {
                char[] secret = console.readPassword("%s", prompt);
                return secret != null ? new String(secret).trim() : null;
            }
        } catch (Exception ignored) {
        }

        System.out.print(prompt);
        try {
            Scanner scanner = new Scanner(System.in);
            String line = scanner.nextLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String renderConfigDoctorReport(ConfigDoctorReport report) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> map = report.toMap();
        Map<String, Object> tools = castMap(map.get("enabledTools"));
        Map<String, Object> capability = castMap(map.get("providerCapability"));

        sb.append("ricbot config doctor\n");
        sb.append("status: ").append(report.status()).append("\n\n");
        sb.append("effective config\n");
        sb.append("  configPath: ").append(report.getConfigPath()).append("\n");
        sb.append("  workspace: ").append(report.getWorkspace()).append("\n");
        sb.append("  model: ").append(report.getModel()).append("\n");
        sb.append("  inferredProvider: ").append(report.getInferredProvider()).append("\n");
        sb.append("  apiBase: ").append(report.getApiBase()).append("\n");
        sb.append("  apiKeyPresent: ").append(report.isApiKeyPresent()).append("\n");
        sb.append("  tools.enable: ").append(value(tools.get("toolsEnable"))).append(" (implicit)\n");
        sb.append("  exec.enable: ").append(value(tools.get("exec")))
                .append(", sandbox=").append(value(tools.get("execSandbox"))).append("\n");
        sb.append("  restrictToWorkspace: ").append(value(tools.get("restrictToWorkspace"))).append("\n");
        sb.append("\n");

        sb.append("provider capability\n");
        sb.append("  providerName: ").append(value(capability.get("providerName"))).append("\n");
        sb.append("  source: ").append(value(capability.get("source"))).append("\n");
        sb.append("  model: ").append(value(capability.get("model"))).append("\n");
        sb.append("  supportsToolCalling: ").append(value(capability.get("supportsToolCalling"))).append("\n");
        sb.append("  supportsStreaming: ").append(value(capability.get("supportsStreaming"))).append("\n");
        sb.append("  supportsVision: ").append(value(capability.get("supportsVision"))).append("\n");
        sb.append("  supportsJsonMode: ").append(value(capability.get("supportsJsonMode"))).append("\n");
        sb.append("  supportsReasoningEffort: ").append(value(capability.get("supportsReasoningEffort"))).append("\n");
        sb.append("  contextWindowTokens: ").append(value(capability.get("contextWindowTokens"))).append("\n");
        sb.append("  maxOutputTokens: ").append(value(capability.get("maxOutputTokens"))).append("\n");
        sb.append("  apiMode: ").append(value(capability.get("apiMode"))).append("\n\n");

        appendList(sb, "errors", report.getErrors());
        appendList(sb, "warnings", report.getWarnings());
        appendList(sb, "ignored / reserved / partially-supported fields", report.getIgnoredFields());
        appendList(sb, "suggested fixes", report.getSuggestedFixes());
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }

    private static void appendList(StringBuilder sb, String title, List<?> rows) {
        sb.append(title).append("\n");
        if (rows == null || rows.isEmpty()) {
            sb.append("  - none\n\n");
            return;
        }
        for (Object row : rows) {
            sb.append("  - ").append(value(row)).append("\n");
        }
        sb.append("\n");
    }

    private static String value(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    // =========================================================
    // Helper methods
    // =========================================================

    /**
     * 加载运行时配置。
     *
     * @param configPath 配置文件路径
     * @param workspace  工作空间路径
     * @return 配置对象
     */
    private static Config loadRuntimeConfig(String configPath, String workspace) {
        return BOOTSTRAPPER.loadConfig(configPath, workspace); // 调用 Bootstrapper 加载配置
    }

    /**
     * 解析配置中的环境变量占位符，并打印最终生效的配置信息到标准错误流。
     * 如果 API Key 仍包含未解析的占位符，则抛出异常。
     *
     * @param configPath 配置文件路径字符串
     * @param config     原始配置对象
     * @return 解析后的配置对象
     * @throws IllegalArgumentException 如果 API Key 包含未解析的占位符
     */
    private static Config resolveAndPrintEffectiveConfig(String configPath, Config config) {
        Path resolvedPath = configPath != null && !configPath.isBlank() // 计算解析后的配置路径：如果 configPath 不为空，则规范化为绝对路径，否则使用默认配置路径
                ? Path.of(configPath).toAbsolutePath().normalize()
                : ConfigLoader.getConfigPath();

        String rawModel = config.getAgents().getDefaults().getModel(); // 获取原始模型名称
        String rawProviderName = config.getProviderName(rawModel); // 获取原始提供商名称
        Config.ProviderConfig rawPc = config.getProvider(rawModel); // 获取原始提供商配置
        String rawApiKey = rawPc != null ? rawPc.getApiKey() : null; // 获取原始 API Key

        Config resolved = config; // 声明解析后的配置对象，初始化为原始配置
        boolean envResolved = false; // 标记环境变量是否已解析
        try {
            resolved = ConfigLoader.resolveConfigEnvVars(config); // 尝试解析配置中的环境变量
            envResolved = true; // 标记解析成功
        } catch (Exception ignored) {
            // 忽略解析异常，保持原配置
        }

        String model = resolved.getAgents().getDefaults().getModel(); // 获取解析后的模型名称
        String providerName = resolved.getProviderName(model); // 获取解析后的提供商名称
        ProviderSpec spec = ProviderRegistry.findByName(providerName); // 查找提供商规范
        String backend = spec != null ? String.valueOf(spec.getBackend()) : "<unresolved>"; // 获取后端类型，若未找到则标记为未解析
        String apiBase = resolved.getApiBase(model); // 获取 API 基础 URL

        Config.ProviderConfig pc = resolved.getProvider(model); // 获取解析后的提供商配置
        String providerConfigKey = providerName; // 初始化提供商配置键
        if (!"openai".equalsIgnoreCase(providerName) && pc == resolved.getProviders().getOpenai()) { // 特殊处理 OpenAI 配置键
            providerConfigKey = "openai";
        }
        String apiKey = pc != null ? pc.getApiKey() : null; // 获取解析后的 API Key
        boolean hasKey = apiKey != null && !apiKey.isBlank(); // 检查是否存在 API Key
        boolean looksLikePlaceholder = hasKey && apiKey.contains("${") && apiKey.contains("}"); // 检查 API Key 是否看起来像未解析的占位符
        boolean keyResolved = hasKey && !looksLikePlaceholder; // 检查 API Key 是否已解析
        boolean rawLookedLikePlaceholder = rawApiKey != null && rawApiKey.contains("${") && rawApiKey.contains("}"); // 检查原始 API Key 是否看起来像占位符
        boolean envReplaced = rawLookedLikePlaceholder && keyResolved && envResolved; // 检查环境变量是否被替换

        System.err.println("ricbot config path: " + resolvedPath); // 打印配置路径
        System.err.println("ricbot config loaded: " + java.nio.file.Files.exists(resolvedPath)); // 打印配置文件是否存在
        System.err.println("ricbot effective model: " + model); // 打印生效的模型
        System.err.println("ricbot effective provider: " + providerName + " (backend=" + backend + ")"); // 打印生效的提供商及后端
        System.err.println("ricbot provider config key: " + providerConfigKey); // 打印提供商配置键
        System.err.println("ricbot effective api_base: " + (apiBase != null ? apiBase : "")); // 打印生效的 API Base
        System.err.println("ricbot api_key present: " + hasKey); // 打印 API Key 是否存在
        System.err.println("ricbot api_key env replaced: " + envReplaced); // 打印 API Key 环境变量是否被替换

        if (looksLikePlaceholder) { // 如果 API Key 仍包含未解析的占位符
            String envName = null;
            int start = apiKey.indexOf("${");
            if (start >= 0) {
                int end = apiKey.indexOf("}", start + 2);
                if (end > start + 2) {
                    envName = apiKey.substring(start + 2, end);
                }
            }
            throw new IllegalArgumentException( // 抛出异常
                    envName != null && !envName.isBlank()
                            ? "api_key contains an unresolved placeholder (${%s}). Set the environment variable %s or put a literal api_key in the config."
                            .formatted(envName, envName)
                            : "api_key contains an unresolved placeholder. Set the environment variable or put a literal api_key in the config."
            );
        }

        return resolved; // 返回解析后的配置
    }

    /**
     * 运行单个消息通过总线。
     * @param agentLoop
     * @param bus
     * @param input
     * @param sessionKey
     * @param channel
     * @param chatId
     * @param renderer
     * @return
     * @throws Exception
     */
    private static OutboundMessage runSingleMessageViaBus(
            AgentLoop agentLoop,
            MessageBus bus,
            String input,
            String sessionKey,
            String channel,
            String chatId,
            StreamRenderer renderer
    ) throws Exception {
        agentLoop.start();

        try {
            InboundMessage inbound = InboundMessages.of(
                    channel,
                    "user",
                    chatId,
                    input,
                    List.of(),
                    new HashMap<>(Map.of("_wants_stream", true)),
                    sessionKey,
                    null
            );

            bus.publishInbound(inbound); // 发布入站消息

            OutboundMessage last = null; // 声明最后一条消息
            long startMillis = System.currentTimeMillis(); // 记录开始时间

            while (true) { // 循环等待响应
                OutboundMessage msg = bus.pollOutbound(1000); // 轮询出站消息
                if (msg == null) { // 如果超时
                    if (System.currentTimeMillis() - startMillis > 5 * 60 * 1000) { // 如果超过 5 分钟
                        throw new RuntimeException("Agent response timeout"); // 抛出超时异常
                    }
                    continue; // 继续等待
                }

                last = msg; // 更新最后一条消息
                Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap(); // 获取元数据

                if (Boolean.TRUE.equals(meta.get("_stream_delta"))) { // 如果是流式增量
                    renderer.onDelta(msg.getContent()); // 渲染增量
                    continue; // 继续等待
                }

                if (Boolean.TRUE.equals(meta.get("_stream_end"))) { // 如果是流式结束
                    renderer.onEnd(Boolean.TRUE.equals(meta.get("_resuming"))); // 处理流式结束
                    continue; // 继续等待
                }

                if (Boolean.TRUE.equals(meta.get("_streamed"))) { // 如果标记为已流式传输完成
                    break; // 跳出循环
                }

                if (Boolean.TRUE.equals(meta.get("_progress"))) { // 如果是进度消息
                    continue; // 忽略进度消息
                }

                if (msg.getContent() != null && !msg.getContent().isBlank()) { // 如果有非空内容
                    break; // 跳出循环
                }
            }

            return last; // 返回最后一条消息
        } finally {
            agentLoop.stop(); // 停止 Agent 循环
        }
    }

    /**
     * 打印帮助信息。
     */
    private static void printHelp() {
        System.out.println("ricbot"); // 打印名称
        System.out.println("命令："); // 打印命令标题
        System.out.println("  onboard"); // 打印 onboard 命令
        System.out.println("  agent      交互模式运行 Agent，或处理单条消息");
        System.out.println("  config doctor  启动前诊断配置与 Provider capability");
        System.out.println("  eval       运行 JSONL 场景评测并生成 artifacts");
        System.out.println("  eval lint  静态检查 eval JSONL 场景");
        System.out.println("  eval smoke 使用内置确定性 provider 跑 eval smoke");
        System.out.println("  eval replay  离线回放 eval case/run artifact");
        System.out.println("  eval compare 对比两个 eval run 并识别回归");
        System.out.println("  eval matrix 对多个真实 provider/model 重复评测成本、延迟与长轨迹");
        System.out.println("  status     显示 ricbot 状态");
        System.out.println("  provider"); // 打印 provider 命令
        System.out.println("  tools");
    }

    /**
     * 打印版本信息。
     */
    private static void printVersion() {
        System.out.println("ricbot v0.1.0"); // 打印版本号
    }

    /**
     * 判断输入是否为退出命令。
     *
     * @param command 用户输入的命令
     * @return 如果是退出命令返回 true，否则 false
     */
    private static boolean isExitCommand(String command) {
        return Set.of("exit", "quit", "/exit", "/quit", ":q").contains(command.toLowerCase(Locale.ROOT)); // 检查命令是否在退出命令集合中
    }

    /**
     * 打印 Agent 的响应内容。
     *
     * @param response      响应内容
     * @param renderMarkdown 是否渲染 Markdown
     * @param metadata      元数据
     */
    private static void printAgentResponse(String response, boolean renderMarkdown, Map<String, Object> metadata) {
        System.out.println(); // 打印空行
        System.out.println("ricbot"); // 打印名称
        System.out.println(response != null ? response : ""); // 打印响应内容
        System.out.println(); // 打印空行
    }

    /**
     * 检查参数列表中是否包含指定的标志。
     *
     * @param args 参数列表
     * @param flag 要检查的标志
     * @return 如果包含返回 true，否则 false
     */
    private static boolean hasFlag(List<String> args, String flag) {
        return args.contains(flag); // 检查列表是否包含标志
    }

    /**
     * 从参数列表中获取指定选项的值。
     *
     * @param args    参数列表
     * @param longOpt 长选项名 (e.g., --config)
     * @param shortOpt 短选项名 (e.g., -c)
     * @return 选项值，如果未找到则返回 null
     */
    private static String optionValue(List<String> args, String longOpt, String shortOpt) {
        for (int i = 0; i < args.size(); i++) { // 遍历参数列表
            String cur = args.get(i); // 获取当前参数
            if ((longOpt != null && longOpt.equals(cur)) || (shortOpt != null && shortOpt.equals(cur))) { // 如果匹配长选项或短选项
                if (i + 1 < args.size()) { // 如果下一个参数存在
                    return args.get(i + 1); // 返回下一个参数作为值
                }
            }
        }
        return null; // 未找到则返回 null
    }

    private static List<String> optionValues(List<String> args, String longOpt, String shortOpt) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String cur = args.get(i);
            if ((longOpt != null && longOpt.equals(cur)) || (shortOpt != null && shortOpt.equals(cur))) {
                if (i + 1 < args.size()) {
                    addOptionValues(values, args.get(i + 1));
                }
            }
        }
        return values;
    }

    private static void addOptionValues(List<String> values, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
    }

    /**
     * 从参数列表中获取指定选项的整数值。
     *
     * @param args    参数列表
     * @param longOpt 长选项名
     * @param shortOpt 短选项名
     * @return 整数值，如果未找到或解析失败则返回 null
     */
    private static Integer optionIntValue(List<String> args, String longOpt, String shortOpt) {
        String v = optionValue(args, longOpt, shortOpt); // 获取选项字符串值
        if (v == null) return null; // 如果值为 null，返回 null
        try {
            return Integer.parseInt(v); // 尝试解析为整数
        } catch (Exception e) {
            return null; // 解析失败返回 null
        }
    }

    /**
     * 从参数列表中获取指定选项的双精度浮点数值。
     *
     * @param args    参数列表
     * @param longOpt 长选项名
     * @param shortOpt 短选项名
     * @return 双精度浮点数值，如果未找到或解析失败则返回 null
     */
    private static Double optionDoubleValue(List<String> args, String longOpt, String shortOpt) {
        String v = optionValue(args, longOpt, shortOpt); // 获取选项字符串值
        if (v == null) return null; // 如果值为 null，返回 null
        try {
            return Double.parseDouble(v); // 尝试解析为双精度浮点数
        } catch (Exception e) {
            return null; // 解析失败返回 null
        }
    }

    /**
     * 安全地读取一行输入，避免异常中断。
     *
     * @param scanner Scanner 实例
     * @return 读取的行内容，如果发生异常则返回 null
     */
    private static String safeReadLine(Scanner scanner) {
        try {
            return scanner.nextLine(); // 尝试读取下一行
        } catch (Exception e) {
            return null; // 发生异常返回 null
        }
    }
}
