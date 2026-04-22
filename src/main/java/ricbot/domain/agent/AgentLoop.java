package ricbot.domain.agent;

import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.skill.SkillRouter;
import ricbot.infra.cron.CronService;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.Dream;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.subagent.SubagentManager;
import ricbot.domain.hook.AgentHook;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.cron.CronTool;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.NotebookEditTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.process.SpawnTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import ricbot.integration.mcp.MCPLoader;
import ricbot.integration.command.CommandRouter;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：ricbot 的核心调度引擎。
 */
public class AgentLoop {

    /** 日志记录器，用于记录 AgentLoop 类的运行日志 */
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 统一会话的默认键值，当启用统一会话模式时使用 */
    public static final String UNIFIED_SESSION_KEY = "unified:default";

    /** 消息总线，用于接收入站消息和发送出站消息 */
    private final MessageBus bus;
    /** LLM 提供者，用于调用大语言模型 */
    private final LLMProvider provider;
    /** 工作空间路径，用于限制文件操作范围 */
    private final Path workspace;
    /** 使用的模型名称 */
    private final String model;

    /** 最大工具调用迭代次数 */
    private final int maxIterations;
    /** 上下文窗口大小（Token 数） */
    private final int contextWindowTokens;
    /** 上下文块数量限制，可选 */
    private final Integer contextBlockLimit;
    /** 工具返回结果的最大字符数 */
    private final int maxToolResultChars;

    /** Web 工具配置 */
    private final Config.WebToolsConfig webConfig;
    /** 执行工具配置 */
    private final Config.ExecToolConfig execConfig;
    /** Dream 配置 */
    private final Config.DreamConfig dreamConfig;
    /** MCP 服务器配置映射 */
    private final Map<String, Object> mcpServers;
    /** 是否限制操作仅在工作空间内 */
    private final boolean restrictToWorkspace;
    /** 是否启用统一会话模式 */
    private final boolean unifiedSession;
    /** Provider 重试模式（透传到运行规格） */
    private final String providerRetryMode;
    /** 会话自动归档 TTL（分钟），0 表示禁用 */
    private final int sessionTtlMinutes;

    /** 上下文构建器，用于构建发送给 LLM 的消息上下文 */
    private final ContextBuilder contextBuilder;
    /** 会话管理器，负责会话的创建、加载和保存 */
    private final SessionManager sessionManager;
    /** 记忆存储，用于长期记忆管理 */
    private final MemoryStore memoryStore;
    /** 记忆整合器，用于压缩和整理历史消息 */
    private final Consolidator consolidator;
    /** Dream 模块，用于后台记忆整理和反思 */
    private final Dream dream;
    /** 会话自动归档器 */
    private final AutoCompact autoCompact;
    /** 子代理管理器，用于管理子代理任务 */
    private final SubagentManager subagents;
    /** 技能加载器，用于加载可用技能 */
    private final SkillsLoader skillsLoader;
    /** 技能路由器，用于根据上下文选择合适技能 */
    private final SkillRouter skillRouter;
    /** 定时任务服务 */
    private final CronService cronService;
    /** 工具注册表，管理所有可用工具 */
    private final ToolRegistry tools;
    /** Agent 运行器，负责执行具体的 LLM 交互循环 */
    private final AgentRunner runner;
    /** Hook 工厂，负责组合请求级 Hook */
    private final AgentHookFactory hookFactory;
    /** 会话准备服务 */
    private final SessionPreparationService sessionPreparationService;
    /** 上下文组装服务 */
    private final AgentContextService agentContextService;
    /** 执行服务 */
    private final AgentExecutionService agentExecutionService;
    /** 持久化服务 */
    private final SessionPersistenceService sessionPersistenceService;
    /** MCP 兼容加载器（统一 MCP 装配路径） */
    private final MCPLoader mcpLoader;
    /** 命令路由器，统一 slash 命令入口 */
    private final CommandRouter commandRouter;
    /** 命令处理器 */
    private final AgentCommands agentCommands;

