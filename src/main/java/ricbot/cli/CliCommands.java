package ricbot.cli;

import ricbot.bootstrap.Bootstrapper; // 导入 Bootstrapper 类，用于初始化核心组件
import ricbot.core.agent.AgentLoop; // 导入 AgentLoop 类，用于运行 Agent 逻辑

import ricbot.core.message.InboundMessage; // 导入入站消息类
import ricbot.core.message.MessageBus; // 导入消息总线类，用于消息传递
import ricbot.core.message.OutboundMessage; // 导出现站消息类
import ricbot.infra.config.Config; // 导入配置类
import ricbot.infra.config.ConfigLoader; // 导入配置加载器
import ricbot.infra.config.RuntimePaths; // 导入运行时路径工具类
import ricbot.llm.api.ProviderRegistry;
import ricbot.llm.api.ProviderSpec;

import java.nio.file.Path; // 导入 Path 类，用于文件路径操作
import java.util.*; // 导入 Java 集合框架
import java.util.concurrent.ExecutorService; // 导入线程池服务接口
import java.util.concurrent.Executors; // 导入线程池工厂类

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
            case "status" -> status(); // 状态命令
            case "provider" -> provider(argv.subList(1, argv.size())); // 提供商管理命令，传递剩余参数
            default -> { // 未知命令
                System.out.println("Unknown command: " + cmd); // 打印未知命令提示
                printHelp(); // 打印帮助信息
            }
        }
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
            System.out.println("Using config: " + resolvedConfigPath); // 打印使用的配置路径
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

        ConfigLoader.saveConfig(config, resolvedConfigPath); // 保存配置到文件
        onboardPlugins(resolvedConfigPath); // 初始化插件（占位方法）

        Path workspacePath = RuntimePaths.getWorkspacePath(config.getAgents().getDefaults().getWorkspace()); // 获取工作空间路径
        System.out.println("Workspace: " + workspacePath); // 打印工作空间路径
        System.out.println("ricbot is ready!"); // 打印就绪信息
    }

    /**
     * 插件初始化占位方法。
     * 对应 Python _onboard_plugins:
     * 读取 config.json，把 discover_all() 发现的 channels default_config 注入。
     * 这里先保留扩展点。
     *
     * @param configPath 配置文件路径
     */
    private static void onboardPlugins(Path configPath) {
        // 对应 Python _onboard_plugins:
        // 读取 config.json，把 discover_all() 发现的 channels default_config 注入。
        // 这里先保留扩展点。
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
        Config resolvedConfig = resolveAndPrintEffectiveConfig(configPath, config);

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

        ExecutorService executor = Executors.newCachedThreadPool(); // 创建缓存线程池
        executor.submit(agentLoop::run); // 提交 Agent 循环任务到线程池

        System.out.println("Interactive mode (type exit or Ctrl+C to quit)"); // 打印交互模式提示

        while (true) { // 主循环
            System.out.print("You: "); // 打印提示符
            String input = safeReadLine(scanner); // 安全读取一行输入
            if (input == null) { // 如果读取失败（例如 EOF）
                System.out.println("Goodbye!"); // 打印告别信息
                break; // 退出循环
            }

            String command = input.trim(); // 去除输入两端空白
            if (command.isBlank()) { // 如果输入为空
                continue; // 跳过本次循环
            }
            if (isExitCommand(command)) { // 如果是退出命令
                System.out.println("Goodbye!"); // 打印告别信息
                break; // 退出循环
            }

            StreamRenderer renderer = new StreamRenderer(markdown, true); // 创建流式渲染器

            InboundMessage inbound = new InboundMessage(); // 创建入站消息对象
            inbound.setChannel(cliChannel); // 设置通道
            inbound.setSenderId("user"); // 设置发送者 ID
            inbound.setChatId(cliChatId); // 设置聊天 ID
            inbound.setContent(input); // 设置消息内容
            inbound.setMetadata(Map.of("_wants_stream", true)); // 设置元数据，标记需要流式输出

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

        agentLoop.stop(); // 停止 Agent 循环
        executor.shutdownNow(); // 立即关闭线程池
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

        System.out.println("ricbot Status"); // 打印标题
        System.out.println("Config: " + configPath + (configPath.toFile().exists() ? " ✓" : " ✗")); // 打印配置状态
        System.out.println("Workspace: " + workspace + (workspace.toFile().exists() ? " ✓" : " ✗")); // 打印工作空间状态
        System.out.println("Model: " + config.getAgents().getDefaults().getModel()); // 打印默认模型
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
            System.out.println("Usage: provider login <provider>"); // 打印用法提示
            return; // 退出方法
        }

        String sub = args.get(0); // 获取子命令
        if (!"login".equals(sub)) { // 如果不是 login 子命令
            System.out.println("Unknown provider subcommand: " + sub); // 打印未知子命令提示
            return; // 退出方法
        }

        if (args.size() < 2) { // 如果缺少提供商名称
            System.out.println("Usage: provider login <provider>"); // 打印用法提示
            return; // 退出方法
        }

        String provider = args.get(1); // 获取提供商名称
        providerLogin(provider); // 执行登录逻辑
    }

    /**
     * 处理特定提供商的 OAuth 登录逻辑占位。
     *
     * @param provider 提供商名称
     */
    private static void providerLogin(String provider) {
        System.out.println("OAuth login for provider '" + provider + "' is provider-specific."); // 打印提示信息
        System.out.println("This is the Java placeholder corresponding to Python provider_login()."); // 打印占位提示
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
            throw new IllegalArgumentException(
                    "api_key contains an unresolved placeholder. Set the environment variable or put a literal api_key in the config."
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
        ExecutorService executor = Executors.newCachedThreadPool(); // 创建缓存线程池
        executor.submit(agentLoop::run); // 提交 Agent 循环任务

        try {
            InboundMessage inbound = new InboundMessage(); // 创建入站消息对象
            inbound.setChannel(channel); // 设置通道
            inbound.setSenderId("user"); // 设置发送者 ID
            inbound.setChatId(chatId); // 设置聊天 ID
            inbound.setContent(input); // 设置消息内容
            inbound.setSessionKeyOverride(sessionKey); // 设置会话密钥覆盖
            inbound.setMetadata(new HashMap<>(Map.of("_wants_stream", true))); // 设置元数据，标记需要流式输出

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
            executor.shutdownNow(); // 立即关闭线程池
        }
    }

    /**
     * 打印帮助信息。
     */
    private static void printHelp() {
        System.out.println("ricbot"); // 打印名称
        System.out.println("Commands:"); // 打印命令标题
        System.out.println("  onboard"); // 打印 onboard 命令
        System.out.println("  agent"); // 打印 agent 命令
        System.out.println("  status"); // 打印 status 命令
        System.out.println("  provider"); // 打印 provider 命令
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
