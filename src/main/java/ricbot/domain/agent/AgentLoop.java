package ricbot.domain.agent;

import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.skill.SkillRouter;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.config.ProviderCapability;
import ricbot.domain.config.ProviderCapabilityResolver;
import ricbot.domain.note.NoteService;
import ricbot.domain.rag.WorkspaceRagService;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.subagent.SubagentManager;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.hook.AgentHook;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.NotebookEditTool;
import ricbot.tool.process.SpawnTool;
import ricbot.tool.skill.ReadSkillTool;
import ricbot.integration.mcp.MCPLoader;
import ricbot.integration.command.CommandRouter;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.infra.config.Config;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
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
    /** 入站消息轮询超时 */
    private static final int INBOUND_POLL_TIMEOUT_MS = 500;

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
    /** MCP 服务器配置映射 */
    private final Map<String, Object> mcpServers;
    /** 是否限制操作仅在工作空间内 */
    private final boolean restrictToWorkspace;
    /** 是否启用统一会话模式 */
    private final boolean unifiedSession;
    /** Provider 重试模式（透传到运行规格） */
    private final String providerRetryMode;
    /** 静态/启发式 Provider capability，用于运行时保守降级。 */
    private ProviderCapability providerCapability;
    /** 会话自动归档 TTL（分钟），0 表示禁用 */
    private final int sessionTtlMinutes;

    /** 上下文构建器，用于构建发送给 LLM 的消息上下文 */
    private final ContextBuilder contextBuilder;
    /** 会话管理器，负责会话的创建、加载和保存 */
    private final SessionManager sessionManager;
    /** 独立于会话转录的运行时检查点存储。 */
    private final RunCheckpointStore runCheckpointStore;
    /** 追加式运行事件与工具调用账本。 */
    private final RunJournalStore runJournalStore;
    private final RunEventSink runEventSink;
    private final OpenTelemetryRuntime telemetryRuntime;
    /** Durable write-tool idempotency and compensation ledger. */
    private final SideEffectStore sideEffectStore;
    private final SideEffectApplicationService sideEffectApplicationService;
    /** 记忆存储，用于长期记忆管理 */
    private final MemoryStore memoryStore;
    /** 记忆整合器，用于压缩和整理历史消息 */
    private final Consolidator consolidator;
    private final ApprovalService approvalService;
    private final TraceStore traceStore;
    /** 会话自动归档器 */
    private final AutoCompact autoCompact;
    /** 子代理管理器，用于管理子代理任务 */
    private final SubagentManager subagents;
    /** 技能加载器，用于加载可用技能 */
    private final SkillsLoader skillsLoader;
    /** 技能路由器，用于根据上下文选择合适技能 */
    private final SkillRouter skillRouter;
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
            int sessionTtlMinutes
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
        this.mcpServers = mcpServers != null ? mcpServers : Collections.emptyMap();
        this.restrictToWorkspace = restrictToWorkspace;
        this.unifiedSession = unifiedSession;
        this.providerRetryMode = providerRetryMode != null && !providerRetryMode.isBlank()
                ? providerRetryMode
                : defaults.getProviderRetryMode();
        this.sessionTtlMinutes = sessionTtlMinutes > 0 ? sessionTtlMinutes : defaults.getSessionTtlMinutes();
        this.providerCapability = new ProviderCapabilityResolver().resolve(
                null,
                this.model,
                this.provider != null ? this.provider.getApiBase() : null,
                this.contextWindowTokens,
                defaults.getMaxTokens()
        );

        // 初始化核心组件
        this.contextBuilder = new ContextBuilder(this.workspace, timezone, disabledSkills);
        AgentPersistenceComponents persistence = AgentPersistenceFactory.create(this.workspace, sessionManager);
        this.sessionManager = persistence.sessionManager();
        this.runCheckpointStore = persistence.checkpointStore();
        this.runJournalStore = persistence.journalStore();
        this.telemetryRuntime = OpenTelemetryRuntime.fromEnvironment();
        this.runEventSink = RunEventSink.composite(
                this.runJournalStore,
                new OpenTelemetryRunEventSink(
                        this.telemetryRuntime.tracer("ricbot.agent", "1.0"))
        );
        this.traceStore = new TraceStore(this.workspace);
        this.sideEffectStore = new AuditedSideEffectStore(persistence.sideEffectStore(), this.traceStore);
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
        
        this.approvalService = new ApprovalService(this.traceStore);
        this.sideEffectApplicationService = new SideEffectApplicationService(
                this.sideEffectStore, this.approvalService);
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
        
        // 初始化工具注册表和运行器
        this.tools = new ToolRegistry();
        this.runner = new AgentRunner(provider);
        ToolContextApplier toolContextApplier = new ToolContextInjector(this.tools);
        this.hookFactory = new AgentHookFactory(this.bus, toolContextApplier);
        this.sessionPreparationService = new SessionPreparationService(
                this.sessionManager,
                this.autoCompact,
                this.consolidator,
                this.runCheckpointStore,
                this.runJournalStore,
                new RunRecoveryCoordinator(this.runJournalStore, this.tools)
        );
        ContextSelectionService contextSelectionService = new ContextSelectionService(
                this.memoryStore,
                new ToolTraceSummarizer(),
                this.contextWindowTokens,
                new NoteService(this.workspace),
                new WorkspaceRagService(this.workspace),
                new ExperienceStore(this.workspace),
                this.traceStore
        );
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
                this.contextBlockLimit,
                this.providerCapability,
                this.runEventSink,
                this.sideEffectStore
        );
        this.sessionPersistenceService = new SessionPersistenceService(this.sessionManager, this.maxToolResultChars, this.memoryStore);
        this.mcpLoader = new MCPLoader(this.tools, this.mcpServers);
        this.commandRouter = new CommandRouter();
        this.agentCommands = new AgentCommands(
                this.sessionManager,
                this.memoryStore,
                this.model,
                this.workspace,
                this::effectiveSessionKey,
                activeTasks::remove,
                this::markSessionInterrupted,
                this.approvalService,
                this.tools,
                new AgentTeamWorkerRunner(
                        this.workspace,
                        this.runner,
                        this.model,
                        Math.min(8, Math.max(1, this.maxIterations)),
                        this.maxToolResultChars,
                        this.providerRetryMode,
                        this.contextWindowTokens,
                        this.contextBlockLimit,
                        this.providerCapability
                )
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

    public void setProviderCapability(ProviderCapability providerCapability) {
        if (providerCapability != null) {
            this.providerCapability = providerCapability;
        }
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
        Path allowedDir = BuiltinToolRegistrar.allowedDir(workspace, restrictToWorkspace, execConfig);
        ApprovalService toolApprovalService = execConfig != null && execConfig.isApprovalEnabled()
                ? approvalService
                : null;

        tools.register(new ReadSkillTool(skillsLoader));
        BuiltinToolRegistrar.registerFileAndSearchTools(tools, workspace, allowedDir, toolApprovalService);
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        if (execConfig.isEnable()) {
            BuiltinToolRegistrar.registerExecTool(tools, workspace, restrictToWorkspace, execConfig, toolApprovalService);
            tools.register(new SpawnTool(subagents));
        }

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
     * 启动 Agent 主循环。
     * <p>
     * 该方法首先确保后台服务（如 MCP 加载等）已启动，
     * 然后使用 CAS 操作保证 Agent 主循环线程只被创建和启动一次。
     */
    public void start() {
        // 启动必要的后台服务，内部有幂等性保护
        startBackgroundIfNeeded();
        
        // 尝试将 loopThreadStarted 标志从 false 设置为 true
        // 如果设置成功，说明这是第一次调用 start()，需要创建并启动主循环线程
        if (loopThreadStarted.compareAndSet(false, true)) {
            // 创建名为 "agent-loop" 的新线程，执行 run() 方法，并立即启动
            new Thread(this::run, "agent-loop").start();
        }
    }

    /**
     * Agent 主循环运行逻辑。
     */
    public void run() {
        // 标记 Agent 循环为运行状态 - 设置 running 标志为 true，表示 Agent 开始运行
        this.running = true;
        // 记录启动日志 - 输出一条 INFO 级别的日志，表明 Agent 循环已启动
        log.info("Agent 循环已启动");

        // 主循环：持续监听 inbound 消息 - 这是一个无限循环，只要 running 为 true 就会一直运行
        while (running) {
            try {
                // 阻塞最多 500ms 等待入站消息，若超时返回 null - 从消息总线消费消息，最多等待 500 毫秒
                InboundMessage msg = bus.consumeInbound(INBOUND_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    // 无消息则继续下一轮循环 - 如果没有收到消息（超时），则跳过本次循环，继续等待下一条消息
                    continue;
                }

                // 获取消息内容并去除首尾空白 - 调用 trim 方法移除消息内容开头和结尾的空白字符
                String raw = trim(msg.getContent());

                // /stop 优先处理：立即停止当前会话任务 - 检查消息是否为以 "/" 开头的优先级命令
                if (raw.startsWith("/") && commandRouter.isPriority(raw)) {
                    // 分发优先级命令并获取响应 - 调用 dispatchCommand 方法处理优先级命令
                    OutboundMessage priorityOut = dispatchCommand(msg, null, effectiveSessionKey(msg), raw, true);
                    if (priorityOut != null) {
                        // 如果有响应消息，则发布到出站消息总线 - 将命令执行结果发送出去
                        bus.publishOutbound(priorityOut);
                    }
                    // 跳过正常消息处理流程 - 优先级命令处理完后直接进入下一次循环
                    continue;
                }

                submitDispatchTask(msg);

            } catch (InterruptedException e) {
                // 线程被中断，恢复中断状态并退出循环 - 如果主循环线程被中断，恢复中断状态并跳出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 捕获其他未预期异常，记录日志并继续运行 - 出现非中断异常时，记录错误但不停止整个循环
                log.error("Agent 循环出错", e);
            }
        }
    }

    /**
     * 按需启动后台服务。
     * 使用 CAS 确保该方法只被执行一次，避免重复启动后台任务。
     */
    private void startBackgroundIfNeeded() {
        // 尝试将 backgroundStarted 从 false 设置为 true，如果设置失败（说明已经启动过），则直接返回
        if (!backgroundStarted.compareAndSet(false, true)) {
            return;
        }
        // 标记 Agent 循环为运行状态
        this.running = true;
        // 如果配置了 MCP 服务器且不为空，则异步加载 MCP 服务
        if (mcpServers != null && !mcpServers.isEmpty()) {
            executor.submit(() -> {
                try {
                    // 加载 MCP 服务
                    mcpLoader.load();
                } catch (Exception e) {
                    // 记录 MCP 加载失败的错误日志
                    log.error("MCP 加载失败", e);
                }
            });
        }
        
        // 如果设置了会话自动归档 TTL（大于 0），则调度定期执行自动归档扫描
        if (sessionTtlMinutes > 0) {
            // 每 1 分钟执行一次自动归档扫描，初始延迟为 1 分钟
            scheduler.scheduleWithFixedDelay(this::runAutoCompactSweep, 1, 1, TimeUnit.MINUTES);
        }
    }

    public void stop() {
        this.running = false;
        for (String sessionKey : activeTasks.keySet()) {
            markSessionInterrupted(sessionKey, "shutdown");
        }
        try {
            subagents.close();
        } catch (Exception e) {
            log.warn("关闭子代理管理器失败", e);
        }
        mcpLoader.close();
        telemetryRuntime.close();
        executor.shutdownNow();
        scheduler.shutdownNow();
        log.info("Agent 循环正在停止");
    }

    public SubagentManager getSubagents() { return subagents; }
    public SessionManager getSessions() { return sessionManager; }
    public ToolRegistry getTools() { return tools; }
    public ApprovalService getApprovalService() { return approvalService; }
    public SideEffectApplicationService getSideEffectApplicationService() { return sideEffectApplicationService; }
    public MessageBus getBus() { return bus; }

    public MCPLoader getMcpLoader() { return mcpLoader; }

    public MemoryStore getMemoryStore() { return memoryStore; }
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
        try {
            withSessionLock(sessionKey, () -> {
                OutboundMessage response = processMessage(msg, sessionKey, List.of());
                if (response != null) {
                    bus.publishOutbound(response);
                } else if ("cli".equals(msg.getChannel())) {
                    bus.publishOutbound(reply(msg, ""));
                }
                return null;
            });
        } catch (Exception e) {
            log.error("处理会话 {} 的消息时出错", sessionKey, e);
            publishProcessingError(msg);
        } finally {
            cleanupCompletedTasks(sessionKey);
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
        deleteRunCheckpoint(request.session().getKey());

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
        ExecutionOutcome outcome = agentExecutionService.executeSystem(
                request,
                payload -> storeRuntimeCheckpoint(request.session(), payload)
        );

        // 持久化系统轮次的处理结果
        // 将 LLM 的输出、状态变更等保存回会话存储，并生成最终的出站消息对象
        PersistenceResult persistence = sessionPersistenceService.persistSystemTurn(
                msg,
                channel,
                chatId,
                request,
                outcome
        );
        deleteRunCheckpoint(request.session().getKey());
        return persistence.outboundMessage();
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

    /**
     * 直接调用 Agent 处理逻辑，支持自定义元数据和请求级钩子。
     * 该方法绕过消息总线，同步执行消息处理流程，适用于测试或内部直接调用场景。
     *
     * @param content      用户输入的内容
     * @param sessionKey   会话键，用于标识和隔离不同的对话上下文
     * @param channel      通信渠道，例如 "cli", "web" 等
     * @param chatId       聊天 ID，用于区分同一渠道下的不同对话
     * @param metadata     附加的元数据映射，可包含额外的上下文信息
     * @param requestHooks 请求级别的 Agent 钩子列表，用于在请求处理过程中插入自定义逻辑
     * @return 出站消息，包含 Agent 的回复内容
     * @throws Exception 处理过程中可能抛出的异常，如 LLM 调用失败、持久化错误等
     */
    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            Map<String, Object> metadata,
            List<AgentHook> requestHooks
    ) throws Exception {
        // 创建入站消息对象，封装用户输入及上下文信息
        InboundMessage msg = newDirectMessage(content, sessionKey, channel, chatId, metadata);

        // 计算有效的会话键，处理统一会话模式或覆盖逻辑
        String effectiveKey = effectiveSessionKey(msg);

        // 处理消息并返回结果，如果请求钩子为 null 则使用空列表
        return withSessionLock(
                effectiveKey,
                () -> processMessage(msg, effectiveKey, requestHooks != null ? requestHooks : List.of())
        );
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
            OutboundMessage result = future != null ? future.join() : null;
            if (result != null) {
                return result;
            }
            if (raw != null && raw.trim().startsWith("/")) {
                return OutboundMessages.replyTo(msg, "command error: unknown command: " + raw.trim().split("\\s+", 2)[0]);
            }
            return null;
        } catch (Exception e) {
            // 捕获命令执行过程中的异常，记录警告日志
            log.warn("命令执行失败: {}", raw, e);
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            return OutboundMessages.replyTo(msg, "command error: " + message);
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

    /**
     * 存储运行时检查点。
     * <p>
     * 将当前的任务状态和额外的负载数据保存到会话的元数据中，并持久化会话。
     * 这用于在长时间运行的任务中保存中间状态，以便在中断后恢复或进行调试。
     *
     * @param session 当前会话对象
     * @param payload 额外的负载数据映射，可能包含特定的上下文信息
     */
    private void storeRuntimeCheckpoint(Session session, Map<String, Object> payload) {
        Map<String, Object> enrichedPayload = payload != null
                ? new LinkedHashMap<>(payload)
                : new LinkedHashMap<>();
        enrichedPayload.putIfAbsent("session_message_count", session.getMessages().size());
        RunCheckpoint checkpoint = RunCheckpoint.fromPayload(
                session.getKey(),
                enrichedPayload,
                TaskState.fromSession(session).toMap()
        );
        // Persist the standalone snapshot first. If the process exits before
        // the session write, SessionPreparationService can still recover it.
        runCheckpointStore.save(checkpoint);
        session.getMetadata().put(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY, checkpoint.toSessionPayload());
        // 保存更新后的会话到持久化存储
        sessionManager.save(session);
    }

    private void deleteRunCheckpoint(String sessionKey) {
        try {
            runCheckpointStore.delete(sessionKey);
        } catch (RuntimeException e) {
            // The committed checkpoint id is stored with the session before
            // this cleanup, preventing a stale file from being restored twice.
            log.warn("清理 durable run checkpoint 失败: sessionKey={}", sessionKey, e);
        }
    }

    /**
     * 创建直接调用的入站消息。
     * <p>
     * 用于绕过消息总线，直接构造一个模拟用户输入的 InboundMessage 对象。
     *
     * @param content   消息内容
     * @param sessionKey 会话键
     * @param channel   通信渠道（如 "cli"）
     * @param chatId    聊天 ID
     * @param metadata  附加的元数据
     * @return 构造好的 InboundMessage 对象
     */
    private InboundMessage newDirectMessage(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            Map<String, Object> metadata
    ) {
        // 调用 InboundMessages 工厂方法创建消息，发送者固定为 "user"，附件列表为空，时间戳为 null
        return InboundMessages.of(channel, "user", chatId, content, List.of(), metadata, sessionKey, null);
    }

    /**
     * 生成回复消息。
     * <p>
     * 基于原始入站消息生成一个标准的回复出站消息。
     *
     * @param msg     原始入站消息
     * @param content 回复内容
     * @return 出站消息对象
     */
    private OutboundMessage reply(InboundMessage msg, String content) {
        // 使用 OutboundMessages 工具类生成回复，保持原有的渠道和聊天 ID 关联
        return OutboundMessages.replyTo(msg, content);
    }

    /**
     * 生成纯文本回复消息。
     * <p>
     * 创建一个简单的出站消息，不包含复杂的回复结构，仅包含渠道、聊天 ID 和内容。
     *
     * @param msg     原始入站消息，用于提取渠道和聊天 ID
     * @param content 回复内容
     * @return 出站消息对象
     */
    private OutboundMessage plainReply(InboundMessage msg, String content) {
        // 直接构造出站消息，不使用 replyTo 的完整上下文关联逻辑
        return OutboundMessages.of(msg.getChannel(), msg.getChatId(), content);
    }

    /**
     * 解析系统目标标识。
     * <p>
     * 从原始的 chatId 字符串中解析出渠道（channel）和具体的聊天 ID（chatId）。
     * 格式预期为 "channel:chatId"。如果格式不符合，则默认渠道为 "cli"。
     *
     * @param rawChatId 原始的聊天 ID 字符串
     * @return 解析后的 SystemTarget 记录对象
     */
    private SystemTarget parseSystemTarget(String rawChatId) {
        // 检查 rawChatId 是否非空且包含分隔符 ":"
        if (rawChatId != null && rawChatId.contains(":")) {
            // 按 ":" 分割字符串，限制分割次数为 2，以处理 chatId 本身包含 ":" 的情况
            String[] parts = rawChatId.split(":", 2);
            // 返回解析出的渠道和聊天 ID
            return new SystemTarget(parts[0], parts[1]);
        }
        // 如果格式不匹配，默认渠道为 "cli"，整个字符串作为 chatId
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

    private void submitDispatchTask(InboundMessage msg) {
        String sessionKey = effectiveSessionKey(msg);
        Future<?> future = executor.submit(() -> {
            boolean permitAcquired = false;
            try {
                permitAcquired = acquireConcurrencyPermit();
                dispatch(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                releaseConcurrencyPermit(permitAcquired);
            }
        });
        registerActiveTask(sessionKey, future);
    }

    private boolean acquireConcurrencyPermit() throws InterruptedException {
        if (concurrencyGate != null) {
            concurrencyGate.acquire();
            return true;
        }
        return false;
    }

    private void releaseConcurrencyPermit(boolean permitAcquired) {
        if (permitAcquired && concurrencyGate != null) {
            concurrencyGate.release();
        }
    }

    private void registerActiveTask(String sessionKey, Future<?> future) {
        activeTasks.computeIfAbsent(sessionKey, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(future);
    }

    private void cleanupCompletedTasks(String sessionKey) {
        List<Future<?>> tasks = activeTasks.get(sessionKey);
        if (tasks != null) {
            tasks.removeIf(Future::isDone);
        }
    }

    private void publishProcessingError(InboundMessage msg) {
        try {
            bus.publishOutbound(plainReply(msg, "抱歉，我遇到了一点错误。"));
        } catch (Exception publishErr) {
            log.warn("发布错误消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), publishErr);
        }
    }

    private <T> T withSessionLock(String sessionKey, SessionWork<T> work) throws Exception {
        Object lock = sessionLocks.computeIfAbsent(sessionKey, key -> new Object());
        synchronized (lock) {
            return work.run();
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
            runCheckpointStore.load(sessionKey)
                    .map(checkpoint -> checkpoint.withInterruptionReason(reason))
                    .ifPresent(runCheckpointStore::save);
            runJournalStore.pauseLatestInterrupted(sessionKey, reason);
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

    @FunctionalInterface
    private interface SessionWork<T> {
        T run() throws Exception;
    }

    private record SystemTarget(String channel, String chatId) {
    }
}
