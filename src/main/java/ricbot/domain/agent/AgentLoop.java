package ricbot.domain.agent;

import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillRoutingContext;
import ricbot.infra.cron.CronService;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.Dream;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.subagent.SubagentManager;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
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
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.infra.common.HelperUtils;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.infra.template.ToolHintFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：ricbot 的核心调度引擎。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    public static final String UNIFIED_SESSION_KEY = "unified:default";
    private static final String RUNTIME_CHECKPOINT_KEY = "runtime_checkpoint";
    private static final String PENDING_USER_TURN_KEY = "pending_user_turn";

    private final MessageBus bus;
    private final LLMProvider provider;
    private final Path workspace;
    private final String model;

    private final int maxIterations;
    private final int contextWindowTokens;
    private final Integer contextBlockLimit;
    private final int maxToolResultChars;

    private final Config.WebToolsConfig webConfig;
    private final Config.ExecToolConfig execConfig;
    private final Config.DreamConfig dreamConfig;
    private final Map<String, Object> mcpServers;
    private final boolean restrictToWorkspace;
    private final boolean unifiedSession;
    private final String providerRetryMode;
    private final int sessionTtlMinutes;

    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Consolidator consolidator;
    private final Dream dream;
    private final AutoCompact autoCompact;
    private final SubagentManager subagents;
    private final SkillsLoader skillsLoader;
    private final SkillRouter skillRouter;
    private final CronService cronService;
    private final ToolRegistry tools;
    private final AgentRunner runner;
    private final MCPLoader mcpLoader;
    private final CommandRouter commandRouter;

    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();
    private final Semaphore concurrencyGate;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    private volatile boolean running = false;
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private final AtomicBoolean loopThreadStarted = new AtomicBoolean(false);
    private List<AgentHook> extraHooks = new ArrayList<>();

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
        Config.AgentDefaults defaults = new Config.AgentDefaults();

        this.bus = bus;
        this.provider = provider;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.model = model != null ? model : provider.getDefaultModel();

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

        this.contextBuilder = new ContextBuilder(this.workspace, timezone, disabledSkills);
        this.sessionManager = sessionManager != null ? sessionManager : new SessionManager(this.workspace);
        this.memoryStore = new MemoryStore(this.workspace);
        
        this.consolidator = new Consolidator(
                this.memoryStore,
                this.provider,
                this.model,
                this.sessionManager,
                this.contextWindowTokens,
                4096
        );
        
        this.dream = new Dream(this.provider, this.model, this.memoryStore);
        this.autoCompact = new AutoCompact(this.sessionManager, this.consolidator, this.sessionTtlMinutes);
        
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
        
        this.skillsLoader = new SkillsLoader(
                this.workspace,
                null,
                disabledSkills != null ? new HashSet<>(disabledSkills) : new HashSet<>()
        );
        
        this.skillRouter = new SkillRouter(
                this.skillsLoader,
                parseInt(System.getenv("RICBOT_SKILLS_MAX_SELECTED"), 3),
                parseInt(System.getenv("RICBOT_SKILLS_MAX_CHARS"), 12000)
        );
        
        this.cronService = new CronService(workspace.resolve(".ricbot").resolve("cron").resolve("store.json"));
        this.cronService.setOnJob(this::handleCronJob);

        this.tools = new ToolRegistry();
        this.runner = new AgentRunner(provider);
        this.mcpLoader = new MCPLoader(this.tools, this.mcpServers);
        this.commandRouter = new CommandRouter();

        int maxConcurrent = parseInt(System.getenv("RICBOT_MAX_CONCURRENT_REQUESTS"), 3);
        this.concurrencyGate = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;

        this.executor = Executors.newCachedThreadPool();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        registerDefaultTools();
        registerCommandRoutes();
    }

    private void registerDefaultTools() {
        Path allowedDir = (restrictToWorkspace || execConfig.isSandbox()) ? workspace : null;

        tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
        tools.register(new ListDirTool(workspace, allowedDir));
        tools.register(new WriteFileTool(workspace, allowedDir));
        tools.register(new EditFileTool(workspace, allowedDir));
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        tools.register(new GlobTool(workspace, allowedDir));
        tools.register(new GrepTool(workspace, allowedDir));

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

        if (cronService != null) {
            tools.register(new CronTool(cronService, contextBuilder.getTimezone()));
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
        commandRouter.priority("/stop", this::cmdStop);
        commandRouter.priority("/restart", this::cmdDisabled);
        commandRouter.exact("/new", this::cmdNew);
        commandRouter.exact("/help", this::cmdHelp);
        commandRouter.exact("/status", this::cmdStatus);

        commandRouter.exact("/dream", this::cmdDream);
        commandRouter.exact("/dream-log", this::cmdDreamLog);
        commandRouter.prefix("/dream-log ", this::cmdDreamLog);
        commandRouter.exact("/dream-restore", this::cmdDreamRestore);
        commandRouter.prefix("/dream-restore ", this::cmdDreamRestore);
    }

    private String handleCronJob(CronJob job) {
        log.info("执行定时任务: {}", job.getName());
        
        InboundMessage msg = new InboundMessage();
        msg.setChannel(job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "system");
        msg.setChatId(job.getPayload().getTo() != null ? job.getPayload().getTo() : "cron");
        msg.setContent(job.getPayload().getMessage());
        msg.setSenderId("cron");
        
        msg.getMetadata().put("_cron_job_id", job.getId());
        msg.getMetadata().put("_cron_job_name", job.getName());
        msg.getMetadata().put("_deliver", job.getPayload().isDeliver());

        executor.submit(() -> dispatch(msg));
        return "任务已分发";
    }

    public void start() {
        startBackgroundIfNeeded();
        if (loopThreadStarted.compareAndSet(false, true)) {
            new Thread(this::run, "agent-loop").start();
        }
    }

    public void run() {
        this.running = true;
        log.info("Agent 循环已启动");

        while (running) {
            try {
                InboundMessage msg = bus.consumeInbound(500, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    continue;
                }

                String raw = trim(msg.getContent());

                if (raw != null && raw.startsWith("/") && commandRouter.isPriority(raw)) {
                    OutboundMessage priorityOut = dispatchCommand(msg, null, effectiveSessionKey(msg), raw, true);
                    if (priorityOut != null) {
                        bus.publishOutbound(priorityOut);
                    }
                    continue;
                }

                String sessionKey = effectiveSessionKey(msg);

                Future<?> future = executor.submit(() -> {
                    try {
                        if (concurrencyGate != null) {
                            concurrencyGate.acquire();
                        }
                        dispatch(msg);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (concurrencyGate != null) {
                            concurrencyGate.release();
                        }
                    }
                });

                activeTasks.computeIfAbsent(sessionKey, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(future);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
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

    public SessionManager getSessions() { return sessionManager; }

    private void dispatch(InboundMessage msg) {
        String sessionKey = effectiveSessionKey(msg);
        Object lock = sessionLocks.computeIfAbsent(sessionKey, k -> new Object());

        synchronized (lock) {
            try {
                OutboundMessage response = processMessage(msg, sessionKey);
                if (response != null) {
                    bus.publishOutbound(response);
                } else if ("cli".equals(msg.getChannel())) {
                    OutboundMessage empty = new OutboundMessage();
                    empty.setChannel(msg.getChannel());
                    empty.setChatId(msg.getChatId());
                    empty.setContent("");
                    empty.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
                    bus.publishOutbound(empty);
                }
            } catch (Exception e) {
                log.error("处理会话 {} 的消息时出错", sessionKey, e);
                OutboundMessage error = new OutboundMessage();
                error.setChannel(msg.getChannel());
                error.setChatId(msg.getChatId());
                error.setContent("抱歉，我遇到了一点错误。");
                error.setMetadata(new HashMap<>());
                try {
                    bus.publishOutbound(error);
                } catch (Exception publishErr) {
                    log.warn("发布错误消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), publishErr);
                }
            } finally {
                List<Future<?>> tasks = activeTasks.get(sessionKey);
                if (tasks != null) {
                    tasks.removeIf(Future::isDone);
                }
            }
        }
    }

    private OutboundMessage processMessage(InboundMessage msg, String sessionKey) throws Exception {
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }

        String preview = msg.getContent() != null && msg.getContent().length() > 80
                ? msg.getContent().substring(0, 80) + "..."
                : String.valueOf(msg.getContent());
        log.info("处理来自 {}:{} 的消息: {}", msg.getChannel(), msg.getSenderId(), preview);

        Session baseSession = sessionManager.getOrCreate(sessionKey);
        AutoCompact.PreparedSession prepared = autoCompact.prepareSession(baseSession, sessionKey);
        String summaryContext = prepared != null ? prepared.summary() : null;
        Session session = prepared != null ? prepared.session() : baseSession;

        consolidator.maybeConsolidateByTokens(session);

        restoreRuntimeCheckpoint(session);
        restorePendingUserTurn(session);

        String raw = trim(msg.getContent());
        if (raw != null && raw.startsWith("/")) {
            OutboundMessage commandOut = dispatchCommand(msg, session, sessionKey, raw, false);
            if (commandOut != null) {
                return commandOut;
            }
        }

        setToolContext(msg.getChannel(), msg.getChatId(), messageIdOf(msg));

        String memoryContext = memoryStore.getMemoryContext();
        String skillsContext = skillsLoader.getSkillsContext();
        SkillRouter.SelectionResult selected = skillRouter.selectAndRender(new SkillRoutingContext(
                workspace,
                msg.getChannel(),
                msg.getChatId(),
                msg.getContent(),
                tools.toolNames(),
                msg.getMetadata(),
                Map.of()
        ));

        String combinedContext = (memoryContext != null ? memoryContext : "")
                + "\n" + (skillsContext != null ? skillsContext : "")
                + (summaryContext != null && !summaryContext.isBlank()
                ? "\n" + summaryContext
                : "")
                + (selected.renderedContext() != null && !selected.renderedContext().isBlank()
                ? "\n" + selected.renderedContext()
                : "");

        List<Map<String, Object>> history = session.getHistory(historyWindowAsMessages());
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId(),
                combinedContext,
                "user"
        );

        boolean userPersistedEarly = false;
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            session.addMessage("user", msg.getContent());
            markPendingUserTurn(session);
            sessionManager.save(session);
            userPersistedEarly = true;
        }

        AgentHook hook = buildLoopHook(msg);

        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(initialMessages)
                .setTools(tools)
                .setModel(model)
                .setMaxIterations(maxIterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setHook(hook)
                .setProviderRetryMode(providerRetryMode)
                .setErrorMessage("抱歉，调用模型时遇到错误。")
                .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + maxIterations + "），但仍未完成任务。可尝试提高该值（例如 12 或 16）后重试。")
                .setConcurrentTools(true)
                .setWorkspace(workspace)
                .setSessionKey(session.getKey())
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit)
                .setCheckpointCallback(payload -> setRuntimeCheckpoint(session, payload));

        AgentRunResult runResult = runner.run(spec);
        if (hook == null || !hook.wantsStreaming()) {
            if ("tool_loop".equals(runResult.getStopReason()) || "tool_error_loop".equals(runResult.getStopReason())) {
                int bumped = Math.min(30, Math.max(maxIterations + 6, maxIterations * 2));
                if (bumped > maxIterations) {
                    AgentRunSpec retrySpec = new AgentRunSpec()
                            .setInitialMessages(runResult.getMessages())
                            .setTools(tools)
                            .setModel(model)
                            .setMaxIterations(bumped)
                            .setMaxToolResultChars(maxToolResultChars)
                            .setHook(hook)
                            .setProviderRetryMode(providerRetryMode)
                            .setErrorMessage("抱歉，调用模型时遇到错误。")
                            .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + bumped + "），但仍未完成任务。可尝试继续提高该值后重试。")
                            .setConcurrentTools(true)
                            .setWorkspace(workspace)
                            .setSessionKey(session.getKey())
                            .setContextWindowTokens(contextWindowTokens)
                            .setContextBlockLimit(contextBlockLimit)
                            .setCheckpointCallback(payload -> setRuntimeCheckpoint(session, payload));
                    runResult = runner.run(retrySpec);
                }
            }
        }

        String finalContent = runResult.getFinalContent();
        if (RuntimeUtils.isBlankText(finalContent)) {
            finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
        }

        int saveSkip = 1 + history.size() + (userPersistedEarly ? 1 : 0);
        saveTurn(session, runResult.getMessages(), saveSkip);

        clearPendingUserTurn(session);
        clearRuntimeCheckpoint(session);
        session.getMetadata().remove("_last_interrupt_reason");
        sessionManager.save(session);

        log.info("回复给 {}:{}: {}", msg.getChannel(), msg.getSenderId(), abbreviate(finalContent, 120));

        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(finalContent);
        out.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
        return out;
    }

    private OutboundMessage processSystemMessage(InboundMessage msg) throws Exception {
        String[] parts = msg.getChatId() != null && msg.getChatId().contains(":")
                ? msg.getChatId().split(":", 2)
                : new String[]{"cli", msg.getChatId()};

        String channel = parts[0];
        String chatId = parts[1];
        String key = channel + ":" + chatId;

        Session session = sessionManager.getOrCreate(key);

        restoreRuntimeCheckpoint(session);
        restorePendingUserTurn(session);

        setToolContext(channel, chatId, messageIdOf(msg));

        String currentRole = "subagent".equals(msg.getSenderId()) ? "assistant" : "user";

        List<Map<String, Object>> history = session.getHistory(historyWindowAsMessages());
        List<Map<String, Object>> messages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                null,
                channel,
                chatId,
                null,
                currentRole
        );

        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(messages)
                .setTools(tools)
                .setModel(model)
                .setMaxIterations(maxIterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setProviderRetryMode(providerRetryMode)
                .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + maxIterations + "），但仍未完成任务。可尝试提高该值（例如 12 或 16）后重试。")
                .setWorkspace(workspace)
                .setSessionKey(session.getKey())
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit);

        AgentRunResult runResult = runner.run(spec);

        saveTurn(session, runResult.getMessages(), 1 + history.size());
        clearRuntimeCheckpoint(session);
        session.getMetadata().remove("_last_interrupt_reason");
        sessionManager.save(session);

        OutboundMessage out = new OutboundMessage();
        out.setChannel(channel);
        out.setChatId(chatId);
        out.setContent(
                RuntimeUtils.isBlankText(runResult.getFinalContent())
                        ? "后台任务已完成。"
                        : runResult.getFinalContent()
        );
        out.setMetadata(new HashMap<>());
        return out;
    }

    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId
    ) throws Exception {
        InboundMessage msg = new InboundMessage();
        msg.setChannel(channel);
        msg.setSenderId("user");
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setMedia(new ArrayList<>());
        msg.setMetadata(new HashMap<>());
        msg.setSessionKeyOverride(sessionKey);

        return processMessage(msg, effectiveSessionKey(msg));
    }

    public OutboundMessage processDirect(String content, String sessionKey) throws Exception {
        return processDirect(content, sessionKey, "cli", "direct");
    }

    private OutboundMessage buildStopResponse(InboundMessage msg) {
        String sessionKey = effectiveSessionKey(msg);
        List<Future<?>> tasks = activeTasks.remove(sessionKey);

        int cancelled = 0;
        if (tasks != null) {
            for (Future<?> task : tasks) {
                if (task != null && !task.isDone()) {
                    if (task.cancel(true)) {
                        cancelled++;
                    }
                }
            }
        }

        int total = cancelled;
        if (total > 0) {
            markSessionInterrupted(sessionKey, "manual_stop");
        }

        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(total > 0 ? "⏹ 已停止 " + total + " 个任务。" : "没有可停止的任务。");
        out.setMetadata(new HashMap<>());
        return out;
    }

    private OutboundMessage dispatchCommand(
            InboundMessage msg,
            Session session,
            String sessionKey,
            String raw,
            boolean priorityOnly
    ) {
        CommandRouter.CommandContext ctx = new CommandRouter.CommandContext(msg, session, sessionKey, raw, this);
        try {
            CompletableFuture<OutboundMessage> future = priorityOnly
                    ? commandRouter.dispatchPriority(ctx)
                    : commandRouter.dispatch(ctx);
            return future != null ? future.join() : null;
        } catch (Exception e) {
            log.warn("命令执行失败: {}", raw, e);
            return null;
        }
    }

    private CompletableFuture<OutboundMessage> cmdStop(CommandRouter.CommandContext ctx) {
        return CompletableFuture.completedFuture(buildStopResponse(ctx.getMsg()));
    }

    private CompletableFuture<OutboundMessage> cmdDisabled(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent("当前运行时未启用该命令。");
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdNew(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        session.clear();
        sessionManager.save(session);

        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent("已开始新的会话。");
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdHelp(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent("ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前任务\n/help — 查看可用命令");
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdStatus(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        int sessionMsgCount = session != null ? session.getMessages().size() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("ricbot status\n");
        sb.append("model: ").append(model).append("\n");
        sb.append("workspace: ").append(workspace).append("\n");
        sb.append("session messages: ").append(sessionMsgCount).append("\n");

        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent(sb.toString());
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdDream(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setMetadata(new HashMap<>());

        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            out.setContent("Dream 未启用。");
            return CompletableFuture.completedFuture(out);
        }

        boolean changed = dream.run();
        if (changed) {
            out.setContent("Dream 已完成一次整合，记忆文件已更新。");
        } else {
            out.setContent("Dream 本次没有检测到可更新内容。");
        }
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdDreamLog(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setMetadata(new HashMap<>());

        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            out.setContent("Dream 未启用。");
            return CompletableFuture.completedFuture(out);
        }

        int maxEntries = 10;
        String args = trim(ctx.getArgs());
        if (!args.isBlank()) {
            maxEntries = Math.max(1, Math.min(50, parseInt(args, 10)));
        }

        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            out.setContent("Dream 日志仓库尚未初始化。先执行一次 /dream 后再查看日志。");
            return CompletableFuture.completedFuture(out);
        }

        var logs = git.log(maxEntries);
        if (logs.isEmpty()) {
            out.setContent("暂无 Dream 历史记录。");
            return CompletableFuture.completedFuture(out);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("dream log (latest ").append(logs.size()).append(")\n");
        sb.append("cursor: ").append(memoryStore.getLastDreamCursor())
                .append("/").append(memoryStore.getLastCursor()).append("\n\n");
        for (var c : logs) {
            sb.append(c.sha()).append("  ").append(c.timestamp()).append("  ").append(c.message()).append("\n");
        }
        out.setContent(sb.toString().trim());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdDreamRestore(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setMetadata(new HashMap<>());

        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            out.setContent("Dream 未启用。");
            return CompletableFuture.completedFuture(out);
        }

        String args = trim(ctx.getArgs());
        if (args.isBlank()) {
            out.setContent("用法：/dream-restore <commit_sha>");
            return CompletableFuture.completedFuture(out);
        }

        String sha = args.split("\\s+")[0];
        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            out.setContent("Dream 日志仓库尚未初始化，无法 restore。请先执行 /dream。");
            return CompletableFuture.completedFuture(out);
        }

        var found = git.findCommit(sha, 200);
        if (found == null) {
            out.setContent("未找到对应提交：" + sha);
            return CompletableFuture.completedFuture(out);
        }

        String reverted = git.revert(found.sha());
        if (reverted == null) {
            out.setContent("restore 失败，请检查工作区状态后重试。");
            return CompletableFuture.completedFuture(out);
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        out.setContent("Dream 已恢复到 " + found.sha() + "，新提交: " + reverted + " (" + now + ")");
        return CompletableFuture.completedFuture(out);
    }

    private AgentHook buildLoopHook(InboundMessage msg) {
        AgentHook baseHook = new AgentHook(true) {
            private final StringBuilder streamBuf = new StringBuilder();

            @Override
            public boolean wantsStreaming() {
                Object wants = msg.getMetadata() != null ? msg.getMetadata().get("_wants_stream") : null;
                return wants instanceof Boolean b && b;
            }

            @Override
            public void onStream(AgentHookContext context, String delta) {
                String prevClean = HelperUtils.stripThink(streamBuf.toString());
                streamBuf.append(delta);
                String newClean = HelperUtils.stripThink(streamBuf.toString());

                String incremental = newClean.length() >= prevClean.length()
                        ? newClean.substring(prevClean.length())
                        : newClean;

                if (!incremental.isBlank()) {
                    OutboundMessage out = new OutboundMessage();
                    out.setChannel(msg.getChannel());
                    out.setChatId(msg.getChatId());
                    out.setContent(incremental);

                    Map<String, Object> meta = msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>();
                    meta.put("_stream_delta", true);
                    out.setMetadata(meta);

                    try {
                        bus.publishOutbound(out);
                    } catch (Exception e) {
                        log.debug("发布流式增量失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
                    }
                }
            }

            @Override
            public void onStreamEnd(AgentHookContext context, boolean resuming) {
                OutboundMessage out = new OutboundMessage();
                out.setChannel(msg.getChannel());
                out.setChatId(msg.getChatId());
                out.setContent("");

                Map<String, Object> meta = msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>();
                meta.put("_stream_end", true);
                meta.put("_resuming", resuming);
                out.setMetadata(meta);

                try {
                    bus.publishOutbound(out);
                } catch (Exception e) {
                    log.debug("发布流式结束标记失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
                }

                streamBuf.setLength(0);
            }

            @Override
            public void beforeExecuteTools(AgentHookContext context) {
                if (!wantsStreaming() && context.getResponse() != null) {
                    String thought = stripThink(context.getResponse().getContent());
                    if (!thought.isBlank()) {
                        publishProgress(msg, thought, false);
                    }
                }

                String toolHint = stripThink(toolHint(context.getToolCalls()));
                if (!toolHint.isBlank()) {
                    publishProgress(msg, toolHint, true);
                }

                setToolContext(msg.getChannel(), msg.getChatId(), messageIdOf(msg));
            }

            @Override
            public void afterIteration(AgentHookContext context) {
                Map<String, Integer> usage = context.getUsage();
                if (usage != null && !usage.isEmpty()) {
                    log.debug(
                            "LLM 用量: 提示词={} 完成={} 总计={}",
                            usage.getOrDefault("prompt_tokens", 0),
                            usage.getOrDefault("completion_tokens", 0),
                            usage.getOrDefault("total_tokens", 0)
                    );
                }
            }

            @Override
            public String finalizeContent(AgentHookContext context, String content) {
                return stripThink(content);
            }
        };

        if (extraHooks == null || extraHooks.isEmpty()) {
            return baseHook;
        }

        List<AgentHook> hooks = new ArrayList<>();
        hooks.add(baseHook);
        for (AgentHook hook : extraHooks) {
            if (hook != null) {
                hooks.add(hook);
            }
        }
        return hooks.size() == 1 ? baseHook : new AgentHook.CompositeHook(hooks);
    }

    private void publishProgress(InboundMessage msg, String content, boolean toolHint) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(content);

        Map<String, Object> meta = msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>();
        meta.put("_progress", true);
        meta.put("_tool_hint", toolHint);
        out.setMetadata(meta);

        try {
            bus.publishOutbound(out);
        } catch (Exception e) {
            log.debug("发布进度消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
        }
    }

    private String effectiveSessionKey(InboundMessage msg) {
        if (unifiedSession && (msg.getSessionKeyOverride() == null || msg.getSessionKeyOverride().isBlank())) {
            return UNIFIED_SESSION_KEY;
        }
        return msg.getSessionKey();
    }

    private void setToolContext(String channel, String chatId, String messageId) {
        for (String toolName : tools.toolNames()) {
            var tool = tools.get(toolName);
            if (tool == null) {
                continue;
            }

            try {
                if (tool instanceof CronTool cronTool) {
                    cronTool.setContext(channel, chatId);
                } else if (tool instanceof SpawnTool spawnTool) {
                    spawnTool.setContext(channel, chatId);
                }

                try {
                    var m3 = tool.getClass().getMethod("setContext", String.class, String.class, String.class);
                    m3.invoke(tool, channel, chatId, messageId);
                    continue;
                } catch (NoSuchMethodException ignored) {
                    // fall through
                }

                try {
                    var m2 = tool.getClass().getMethod("setContext", String.class, String.class);
                    m2.invoke(tool, channel, chatId);
                } catch (NoSuchMethodException ignored) {
                    // 当前工具不支持上下文注入
                }
            } catch (Exception e) {
                log.debug("注入工具上下文失败: tool={}", toolName, e);
            }
        }
    }

    private String stripThink(String text) {
        return HelperUtils.stripThink(text != null ? text : "");
    }

    private String toolHint(List<ToolCallRequest> toolCalls) {
        return ToolHintFormatter.formatToolHints(toolCalls);
    }

    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int start = Math.max(0, Math.min(skip, messages.size()));

        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> entry = normalizeTurnEntry(messages.get(i));
            if (entry == null) {
                continue;
            }
            session.getMessages().add(entry);
        }

        session.setUpdatedAt(Instant.now());
    }

    private Map<String, Object> normalizeTurnEntry(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }

        Map<String, Object> entry = new LinkedHashMap<>(raw);
        Object role = entry.get("role");
        Object content = entry.get("content");

        if ("assistant".equals(role) && (content == null || String.valueOf(content).isBlank()) && !entry.containsKey("tool_calls")) {
            return null;
        }

        if ("tool".equals(role) && content instanceof String s && s.length() > maxToolResultChars) {
            entry.put("content", HelperUtils.truncateText(s, maxToolResultChars));
            content = entry.get("content");
        }

        if ("user".equals(role) && content instanceof String s && s.startsWith(ContextBuilder.RUNTIME_CONTEXT_TAG)) {
            String endMarker = ContextBuilder.RUNTIME_CONTEXT_END;
            int endPos = s.indexOf(endMarker);
            if (endPos >= 0) {
                String after = s.substring(endPos + endMarker.length()).stripLeading();
                if (after.isBlank()) {
                    return null;
                }
                entry.put("content", after);
            }
        }

        entry.putIfAbsent("timestamp", Instant.now().toString());
        return entry;
    }

    private void setRuntimeCheckpoint(Session session, Map<String, Object> payload) {
        session.getMetadata().put(RUNTIME_CHECKPOINT_KEY, payload);
        sessionManager.save(session);
    }

    private void clearRuntimeCheckpoint(Session session) {
        session.getMetadata().remove(RUNTIME_CHECKPOINT_KEY);
    }

    private void markPendingUserTurn(Session session) {
        session.getMetadata().put(PENDING_USER_TURN_KEY, true);
    }

    private void clearPendingUserTurn(Session session) {
        session.getMetadata().remove(PENDING_USER_TURN_KEY);
    }

    @SuppressWarnings("unchecked")
    private void restoreRuntimeCheckpoint(Session session) {
        Object raw = session.getMetadata().get(RUNTIME_CHECKPOINT_KEY);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }

        Map<String, Object> checkpoint = (Map<String, Object>) rawMap;
        Object assistantMessage = checkpoint.get("assistant_message");
        Object completedToolResults = checkpoint.get("completed_tool_results");
        Object pendingToolCalls = checkpoint.get("pending_tool_calls");

        if (assistantMessage instanceof Map<?, ?> a) {
            session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) a));
        }

        if (completedToolResults instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }

        if (pendingToolCalls instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> toolCall) {
                    Map<String, Object> fn = toolCall.get("function") instanceof Map<?, ?> f
                            ? (Map<String, Object>) f
                            : new LinkedHashMap<>();

                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCall.get("id"));
                    toolMsg.put("name", fn.getOrDefault("name", "tool"));
                    toolMsg.put("content", interruptedToolMessage(checkpoint, session));
                    toolMsg.put("timestamp", Instant.now().toString());
                    session.getMessages().add(toolMsg);
                }
            }
        }

        clearPendingUserTurn(session);
        clearRuntimeCheckpoint(session);
        sessionManager.save(session);
    }

    private String interruptedToolMessage(Map<String, Object> checkpoint, Session session) {
        String reason = null;
        Object cpReason = checkpoint.get("interruption_reason");
        if (cpReason instanceof String s && !s.isBlank()) {
            reason = s;
        }
        if (reason == null) {
            Object sessionReason = session.getMetadata().get("_last_interrupt_reason");
            if (sessionReason instanceof String s && !s.isBlank()) {
                reason = s;
            }
        }
        if (reason == null) {
            reason = "interrupted";
        }

        return switch (reason) {
            case "manual_stop" -> "错误：任务在该工具执行完成前被手动停止。";
            case "shutdown" -> "错误：任务在该工具执行完成前因服务关闭而中断。";
            case "timeout" -> "错误：任务在该工具执行完成前因超时而中断。";
            default -> "错误：任务在该工具执行完成前被中断。";
        };
    }

    private void restorePendingUserTurn(Session session) {
        Object flag = session.getMetadata().get(PENDING_USER_TURN_KEY);
        if (!(flag instanceof Boolean b) || !b) {
            return;
        }

        List<Map<String, Object>> messages = session.getMessages();
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if ("user".equals(last.get("role"))) {
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", "错误：任务在生成回复前被中断。");
                assistant.put("timestamp", Instant.now().toString());
                messages.add(assistant);
                session.setUpdatedAt(Instant.now());
            }
        }

        clearPendingUserTurn(session);
        sessionManager.save(session);
    }

    private int historyWindowAsMessages() {
        if (contextWindowTokens <= 0) {
            return 100;
        }
        return Math.min(200, Math.max(20, contextWindowTokens / 500));
    }

    private String messageIdOf(InboundMessage msg) {
        if (msg.getMetadata() == null) {
            return null;
        }
        Object v = msg.getMetadata().get("message_id");
        return v != null ? String.valueOf(v) : null;
    }

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

    public List<AgentHook> getExtraHooks() {
        return extraHooks;
    }

    public void setExtraHooks(List<AgentHook> extraHooks) {
        this.extraHooks = extraHooks != null ? extraHooks : new ArrayList<>();
    }
}
