package ricbot.app.cli;

import ricbot.app.bootstrap.Bootstrapper;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;
import ricbot.infra.config.RuntimePaths;
import ricbot.domain.skill.SkillsLoader;
import ricbot.infra.heartbeat.HeartbeatService;
import ricbot.integration.api.RicbotApiServer;
import ricbot.integration.mcp.MCPLoader;
import ricbot.integration.llm.provider.ProviderRegistry;
import ricbot.integration.llm.provider.ProviderSpec;
import ricbot.integration.channel.ChannelManager;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/**
 * Ricbot CLI 命令入口类。
 */
public final class CliCommands {

    private static final Bootstrapper BOOTSTRAPPER = new Bootstrapper();
    private static final Logger log = LoggerFactory.getLogger(CliCommands.class);

    private CliCommands() {
    }

    public static void main(String[] args) throws Exception {
        initLogging(args);
        if (args.length == 0) {
            printHelp();
            return;
        }

        List<String> argv = Arrays.asList(args);
        String cmd = argv.get(0);

        switch (cmd) {
            case "--version", "-v" -> printVersion();
            case "onboard" -> onboard(argv.subList(1, argv.size()));
            case "agent" -> agent(argv.subList(1, argv.size()));
            case "serve" -> serve(argv.subList(1, argv.size()));
            case "status" -> status();
            case "provider" -> provider(argv.subList(1, argv.size()));
            case "tools" -> tools(argv.subList(1, argv.size()));
            case "skills" -> skills(argv.subList(1, argv.size()));
            default -> {
                System.out.println("未知命令：" + cmd);
                printHelp();
            }
        }
    }