    /** 会话锁映射，用于保证同一会话的串行处理 */
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    /** 活跃任务映射，记录每个会话当前正在执行的任务 Future */
    private final ConcurrentMap<String, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();
    /** 并发控制信号量，限制同时处理的消息数量 */
    private final Semaphore concurrencyGate;
    /** 线程池执行器，用于异步处理消息 */
    private final ExecutorService executor;
    /** 定时任务调度器，用于执行周期性任务 */
    private final ScheduledExecutorService scheduler;

    /** Agent 循环运行状态标志 */
    private volatile boolean running = false;
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private final AtomicBoolean loopThreadStarted = new AtomicBoolean(false);
    /** 额外的 Agent 钩子列表 */
    private final List<AgentHook> extraHooks = new ArrayList<>();

    /**
     * 构造 AgentLoop 实例。
     *
     * @param bus                消息总线
     * @param provider           LLM 提供者
     * @param workspace          工作空间路径
     * @param model              模型名称
     * @param maxIterations      最大迭代次数
     * @param contextWindowTokens 上下文窗口大小
     * @param contextBlockLimit  上下文块限制
     * @param maxToolResultChars 工具结果最大字符数
     * @param providerRetryMode  提供者重试模式
     * @param webConfig          Web 工具配置
     * @param execConfig         执行工具配置
     * @param mcpServers         MCP 服务器配置
     * @param restrictToWorkspace 是否限制工作空间
     * @param sessionManager     会话管理器
     * @param timezone           时区
     * @param unifiedSession     是否统一会话
     * @param disabledSkills     禁用的技能列表
     * @param sessionTtlMinutes  会话 TTL（分钟，<=0 表示禁用）
     * @param dreamConfig        Dream 配置
     */
    public AgentLoop(
            MessageBus bus,
            LLMProvider provider,
            Path workspace,
            String model,
            Integer maxIterations,
            Integer contextWindowTokens,
            Integer contextBlockLimit,
            Integer maxToolResultChars,
            String providerRetryMode,
            Config.WebToolsConfig webConfig,
            Config.ExecToolConfig execConfig,
            Map<String, Object> mcpServers,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            String timezone,
            boolean unifiedSession,
            List<String> disabledSkills,
            int sessionTtlMinutes,
            Config.DreamConfig dreamConfig
    ) {
        // 获取默认配置
        Config.AgentDefaults defaults = new Config.AgentDefaults();

        // 初始化基础组件
        this.bus = bus;
        this.provider = provider;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.model = model != null ? model : provider.getDefaultModel();

        // 初始化配置参数，使用默认值填充 null 值
        this.maxIterations = maxIterations != null ? maxIterations : defaults.getMaxToolIterations();
        this.contextWindowTokens = contextWindowTokens != null ? contextWindowTokens : defaults.getContextWindowTokens();
        this.contextBlockLimit = contextBlockLimit;
        this.maxToolResultChars = maxToolResultChars != null ? maxToolResultChars : defaults.getMaxToolResultChars();

        this.webConfig = webConfig != null ? webConfig : new Config.WebToolsConfig();
        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        this.dreamConfig = dreamConfig != null ? dreamConfig : defaults.getDream();
        this.mcpServers = mcpServers != null ? mcpServers : Collections.emptyMap();
        this.restrictToWorkspace = restrictToWorkspace;
        this.unifiedSession = unifiedSession;
        this.providerRetryMode = providerRetryMode != null && !providerRetryMode.isBlank()
                ? providerRetryMode
                : defaults.getProviderRetryMode();
        this.sessionTtlMinutes = sessionTtlMinutes > 0 ? sessionTtlMinutes : defaults.getSessionTtlMinutes();

        // 初始化核心组件
        this.contextBuilder = new ContextBuilder(this.workspace, timezone, disabledSkills);
        this.sessionManager = sessionManager != null ? sessionManager : new SessionManager(this.workspace);
        this.memoryStore = new MemoryStore(this.workspace);
        
        // 初始化记忆整合器
        this.consolidator = new Consolidator(
                this.memoryStore,
                this.provider,
                this.model,
                this.sessionManager,
                this.contextWindowTokens,
                4096 // maxCompletionTokens placeholder
        );
        
        // 初始化 Dream 模块
        this.dream = new Dream(this.workspace, this.provider, this.model, this.memoryStore);
        this.autoCompact = new AutoCompact(this.sessionManager, this.consolidator, this.sessionTtlMinutes);
        
        // 初始化子代理管理器
        this.subagents = new SubagentManager(
                this.provider,
                this.workspace,
                this.bus,
                this.maxToolResultChars,
                this.model,
                this.webConfig,
                this.execConfig,
                this.restrictToWorkspace,
                disabledSkills
        );
        
        // 初始化技能加载器
        this.skillsLoader = new SkillsLoader(
                this.workspace,
                null, // builtinDir will be resolved automatically
                disabledSkills != null ? new HashSet<>(disabledSkills) : new HashSet<>()
        );
        
        // 初始化技能路由器，从环境变量读取配置
        this.skillRouter = new SkillRouter(
                this.skillsLoader,
                parseInt(System.getenv("RICBOT_SKILLS_MAX_SELECTED"), 3),
                parseInt(System.getenv("RICBOT_SKILLS_MAX_CHARS"), 12000)
        );
        
        // 初始化定时任务服务
        this.cronService = new CronService(workspace.resolve(".ricbot").resolve("cron").resolve("store.json"));
        this.cronService.setOnJob(this::handleCronJob);

        // 初始化工具注册表和运行器
        this.tools = new ToolRegistry();
        this.runner = new AgentRunner(provider);
        ToolContextApplier toolContextApplier = new ToolContextInjector(this.tools);
        this.hookFactory = new AgentHookFactory(this.bus, toolContextApplier);
        this.sessionPreparationService = new SessionPreparationService(this.sessionManager, this.autoCompact, this.consolidator);
        ContextSelectionService contextSelectionService = new ContextSelectionService(this.memoryStore, new ToolTraceSummarizer());
        this.agentContextService = new AgentContextService(
                this.workspace,
                this.contextBuilder,
                this.memoryStore,
                this.skillsLoader,
                this.skillRouter,
                this.tools,
                this.hookFactory,
                toolContextApplier,
                this.extraHooks,
                contextSelectionService
        );
        this.agentExecutionService = new AgentExecutionService(
                this.runner,
                this.tools,
                this.workspace,
                this.model,
                this.maxIterations,
                this.maxToolResultChars,
                this.providerRetryMode,
                this.contextWindowTokens,
                this.contextBlockLimit
        );
        this.sessionPersistenceService = new SessionPersistenceService(this.sessionManager, this.maxToolResultChars);
        this.mcpLoader = new MCPLoader(this.tools, this.mcpServers);
        this.commandRouter = new CommandRouter();
        this.agentCommands = new AgentCommands(
                this.sessionManager,
                this.memoryStore,
                this.dream,
                this.dreamConfig,
                this.model,
                this.workspace,
                this::effectiveSessionKey,
                activeTasks::remove,
                this::markSessionInterrupted
        );

        // 初始化并发控制
        int maxConcurrent = parseInt(System.getenv("RICBOT_MAX_CONCURRENT_REQUESTS"), 3);
        this.concurrencyGate = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;

        // 初始化线程池
        this.executor = createWorkerExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // 注册默认工具
        registerDefaultTools();
        registerCommandRoutes();
    }