    private static void initLogging(String[] args) {
        String workspace = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String cur = args[i];
                if ("--workspace".equals(cur) || "-w".equals(cur)) {
                    if (i + 1 < args.length) {
                        workspace = args[i + 1];
                    }
                }
            }
        }

        Path workspacePath = RuntimePaths.getWorkspacePath(workspace);
        Path logsDir = workspacePath.resolve(".ricbot").resolve("logs");
        try {
            Files.createDirectories(logsDir);
        } catch (Exception ignored) {
        }
        Path logFile = logsDir.resolve("ricbot.log");
        System.setProperty("ricbot.log.file", logFile.toString());
    }

    private static void onboard(List<String> args) {
        String workspace = optionValue(args, "--workspace", "-w");
        String configPath = optionValue(args, "--config", "-c");

        Config config;
        Path resolvedConfigPath;

        if (configPath != null) {
            resolvedConfigPath = Path.of(configPath).toAbsolutePath().normalize();
            ConfigLoader.setConfigPath(resolvedConfigPath);
            System.out.println("使用配置文件：" + resolvedConfigPath);
        } else {
            resolvedConfigPath = ConfigLoader.getConfigPath();
        }

        if (resolvedConfigPath.toFile().exists()) {
            config = ConfigLoader.loadConfig(resolvedConfigPath);
        } else {
            config = new Config();
        }

        if (workspace != null && !workspace.isBlank()) {
            config.getAgents().getDefaults().setWorkspace(workspace);
        }

        OnboardWizard.OnboardResult result = OnboardWizard.runOnboard(config);
        if (result.shouldSave()) {
            Config finalConfig = result.config();
            ConfigLoader.saveConfig(finalConfig, resolvedConfigPath);
            onboardPlugins(resolvedConfigPath);

            Path workspacePath = RuntimePaths.getWorkspacePath(finalConfig.getAgents().getDefaults().getWorkspace());
            System.out.println("工作区：" + workspacePath);
            System.out.println("配置已保存到：" + resolvedConfigPath);
            System.out.println("ricbot 已就绪！");
            return;
        }

        System.out.println("已取消，配置未保存。");
    }

    private static void onboardPlugins(Path configPath) {
    }

    private static void agent(List<String> args) throws Exception {
        String message = optionValue(args, "--message", "-m");
        String sessionId = optionValue(args, "--session", "-s");
        String configPath = optionValue(args, "--config", "-c");
        String workspace = optionValue(args, "--workspace", "-w");
        boolean markdown = !hasFlag(args, "--no-markdown");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "cli:direct";
        }

        Config config = loadRuntimeConfig(configPath, workspace);
        Config resolvedConfig = resolveAndPrintEffectiveConfig(configPath, config);

        MessageBus bus = new MessageBus();
        var provider = BOOTSTRAPPER.createProvider(resolvedConfig);

        AgentLoop agentLoop = BOOTSTRAPPER.createAgentLoop(resolvedConfig, bus, provider);

        if (message != null && !message.isBlank()) {
            StreamRenderer renderer = new StreamRenderer(markdown, true);
            try {
                OutboundMessage response = runSingleMessageViaBus(agentLoop, bus, message, sessionId, "cli", "direct", renderer);
                if (!renderer.isStreamed()) {
                    renderer.close();
                    printAgentResponse(
                            response != null ? response.getContent() : "",
                            markdown,
                            response != null ? response.getMetadata() : null
                    );
                }
                return;
            } finally {
                renderer.close();
            }
        }

        interactiveAgent(agentLoop, bus, sessionId, markdown);
    }

    private static void interactiveAgent(AgentLoop agentLoop, MessageBus bus, String sessionId, boolean markdown) throws Exception {
        Scanner scanner = new Scanner(System.in);

        String cliChannel;
        String cliChatId;
        if (sessionId.contains(":")) {
            String[] parts = sessionId.split(":", 2);
            cliChannel = parts[0];
            cliChatId = parts[1];
        } else {
            cliChannel = "cli";
            cliChatId = sessionId;
        }

        agentLoop.start();

        try {
            System.out.println("交互模式（输入 exit 或按 Ctrl+C 退出）");

            while (true) {
                System.out.print("你：");
                String input = safeReadLine(scanner);
                if (input == null) {
                    System.out.println("再见！");
                    break;
                }

                String command = input.trim();
                if (command.isBlank()) {
                    continue;
                }
                if (isExitCommand(command)) {
                    System.out.println("再见！");
                    break;
                }

                StreamRenderer renderer = new StreamRenderer(markdown, true);

                InboundMessage inbound = new InboundMessage();
                inbound.setChannel(cliChannel);
                inbound.setSenderId("user");
                inbound.setChatId(cliChatId);
                inbound.setContent(input);
                inbound.setMetadata(Map.of("_wants_stream", true));

                bus.publishInbound(inbound);

                while (true) {
                    OutboundMessage msg = bus.pollOutbound(1000);
                    if (msg == null) {
                        continue;
                    }

                    Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap();

                    if (Boolean.TRUE.equals(meta.get("_stream_delta"))) {
                        renderer.onDelta(msg.getContent());
                        continue;
                    }

                    if (Boolean.TRUE.equals(meta.get("_stream_end"))) {
                        renderer.onEnd(Boolean.TRUE.equals(meta.get("_resuming")));
                        continue;
                    }

                    if (Boolean.TRUE.equals(meta.get("_streamed"))) {
                        break;
                    }

                    if (Boolean.TRUE.equals(meta.get("_progress"))) {
                        continue;
                    }

                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        if (!renderer.isStreamed()) {
                            renderer.close();
                            printAgentResponse(msg.getContent(), markdown, msg.getMetadata());
                        }
                        break;
                    }
                }
            }
        } finally {
            agentLoop.stop();
        }
    }

    private static void serve(List<String> args) throws Exception {
        String configPath = optionValue(args, "--config", "-c");
        String workspace = optionValue(args, "--workspace", "-w");

        Config config = loadRuntimeConfig(configPath, workspace);
        Config resolvedConfig = resolveAndPrintEffectiveConfig(configPath, config);

        MessageBus bus = BOOTSTRAPPER.createBus();
        var provider = BOOTSTRAPPER.createProvider(resolvedConfig);

        AgentLoop agentLoop = BOOTSTRAPPER.createAgentLoop(resolvedConfig, bus, provider);
        ChannelManager channelManager = BOOTSTRAPPER.createChannelManager(resolvedConfig, bus);

        HeartbeatService heartbeat = BOOTSTRAPPER.createHeartbeatService(
                resolvedConfig,
                provider,
                (tasks) -> {
                    log.info("心跳任务执行中：{}", tasks);
                    OutboundMessage out = agentLoop.processDirect(tasks, "heartbeat:default", "system", "heartbeat");
                    return out != null ? out.getContent() : null;
                },
                (response) -> {
                    log.info("心跳任务结果：{}", response);
                    OutboundMessage out = new OutboundMessage();
                    out.setChannel("system");
                    out.setChatId("heartbeat");
                    out.setContent(response);
                    bus.publishOutbound(out);
                }
        );

        System.out.println("正在启动 Ricbot 服务…");
        
        agentLoop.start();
        
        channelManager.startAll();

        heartbeat.start();

        Config.GatewayConfig gateway = resolvedConfig.getGateway();
        var apiServer = RicbotApiServer.createAndStart(
                gateway.getPort(),
                agentLoop,
                resolvedConfig.getAgents().getDefaults().getModel(),
                120_000L
        );
        System.out.println("OpenAI 兼容 API 服务已启动，端口：" + gateway.getPort());

        System.out.println("Ricbot 正在运行，按 Ctrl+C 停止。");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭…");
            apiServer.stop(1);
            heartbeat.stop();
            channelManager.stopAll();
            agentLoop.stop();
        }));

        while (true) {
            Thread.sleep(1000);
        }
    }

    private static void status() {
        Config config = ConfigLoader.loadConfig();
        Path configPath = ConfigLoader.getConfigPath();
        Path workspace = config.getWorkspacePath();

        System.out.println("ricbot 状态");
        System.out.println("配置文件：" + configPath + (configPath.toFile().exists() ? " ✓" : " ✗"));
        System.out.println("工作区：" + workspace + (workspace.toFile().exists() ? " ✓" : " ✗"));
        System.out.println("模型：" + config.getAgents().getDefaults().getModel());
    }

    private static void skills(List<String> args) {
        String configPath = optionValue(args, "--config", "-c");
        String workspaceOverride = optionValue(args, "--workspace", "-w");

        Config config = loadRuntimeConfig(configPath, workspaceOverride);
        Config resolved = config;
        try {
            resolved = ConfigLoader.resolveConfigEnvVars(config);
        } catch (Exception ignored) {
        }

        Set<String> disabled = new HashSet<>();
        try {
            List<String> list = resolved.getAgents().getDefaults().getDisabledSkills();
            if (list != null) {
                disabled.addAll(list);
            }
        } catch (Exception ignored) {
        }

        SkillsLoader loader = new SkillsLoader(resolved.getWorkspacePath(), null, disabled);
        List<Map<String, String>> rows = loader.listSkills(false);

        System.out.println("ricbot skills");
        if (rows.isEmpty()) {
            System.out.println("（未发现任何技能）");
            return;
        }
        for (Map<String, String> row : rows) {
            String name = row.getOrDefault("name", "");
            String source = row.getOrDefault("source", "");
            boolean isDisabled = "true".equalsIgnoreCase(row.get("disabled"));
            System.out.println("- " + name + (source.isBlank() ? "" : " (" + source + ")") + (isDisabled ? " [disabled]" : ""));
        }
    }

    private static void tools(List<String> args) {
        String configPath = optionValue(args, "--config", "-c");
        String workspaceOverride = optionValue(args, "--workspace", "-w");

        Config config = loadRuntimeConfig(configPath, workspaceOverride);
        Config resolved = config;
        boolean envResolved = false;
        try {
            resolved = ConfigLoader.resolveConfigEnvVars(config);
            envResolved = true;
        } catch (Exception ignored) {
        }

        Path workspace = resolved.getWorkspacePath();
        boolean restrictToWorkspace = resolved.getTools().isRestrictToWorkspace();
        Config.ExecToolConfig execConfig = resolved.getTools().getExec() != null ? resolved.getTools().getExec() : new Config.ExecToolConfig();

        Path allowedDir = (restrictToWorkspace || execConfig.isSandbox()) ? workspace : null;

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace, allowedDir, List.of()));
        registry.register(new ListDirTool(workspace, allowedDir));
        registry.register(new WriteFileTool(workspace, allowedDir));
        registry.register(new EditFileTool(workspace, allowedDir));
        registry.register(new GlobTool(workspace, allowedDir));
        registry.register(new GrepTool(workspace, allowedDir));
        
        if (execConfig.isEnable()) {
            registry.register(new ExecTool(
                    execConfig.getTimeout(),
                    workspace.toString(),
                    null,
                    null,
                    restrictToWorkspace,
                    execConfig.isSandbox() ? "sandbox" : "",
                    execConfig.getPathAppend(),
                    execConfig.getAllowedEnvKeys()
            ));
        }

        MCPLoader mcpLoader = null;
        if (resolved.getTools().getMcpServers() != null && !resolved.getTools().getMcpServers().isEmpty()) {
            try {
                mcpLoader = new MCPLoader(registry, resolved.getTools().getMcpServers());
                mcpLoader.load();
            } catch (Exception e) {
                System.out.println("MCP 工具加载失败：" + e.getMessage());
            }
        }

        System.out.println("ricbot 工具");
        System.out.println("工作区：" + workspace);
        System.out.println("restrictToWorkspace：" + restrictToWorkspace);
        System.out.println("允许的基目录：" + (allowedDir != null ? allowedDir : "（不限制）"));
        System.out.println("exec：enable=" + execConfig.isEnable()
                + ", sandbox=" + execConfig.isSandbox()
                + ", timeout=" + execConfig.getTimeout()
                + ", allowed_env_keys=" + (execConfig.getAllowedEnvKeys() != null ? execConfig.getAllowedEnvKeys().size() : 0));
        System.out.println("配置环境变量已解析：" + envResolved);
        System.out.println();
        System.out.println("已启用的工具：");

        List<String> names = new ArrayList<>(registry.toolNames());
        names.sort(String::compareTo);
        
        for (String name : names) {
            var tool = registry.get(name);
            if (tool == null) {
                continue;
            }
            
            String flags = "";
            if (tool.isReadOnly()) {
                flags = flags.isEmpty() ? "(read-only" : flags + ", read-only";
            }
            if (tool.isExclusive()) {
                flags = flags.isEmpty() ? "(exclusive" : flags + ", exclusive";
            }
            if (!flags.isEmpty()) {
                flags = flags + ")";
            }
            
            System.out.println("  - " + tool.getName() + " — " + tool.getDescription() + (flags.isEmpty() ? "" : " " + flags));
        }

        if (mcpLoader != null) {
            try {
                mcpLoader.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void provider(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("用法：provider login <provider>");
            return;
        }

        String sub = args.get(0);
        if (!"login".equals(sub)) {
            System.out.println("未知的 provider 子命令：" + sub);
            return;
        }

        if (args.size() < 2) {
            System.out.println("用法：provider login <provider>");
            return;
        }

        String provider = args.get(1);
        providerLogin(provider);
    }

    private static void providerLogin(String provider) {
        System.out.println("Provider '" + provider + "' 的 OAuth 登录流程需要按 provider 具体实现。");
        System.out.println("这是 Java 版本的占位实现，对应 Python 的 provider_login()。");
    }

    private static Config loadRuntimeConfig(String configPath, String workspace) {
        return BOOTSTRAPPER.loadConfig(configPath, workspace);
    }

    private static Config resolveAndPrintEffectiveConfig(String configPath, Config config) {
        Path resolvedPath = configPath != null && !configPath.isBlank()
                ? Path.of(configPath).toAbsolutePath().normalize()
                : ConfigLoader.getConfigPath();

        String rawModel = config.getAgents().getDefaults().getModel();
        String rawProviderName = config.getProviderName(rawModel);
        Config.ProviderConfig rawPc = config.getProvider(rawModel);
        String rawApiKey = rawPc != null ? rawPc.getApiKey() : null;

        Config resolved = config;
        boolean envResolved = false;
        try {
            resolved = ConfigLoader.resolveConfigEnvVars(config);
            envResolved = true;
        } catch (Exception ignored) {
        }

        String model = resolved.getAgents().getDefaults().getModel();
        String providerName = resolved.getProviderName(model);
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        String backend = spec != null ? String.valueOf(spec.getBackend()) : "<unresolved>";
        String apiBase = resolved.getApiBase(model);

        Config.ProviderConfig pc = resolved.getProvider(model);
        String providerConfigKey = providerName;
        if (!"openai".equalsIgnoreCase(providerName) && pc == resolved.getProviders().getOpenai()) {
            providerConfigKey = "openai";
        }
        String apiKey = pc != null ? pc.getApiKey() : null;
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        boolean looksLikePlaceholder = hasKey && apiKey.contains("${") && apiKey.contains("}");
        boolean keyResolved = hasKey && !looksLikePlaceholder;
        boolean rawLookedLikePlaceholder = rawApiKey != null && rawApiKey.contains("${") && rawApiKey.contains("}");
        boolean envReplaced = rawLookedLikePlaceholder && keyResolved && envResolved;

        System.err.println("ricbot config path: " + resolvedPath);
        System.err.println("ricbot config loaded: " + java.nio.file.Files.exists(resolvedPath));
        System.err.println("ricbot effective model: " + model);
        System.err.println("ricbot effective provider: " + providerName + " (backend=" + backend + ")");
        System.err.println("ricbot provider config key: " + providerConfigKey);
        System.err.println("ricbot effective api_base: " + (apiBase != null ? apiBase : ""));
        System.err.println("ricbot api_key present: " + hasKey);
        System.err.println("ricbot api_key env replaced: " + envReplaced);

        if (looksLikePlaceholder) {
            String envName = null;
            int start = apiKey.indexOf("${");
            if (start >= 0) {
                int end = apiKey.indexOf("}", start + 2);
                if (end > start + 2) {
                    envName = apiKey.substring(start + 2, end);
                }
            }
            throw new IllegalArgumentException(
                    envName != null && !envName.isBlank()
                            ? "api_key contains an unresolved placeholder (${%s}). Set the environment variable %s or put a literal api_key in the config."
                            .formatted(envName, envName)
                            : "api_key contains an unresolved placeholder. Set the environment variable or put a literal api_key in the config."
            );
        }

        return resolved;
    }

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
            InboundMessage inbound = new InboundMessage();
            inbound.setChannel(channel);
            inbound.setSenderId("user");
            inbound.setChatId(chatId);
            inbound.setContent(input);
            inbound.setSessionKeyOverride(sessionKey);
            inbound.setMetadata(new HashMap<>(Map.of("_wants_stream", true)));

            bus.publishInbound(inbound);

            OutboundMessage last = null;
            long startMillis = System.currentTimeMillis();

            while (true) {
                OutboundMessage msg = bus.pollOutbound(1000);
                if (msg == null) {
                    if (System.currentTimeMillis() - startMillis > 5 * 60 * 1000) {
                        throw new RuntimeException("Agent response timeout");
                    }
                    continue;
                }

                last = msg;
                Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap();

                if (Boolean.TRUE.equals(meta.get("_stream_delta"))) {
                    renderer.onDelta(msg.getContent());
                    continue;
                }

                if (Boolean.TRUE.equals(meta.get("_stream_end"))) {
                    renderer.onEnd(Boolean.TRUE.equals(meta.get("_resuming")));
                    continue;
                }

                if (Boolean.TRUE.equals(meta.get("_streamed"))) {
                    break;
                }

                if (Boolean.TRUE.equals(meta.get("_progress"))) {
                    continue;
                }

                if (msg.getContent() != null && !msg.getContent().isBlank()) {
                    break;
                }
            }

            return last;
        } finally {
            agentLoop.stop();
        }
    }

    private static void printHelp() {
        System.out.println("ricbot");
        System.out.println("命令：");
        System.out.println("  onboard");
        System.out.println("  agent      交互模式运行 Agent，或处理单条消息");
        System.out.println("  serve      启动多渠道服务（飞书、钉钉、企微等）");
        System.out.println("  status     显示 ricbot 状态");
        System.out.println("  provider");
        System.out.println("  tools");
        System.out.println("  skills");
    }

    private static void printVersion() {
        System.out.println("ricbot v0.1.0");
    }

    private static boolean isExitCommand(String command) {
        return Set.of("exit", "quit", "/exit", "/quit", ":q").contains(command.toLowerCase(Locale.ROOT));
    }

    private static void printAgentResponse(String response, boolean renderMarkdown, Map<String, Object> metadata) {
        System.out.println();
        System.out.println("ricbot");
        System.out.println(response != null ? response : "");
        System.out.println();
    }

    private static boolean hasFlag(List<String> args, String flag) {
        return args.contains(flag);
    }

    private static String optionValue(List<String> args, String longOpt, String shortOpt) {
        for (int i = 0; i < args.size(); i++) {
            String cur = args.get(i);
            if ((longOpt != null && longOpt.equals(cur)) || (shortOpt != null && shortOpt.equals(cur))) {
                if (i + 1 < args.size()) {
                    return args.get(i + 1);
                }
            }
        }
        return null;
    }

    private static Integer optionIntValue(List<String> args, String longOpt, String shortOpt) {
        String v = optionValue(args, longOpt, shortOpt);
        if (v == null) return null;
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double optionDoubleValue(List<String> args, String longOpt, String shortOpt) {
        String v = optionValue(args, longOpt, shortOpt);
        if (v == null) return null;
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeReadLine(Scanner scanner) {
        try {
            return scanner.nextLine();
        } catch (Exception e) {
            return null;
        }
    }
}