    private static ExecutorService createWorkerExecutor() {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        return new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                r -> {
                    Thread t = new Thread(r, "agent-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // ---------------------------------------------------------------------
    // Tool registration
    // ---------------------------------------------------------------------

    /**
     * 注册默认工具。
     *
     * 最小可展示工具集：read_file / list_dir / exec
     */
    private void registerDefaultTools() {
        // 计算允许的操作目录，如果限制工作空间或启用沙箱，则限制为工作空间
        Path allowedDir = (restrictToWorkspace || execConfig.isSandbox()) ? workspace : null;

        // 注册文件系统工具
        tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
        tools.register(new ListDirTool(workspace, allowedDir));
        tools.register(new WriteFileTool(workspace, allowedDir));
        tools.register(new EditFileTool(workspace, allowedDir));
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        // 注册搜索工具
        tools.register(new GlobTool(workspace, allowedDir));
        tools.register(new GrepTool(workspace, allowedDir));

        // 注册 Shell 执行工具
        if (execConfig.isEnable()) {
            tools.register(new ExecTool(
                    execConfig.getTimeout(),
                    workspace.toString(),
                    null,
                    null,
                    restrictToWorkspace,
                    execConfig.isSandbox() ? "sandbox" : "",
                    execConfig.getPathAppend(),
                    execConfig.getAllowedEnvKeys()
            ));
            tools.register(new SpawnTool(subagents));
        }

        // 注册定时任务工具
        if (cronService != null) {
            tools.register(new CronTool(cronService, contextBuilder.getTimezone()));
        }

        // 注册 Web 工具
        if (webConfig.isEnable()) {
            tools.register(new WebFetchTool(
                    webConfig.getMaxChars(),
                    webConfig.getProxy()
            ));
            tools.register(new WebSearchTool(
                    webConfig.getSearch(),
                    webConfig.getProxy()
            ));
        }

    }

    private void registerCommandRoutes() {
        agentCommands.register(commandRouter);
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    /**
     * 处理定时任务回调。
     *
     * @param job 定时任务对象
     * @return 处理结果字符串
     */
    private String handleCronJob(CronJob job) {
        log.info("执行定时任务: {}", job.getName());
        
        // 构建入站消息
        InboundMessage msg = InboundMessages.of(
                job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "system",
                "cron",
                job.getPayload().getTo() != null ? job.getPayload().getTo() : "cron",
                job.getPayload().getMessage()
        );
        
        // 标记这是一个 cron 任务
        msg.getMetadata().put("_cron_job_id", job.getId());
        msg.getMetadata().put("_cron_job_name", job.getName());
        msg.getMetadata().put("_deliver", job.getPayload().isDeliver());

        // 提交任务到线程池异步分发
        executor.submit(() -> dispatch(msg));
        return "任务已分发";
    }

    /**
     * 启动 Agent 主循环。
     */
    public void start() {
        startBackgroundIfNeeded();
        if (loopThreadStarted.compareAndSet(false, true)) {
            new Thread(this::run, "agent-loop").start();
        }
    }

    /**
     * Agent 主循环运行逻辑。
     */
    public void run() {
        // 标记 Agent 循环为运行状态
        this.running = true;
        // 记录启动日志
        log.info("Agent 循环已启动");

        // 主循环：持续监听 inbound 消息
        while (running) {
            try {
                // 阻塞最多 500ms 等待入站消息，若超时返回 null
                InboundMessage msg = bus.consumeInbound(500, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    // 无消息则继续下一轮循环
                    continue;
                }

                // 获取消息内容并去除首尾空白
                String raw = trim(msg.getContent());

                // /stop 优先处理：立即停止当前会话任务
                if (raw != null && raw.startsWith("/") && commandRouter.isPriority(raw)) {
                    OutboundMessage priorityOut = dispatchCommand(msg, null, effectiveSessionKey(msg), raw, true);
                    if (priorityOut != null) {
                        bus.publishOutbound(priorityOut);
                    }
                    continue;
                }

                // 正常异步分发：计算会话 Key
                String sessionKey = effectiveSessionKey(msg);

                // 提交任务到线程池异步执行
                Future<?> future = executor.submit(() -> {
                    try {
                        // 如果配置了并发限制信号量，则先获取许可
                        if (concurrencyGate != null) {
                            concurrencyGate.acquire();
                        }
                        // 分发消息进行处理
                        dispatch(msg);
                    } catch (InterruptedException e) {
                        // 恢复中断状态
                        Thread.currentThread().interrupt();
                    } finally {
                        // 释放并发限制信号量许可
                        if (concurrencyGate != null) {
                            concurrencyGate.release();
                        }
                    }
                });

                // 将未来任务对象添加到对应会话的活动任务列表中
                activeTasks.computeIfAbsent(sessionKey, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(future);

            } catch (InterruptedException e) {
                // 线程被中断，恢复中断状态并退出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 捕获其他未预期异常，记录日志并继续运行
                log.error("Agent 循环出错", e);
            }
        }
    }

    private void startBackgroundIfNeeded() {
        if (!backgroundStarted.compareAndSet(false, true)) {
            return;
        }
        this.running = true;
        this.cronService.start();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            executor.submit(() -> {
                try {
                    mcpLoader.load();
                } catch (Exception e) {
                    log.error("MCP 加载失败", e);
                }
            });
        }
        if (dreamConfig != null && dreamConfig.isEnabled()) {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    dream.run();
                } catch (Exception e) {
                    log.error("后台 Dream 任务出错", e);
                }
            }, 15, 15, TimeUnit.MINUTES);
        }

        if (sessionTtlMinutes > 0) {
            scheduler.scheduleWithFixedDelay(this::runAutoCompactSweep, 1, 1, TimeUnit.MINUTES);
        }
    }

    public void stop() {
        this.running = false;
        for (String sessionKey : activeTasks.keySet()) {
            markSessionInterrupted(sessionKey, "shutdown");
        }
        this.cronService.stop();
        try {
            subagents.close();
        } catch (Exception e) {
            log.warn("关闭子代理管理器失败", e);
        }
        mcpLoader.close();
        executor.shutdownNow();
        scheduler.shutdownNow();
        log.info("Agent 循环正在停止");
    }

    public Dream getDream() { return dream; }
    public SubagentManager getSubagents() { return subagents; }
    public SessionManager getSessions() { return sessionManager; }
    public Consolidator getConsolidator() { return consolidator; }

    // ---------------------------------------------------------------------
    // Dispatch / processing
    // ---------------------------------------------------------------------

    /**
     * 分发消息到对应的会话进行处理。
     *
     * @param msg 入站消息
     */
    private void dispatch(InboundMessage msg) {
        // 计算有效的会话键，用于确定消息所属的会话
        String sessionKey = effectiveSessionKey(msg);
        // 获取或创建会话锁，确保同一会话的消息串行处理
        Object lock = sessionLocks.computeIfAbsent(sessionKey, k -> new Object());

        // 同步块，保证线程安全
        synchronized (lock) {
            try {
                // 处理消息并获取响应
                OutboundMessage response = processMessage(msg, sessionKey, List.of());
                if (response != null) {
                    // 如果响应不为空，发布出站消息
                    bus.publishOutbound(response);
                } else if ("cli".equals(msg.getChannel())) {
                    // CLI 通道特殊处理：如果响应为空，发送一个空包以结束本轮输出
                    bus.publishOutbound(reply(msg, ""));
                }
            } catch (Exception e) {
                // 捕获处理过程中的异常，记录日志并发送错误消息
                log.error("处理会话 {} 的消息时出错", sessionKey, e);
                try {
                    // 尝试发布错误消息
                    bus.publishOutbound(plainReply(msg, "抱歉，我遇到了一点错误。"));
                } catch (Exception publishErr) {
                    log.warn("发布错误消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), publishErr);
                }
            } finally {
                // 清理已完成的任务引用，防止内存泄漏
                List<Future<?>> tasks = activeTasks.get(sessionKey);
                if (tasks != null) {
                    tasks.removeIf(Future::isDone);
                }
            }
        }
    }

    /**
     * 处理单条用户消息。
     *
     * @param msg          入站消息，包含用户发送的原始内容、渠道、发送者等信息
     * @param sessionKey   会话键，用于标识和隔离不同的对话上下文
     * @param requestHooks 请求级别的 Agent 钩子列表，用于在请求处理过程中插入自定义逻辑
     * @return 出站消息响应，包含 Agent 生成的回复内容
     * @throws Exception 处理过程中可能抛出的异常，如 LLM 调用失败、持久化错误等
     */
    private OutboundMessage processMessage(InboundMessage msg, String sessionKey, List<AgentHook> requestHooks) throws Exception {
        // 检查消息渠道是否为系统通道（system），如果是则委托给系统消息处理器
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }

        // 生成消息内容的预览字符串，用于日志记录，限制长度为 80 字符以避免日志过长
        String preview = msg.getContent() != null && msg.getContent().length() > 80
                ? msg.getContent().substring(0, 80) + "..."
                : String.valueOf(msg.getContent());
        // 记录开始处理消息的日志，包含渠道、发送者和内容预览
        log.info("处理来自 {}:{} 的消息: {}", msg.getChannel(), msg.getSenderId(), preview);

        // 准备交互式会话上下文：加载历史、应用钩子、检查命令等
        // 传入一个回调函数，用于在准备阶段遇到命令时进行分发处理
        PreparedSessionContext prepared = sessionPreparationService.prepareInteractiveTurn(
                msg,
                sessionKey,
                (commandMessage, session, key, raw) -> dispatchCommand(commandMessage, session, key, raw, false)
        );
        // 如果准备阶段产生了立即响应（例如命中了快捷命令或拦截逻辑），直接返回该响应，不再继续后续流程
        if (prepared.immediateResponse() != null) {
            return prepared.immediateResponse();
        }

        // 如果需要，将用户的当前轮次消息持久化到会话存储中，并返回更新后的上下文
        PreparedSessionContext persisted = sessionPreparationService.persistUserTurnIfNeeded(prepared, msg);
        
        // 构建 Agent 请求上下文：组装发送给 LLM 的完整提示词、工具列表、历史消息窗口等
        AgentRequestContext request = agentContextService.buildInteractiveRequest(
                msg,
                persisted,
                requestHooks,
                historyWindowAsMessages() // 计算历史消息窗口大小
        );
        
        // 执行交互式 Agent 循环：调用 LLM，处理工具调用，直到得出最终结论或达到最大迭代次数
        // 传入一个回调函数，用于在执行过程中保存运行时检查点（如任务状态）
        ExecutionOutcome outcome = agentExecutionService.executeInteractive(
                request,
                payload -> storeRuntimeCheckpoint(request.session(), payload)
        );
        
        // 持久化交互式轮次的结果：将 Agent 的回复、工具调用记录等保存到会话存储中
        PersistenceResult persistence = sessionPersistenceService.persistInteractiveTurn(request, outcome);

        // 记录回复日志，包含渠道、发送者和回复内容的缩写（限制 120 字符）
        log.info("回复给 {}:{}: {}", msg.getChannel(), msg.getSenderId(), abbreviate(outcome.finalContent(), 120));
        
        // 从持久化结果中提取并返回最终的出站消息对象
        return persistence.outboundMessage();
    }

    /**
     * 处理系统通道（system channel）的后台消息。
     * 此类消息通常由内部任务（如定时任务、子代理回调）触发，不直接来自用户交互。
     *
     * @param msg 入站消息，包含触发系统处理的相关信息
     * @return 出站消息响应，包含处理结果或状态更新
     * @throws Exception 处理过程中可能抛出的异常，如会话准备失败、LLM 调用错误等
     */
    private OutboundMessage processSystemMessage(InboundMessage msg) throws Exception {
        SystemTarget target = parseSystemTarget(msg.getChatId());
        String channel = target.channel();
        String chatId = target.chatId();
        // 构建唯一的会话键，格式为 "channel:chatId"，用于定位和管理会话状态
        String key = channel + ":" + chatId;

        // 确定当前消息在对话中的角色
        // 如果发送者是 "subagent"，则视为助手（assistant）的后续动作；否则视为用户（user）发起的系统指令
        String currentRole = "subagent".equals(msg.getSenderId()) ? "assistant" : "user";

        // 准备系统轮次的会话上下文
        // 加载相关的历史记忆、会话状态，并为系统消息的处理做预处理
        PreparedSessionContext prepared = sessionPreparationService.prepareSystemTurn(key);

        // 构建系统请求上下文
        // 组装发送给 LLM 的必要信息，包括消息内容、会话上下文、渠道信息、角色以及历史消息窗口大小
        AgentRequestContext request = agentContextService.buildSystemRequest(
                msg,
                prepared,
                channel,
                chatId,
                currentRole,
                historyWindowAsMessages() // 计算并传入历史消息窗口的最大条数
        );

        // 执行系统级的 Agent 逻辑
        // 调用 LLM 进行处理，可能涉及工具调用或状态更新，但不一定产生直接的用户可见回复
        ExecutionOutcome outcome = agentExecutionService.executeSystem(request);

        // 持久化系统轮次的处理结果
        // 将 LLM 的输出、状态变更等保存回会话存储，并生成最终的出站消息对象
        return sessionPersistenceService.persistSystemTurn(msg, channel, chatId, request, outcome).outboundMessage();
    }

    /**
     * 直接调用 Agent 处理逻辑，不通过消息总线（Bus）。
     * 适用于需要同步获取结果的场景，如单元测试或内部 API 调用。
     *
     * @param content   用户输入的内容
     * @param sessionKey 会话键，用于标识和隔离不同的对话上下文
     * @param channel   通信渠道，例如 "cli", "web" 等
     * @param chatId    聊天 ID，用于区分同一渠道下的不同对话
     * @return 出站消息，包含 Agent 的回复内容
     * @throws Exception 处理过程中可能抛出的异常
     */
    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId
    ) throws Exception {
        return processDirect(content, sessionKey, channel, chatId, Map.of(), List.of());
    }

    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            Map<String, Object> metadata,
            List<AgentHook> requestHooks
    ) throws Exception {
        InboundMessage msg = newDirectMessage(content, sessionKey, channel, chatId, metadata);

        String effectiveKey = effectiveSessionKey(msg);
        Object lock = sessionLocks.computeIfAbsent(effectiveKey, key -> new Object());
        synchronized (lock) {
            return processMessage(msg, effectiveKey, requestHooks != null ? requestHooks : List.of());
        }
    }

    /**
     * processDirect 的重载方法，提供默认的参数值。
     * 默认渠道为 "cli"，默认聊天 ID 为 "direct"。
     * 旨在简化调用，对齐项目中已有的调用点。
     *
     * @param content   用户输入的内容
     * @param sessionKey 会话键
     * @return 出站消息
     * @throws Exception 处理过程中可能抛出的异常
     */
    public OutboundMessage processDirect(String content, String sessionKey) throws Exception {
        // 调用全参数版本的 processDirect，传入默认值
        return processDirect(content, sessionKey, "cli", "direct");
    }

    /**
     * 分发并执行命令。
     *
     * @param msg          入站消息，包含用户请求的原始信息
     * @param session      当前会话对象，可能为 null
     * @param sessionKey   会话键，用于标识特定会话
     * @param raw          原始命令字符串，例如 "/stop" 或 "/help"
     * @param priorityOnly 是否仅分发高优先级命令
     * @return 命令执行后的出站消息，如果执行失败或无响应则返回 null
     */
    private OutboundMessage dispatchCommand(
            InboundMessage msg,
            Session session,
            String sessionKey,
            String raw,
            boolean priorityOnly
    ) {
        // 构建命令上下文，封装执行命令所需的所有参数
        CommandRouter.CommandContext ctx = new CommandRouter.CommandContext(msg, session, sessionKey, raw, this);
        try {
            // 根据 priorityOnly 标志决定调用优先分发还是普通分发
            CompletableFuture<OutboundMessage> future = priorityOnly
                    ? commandRouter.dispatchPriority(ctx)
                    : commandRouter.dispatch(ctx);
            
            // 阻塞等待命令执行完成并获取结果，如果 future 为 null 则返回 null
            return future != null ? future.join() : null;
        } catch (Exception e) {
            // 捕获命令执行过程中的异常，记录警告日志
            log.warn("命令执行失败: {}", raw, e);
            // 发生异常时返回 null，表示命令处理失败
            return null;
        }
    }

    /**
     * 计算有效的会话键。
     *
     * @param msg 入站消息
     * @return 会话键
     */
    private String effectiveSessionKey(InboundMessage msg) {
        // 如果启用统一会话且消息中没有覆盖会话键或会话键为空
        if (unifiedSession && (msg.getSessionKeyOverride() == null || msg.getSessionKeyOverride().isBlank())) {
            // 返回统一会话键
            return UNIFIED_SESSION_KEY;
        }
        // 否则返回消息中的会话键
        return msg.getSessionKey();
    }

    private void storeRuntimeCheckpoint(Session session, Map<String, Object> payload) {
        Map<String, Object> checkpoint = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        checkpoint.put("task_state", TaskState.fromSession(session).toMap());
        session.getMetadata().put(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY, checkpoint);
        sessionManager.save(session);
    }

    private InboundMessage newDirectMessage(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            Map<String, Object> metadata
    ) {
        return InboundMessages.of(channel, "user", chatId, content, List.of(), metadata, sessionKey, null);
    }

    private OutboundMessage reply(InboundMessage msg, String content) {
        return OutboundMessages.replyTo(msg, content);
    }

    private OutboundMessage plainReply(InboundMessage msg, String content) {
        return OutboundMessages.of(msg.getChannel(), msg.getChatId(), content);
    }

    private SystemTarget parseSystemTarget(String rawChatId) {
        if (rawChatId != null && rawChatId.contains(":")) {
            String[] parts = rawChatId.split(":", 2);
            return new SystemTarget(parts[0], parts[1]);
        }
        return new SystemTarget("cli", rawChatId);
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /**
     * 计算历史消息窗口大小。
     *
     * @return 最大历史消息数
     */
    private int historyWindowAsMessages() {
        // 根据 contextWindowTokens 近似计算最大历史消息数
        // 设置保守上限，避免历史无限增长
        if (contextWindowTokens <= 0) {
            return 100;
        }
        // 限制在 20 到 200 之间
        return Math.min(200, Math.max(20, contextWindowTokens / 500));
    }

    /**
     * 去除字符串首尾空白。
     *
     * @param s 原始字符串
     * @return 处理后的字符串
     */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return s != null ? Integer.parseInt(s) : def;
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 缩写字符串。
     *
     * @param s   原始字符串
     * @param max 最大长度
     * @return 缩写后的字符串
     */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void markSessionInterrupted(String sessionKey, String reason) {
        try {
            Session session = sessionManager.getOrCreate(sessionKey);
            session.getMetadata().put("_last_interrupt_reason", reason);
            sessionManager.save(session);
        } catch (Exception e) {
            log.debug("记录会话中断原因失败: sessionKey={}, reason={}", sessionKey, reason, e);
        }
    }

    private void runAutoCompactSweep() {
        try {
            autoCompact.checkExpired(executor, activeTasks.keySet());
        } catch (Exception e) {
            log.warn("自动归档扫描失败", e);
        }
    }

    // ---------------------------------------------------------------------
    // extra hooks
    // ---------------------------------------------------------------------

    /**
     * 获取额外的钩子列表。
     *
     * @return 钩子列表
     */
    public List<AgentHook> getExtraHooks() {
        return extraHooks;
    }

    /**
     * 设置额外的钩子列表。
     *
     * @param extraHooks 钩子列表
     */
    public void setExtraHooks(List<AgentHook> extraHooks) {
        this.extraHooks.clear();
        if (extraHooks != null) {
            this.extraHooks.addAll(extraHooks);
        }
    }

    private record SystemTarget(String channel, String chatId) {
    }
}
