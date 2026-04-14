package ricbot.core.agent;

import ricbot.core.mcp.MCPLoader;
import ricbot.core.skill.SkillsLoader;
import ricbot.infra.cron.CronService;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import ricbot.core.memory.Consolidator;
import ricbot.core.memory.Dream;
import ricbot.core.memory.MemoryStore;
import ricbot.core.subagent.SubagentManager;
import ricbot.core.hook.AgentHook;
import ricbot.core.hook.AgentHookContext;
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
import ricbot.core.message.InboundMessage;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;
import ricbot.infra.config.Config;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.ToolCallRequest;
import ricbot.core.session.Session;
import ricbot.core.session.SessionManager;
import ricbot.common.util.HelperUtils;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.infra.template.ToolHintFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

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
    private final Map<String, Object> mcpServers;
    private final boolean restrictToWorkspace;
    private final boolean unifiedSession;

    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Consolidator consolidator;
    private final Dream dream;
    private final SubagentManager subagents;
    private final SkillsLoader skillsLoader;
    private final CronService cronService;
    private final ToolRegistry tools;
    private final AgentRunner runner;

    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();
    private final Semaphore concurrencyGate;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    private volatile boolean running = false;
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
            int sessionTtlMinutes
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
        this.mcpServers = mcpServers != null ? mcpServers : Collections.emptyMap();
        this.restrictToWorkspace = restrictToWorkspace;
        this.unifiedSession = unifiedSession;

        this.contextBuilder = new ContextBuilder(this.workspace, timezone, disabledSkills);
        this.sessionManager = sessionManager != null ? sessionManager : new SessionManager(this.workspace);
        this.memoryStore = new MemoryStore(this.workspace);
        this.consolidator = new Consolidator(
                this.memoryStore,
                this.provider,
                this.model,
                this.sessionManager,
                this.contextWindowTokens,
                4096 // maxCompletionTokens placeholder
        );
        this.dream = new Dream(this.workspace, this.provider, this.model, this.memoryStore);
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
                null, // builtinDir will be resolved automatically
                disabledSkills != null ? new HashSet<>(disabledSkills) : new HashSet<>()
        );
        this.cronService = new CronService(workspace.resolve(".ricbot").resolve("cron").resolve("store.json"));
        this.cronService.setOnJob(this::handleCronJob);

        this.tools = new ToolRegistry();
        this.runner = new AgentRunner(provider);

        int maxConcurrent = parseInt(System.getenv("RICBOT_MAX_CONCURRENT_REQUESTS"), 3);
        this.concurrencyGate = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;

        this.executor = Executors.newCachedThreadPool();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        registerDefaultTools();
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
        Path allowedDir = (restrictToWorkspace || execConfig.isSandbox()) ? workspace : null;

        // filesystem
        tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
        tools.register(new ListDirTool(workspace, allowedDir));
        tools.register(new WriteFileTool(workspace, allowedDir));
        tools.register(new EditFileTool(workspace, allowedDir));
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        // search
        tools.register(new GlobTool(workspace, allowedDir));
        tools.register(new GrepTool(workspace, allowedDir));

        // shell
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

        // cron
        if (cronService != null) {
            tools.register(new CronTool(cronService, contextBuilder.getTimezone()));
        }

        // web
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

        // mcp
        if (mcpServers != null && !mcpServers.isEmpty()) {
            new MCPLoader(tools, mcpServers).load();
        }

        // cron tools are intentionally not part of the minimal runnable build
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    /**
     * 启动主循环。
     *
     * Java 版采用 while + 阻塞消费的方式持续运行。
     */
    private String handleCronJob(CronJob job) {
        log.info("Cron executing: {}", job.getName());
        InboundMessage msg = new InboundMessage();
        msg.setChannel(job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "system");
        msg.setChatId(job.getPayload().getTo() != null ? job.getPayload().getTo() : "cron");
        msg.setContent(job.getPayload().getMessage());
        msg.setSenderId("cron");
        
        // 标记这是一个 cron 任务
        msg.getMetadata().put("_cron_job_id", job.getId());
        msg.getMetadata().put("_cron_job_name", job.getName());
        msg.getMetadata().put("_deliver", job.getPayload().isDeliver());

        // 分发任务
        executor.submit(() -> dispatch(msg));
        return "Task dispatched";
    }

    public void start() {
        this.running = true;
        this.cronService.start();
        new Thread(this::run, "agent-loop").start();
    }

    public void run() {
        this.running = true;
        log.info("Agent loop started");

        // Start Dream consolidation task in background every 15 minutes
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                dream.run();
            } catch (Exception e) {
                log.error("Error in background Dream task", e);
            }
        }, 15, 15, TimeUnit.MINUTES);

        while (running) {
            try {
                InboundMessage msg = bus.consumeInbound(500, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    continue;
                }

                String raw = trim(msg.getContent());

                // /stop 优先处理
                if ("/stop".equalsIgnoreCase(raw)) {
                    handleStop(msg);
                    continue;
                }

                // 正常异步分发
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
                log.error("Error in agent loop", e);
            }
        }
    }

    public void stop() {
        this.running = false;
        this.cronService.stop();
        executor.shutdownNow();
        scheduler.shutdownNow();
        log.info("Agent loop stopping");
    }

    public Dream getDream() { return dream; }
    public SubagentManager getSubagents() { return subagents; }
    public SessionManager getSessions() { return sessionManager; }
    public Consolidator getConsolidator() { return consolidator; }

    // ---------------------------------------------------------------------
    // Dispatch / processing
    // ---------------------------------------------------------------------

    private void dispatch(InboundMessage msg) {
        String sessionKey = effectiveSessionKey(msg);
        Object lock = sessionLocks.computeIfAbsent(sessionKey, k -> new Object());

        synchronized (lock) {
            try {
                OutboundMessage response = processMessage(msg, sessionKey);
                if (response != null) {
                    bus.publishOutbound(response);
                } else if ("cli".equals(msg.getChannel())) {
                    // CLI 安全兜底：补一个空包结束本轮输出
                    OutboundMessage empty = new OutboundMessage();
                    empty.setChannel(msg.getChannel());
                    empty.setChatId(msg.getChatId());
                    empty.setContent("");
                    empty.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
                    bus.publishOutbound(empty);
                }
            } catch (Exception e) {
                log.error("Error processing message for session {}", sessionKey, e);
                OutboundMessage error = new OutboundMessage();
                error.setChannel(msg.getChannel());
                error.setChatId(msg.getChatId());
                error.setContent("Sorry, I encountered an error.");
                error.setMetadata(new HashMap<>());
                try {
                    bus.publishOutbound(error);
                } catch (Exception ignored) {
                }
            } finally {
                List<Future<?>> tasks = activeTasks.get(sessionKey);
                if (tasks != null) {
                    tasks.removeIf(Future::isDone);
                }
            }
        }
    }

    /**
     * 处理单条消息。
     */
    private OutboundMessage processMessage(InboundMessage msg, String sessionKey) throws Exception {
        // system message：子任务/后台任务注入
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }

        String preview = msg.getContent() != null && msg.getContent().length() > 80
                ? msg.getContent().substring(0, 80) + "..."
                : String.valueOf(msg.getContent());
        log.info("Processing message from {}:{}: {}", msg.getChannel(), msg.getSenderId(), preview);

        Session session = sessionManager.getOrCreate(sessionKey);

        // 归档旧消息
        consolidator.maybeConsolidateByTokens(session);

        restoreRuntimeCheckpoint(session);
        restorePendingUserTurn(session);

        // slash commands
        String raw = trim(msg.getContent());
        if ("/new".equalsIgnoreCase(raw)) {
            session.clear();
            sessionManager.save(session);

            OutboundMessage out = new OutboundMessage();
            out.setChannel(msg.getChannel());
            out.setChatId(msg.getChatId());
            out.setContent("New session started.");
            out.setMetadata(new HashMap<>());
            return out;
        }

        if ("/help".equalsIgnoreCase(raw)) {
            OutboundMessage out = new OutboundMessage();
            out.setChannel(msg.getChannel());
            out.setChatId(msg.getChatId());
            out.setContent("ricbot commands:\n/new — Start a new conversation\n/stop — Stop the current task\n/help — Show available commands");
            out.setMetadata(new HashMap<>());
            return out;
        }

        setToolContext(msg.getChannel(), msg.getChatId(), messageIdOf(msg));

        String memoryContext = memoryStore.getMemoryContext();
        String skillsContext = skillsLoader.getSkillsContext();
        String combinedContext = (memoryContext != null ? memoryContext : "") + "\n" + (skillsContext != null ? skillsContext : "");

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

        // 提前持久化用户消息，避免中途 crash 丢失
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
                .setErrorMessage("Sorry, I encountered an error calling the AI model.")
                .setConcurrentTools(true)
                .setWorkspace(workspace)
                .setSessionKey(session.getKey())
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit)
                .setCheckpointCallback(payload -> setRuntimeCheckpoint(session, payload));

        AgentRunResult runResult = runner.run(spec);

        String finalContent = runResult.getFinalContent();
        if (RuntimeUtils.isBlankText(finalContent)) {
            finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
        }

        // 跳过已经提前持久化的 user message
        int saveSkip = 1 + history.size() + (userPersistedEarly ? 1 : 0);
        saveTurn(session, runResult.getMessages(), saveSkip);

        clearPendingUserTurn(session);
        clearRuntimeCheckpoint(session);
        sessionManager.save(session);

        log.info("Response to {}:{}: {}", msg.getChannel(), msg.getSenderId(), abbreviate(finalContent, 120));

        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(finalContent);
        out.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
        return out;
    }

    /**
     * system channel 的后台消息处理。
     */
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
                .setWorkspace(workspace)
                .setSessionKey(session.getKey())
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit);

        AgentRunResult runResult = runner.run(spec);

        saveTurn(session, runResult.getMessages(), 1 + history.size());
        clearRuntimeCheckpoint(session);
        sessionManager.save(session);

        OutboundMessage out = new OutboundMessage();
        out.setChannel(channel);
        out.setChatId(chatId);
        out.setContent(
                RuntimeUtils.isBlankText(runResult.getFinalContent())
                        ? "Background task completed."
                        : runResult.getFinalContent()
        );
        out.setMetadata(new HashMap<>());
        return out;
    }

    /**
     * 直接调用，不通过 bus。
     */
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

        return processMessage(msg, sessionKey);
    }

    /**
     * 对齐你前面已有调用点的重载。
     */
    public OutboundMessage processDirect(String content, String sessionKey) throws Exception {
        return processDirect(content, sessionKey, "cli", "direct");
    }

    // ---------------------------------------------------------------------
    // /stop
    // ---------------------------------------------------------------------

    private void handleStop(InboundMessage msg) {
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

        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(total > 0 ? "⏹ Stopped " + total + " task(s)." : "No active task to stop.");
        out.setMetadata(new HashMap<>());

        try {
            bus.publishOutbound(out);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Hook / helpers
    // ---------------------------------------------------------------------

    private AgentHook buildLoopHook(InboundMessage msg) {
        return new AgentHook(true) {
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
                    } catch (Exception ignored) {
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
                } catch (Exception ignored) {
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
                            "LLM usage: prompt={} completion={} total={}",
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
        } catch (Exception ignored) {
        }
    }

    private String effectiveSessionKey(InboundMessage msg) {
        if (unifiedSession && (msg.getSessionKeyOverride() == null || msg.getSessionKeyOverride().isBlank())) {
            return UNIFIED_SESSION_KEY;
        }
        return msg.getSessionKey();
    }

    private void setToolContext(String channel, String chatId, String messageId) {
        // no-op for minimal toolset
    }

    private String stripThink(String text) {
        return HelperUtils.stripThink(text != null ? text : "");
    }

    private String toolHint(List<ToolCallRequest> toolCalls) {
        return ToolHintFormatter.formatToolHints(toolCalls);
    }

    // ---------------------------------------------------------------------
    // Session persistence helpers
    // ---------------------------------------------------------------------

    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int start = Math.max(0, Math.min(skip, messages.size()));

        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> entry = new LinkedHashMap<>(messages.get(i));
            Object role = entry.get("role");
            Object content = entry.get("content");

            // 跳过空 assistant message
            if ("assistant".equals(role) && (content == null || String.valueOf(content).isBlank()) && !entry.containsKey("tool_calls")) {
                continue;
            }

            // 截断过长 tool result
            if ("tool".equals(role) && content instanceof String s && s.length() > maxToolResultChars) {
                entry.put("content", HelperUtils.truncateText(s, maxToolResultChars));
            }

            // 去掉 runtime context tag，只保留用户真正内容
            if ("user".equals(role) && content instanceof String s && s.startsWith(ContextBuilder.RUNTIME_CONTEXT_TAG)) {
                String endMarker = ContextBuilder.RUNTIME_CONTEXT_END;
                int endPos = s.indexOf(endMarker);
                if (endPos >= 0) {
                    String after = s.substring(endPos + endMarker.length()).stripLeading();
                    if (after.isBlank()) {
                        continue;
                    }
                    entry.put("content", after);
                }
            }

            entry.putIfAbsent("timestamp", Instant.now().toString());
            session.getMessages().add(entry);
        }

        session.setUpdatedAt(Instant.now());
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
                    toolMsg.put("content", "Error: Task interrupted before this tool finished.");
                    toolMsg.put("timestamp", Instant.now().toString());
                    session.getMessages().add(toolMsg);
                }
            }
        }

        clearPendingUserTurn(session);
        clearRuntimeCheckpoint(session);
        sessionManager.save(session);
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
                assistant.put("content", "Error: Task interrupted before a response was generated.");
                assistant.put("timestamp", Instant.now().toString());
                messages.add(assistant);
                session.setUpdatedAt(Instant.now());
            }
        }

        clearPendingUserTurn(session);
        sessionManager.save(session);
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private int historyWindowAsMessages() {
        // 这里用 contextWindowTokens 近似代替“最大历史消息数”
        // 先给一个比较保守的上限，避免历史无限增长
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

    // ---------------------------------------------------------------------
    // extra hooks
    // ---------------------------------------------------------------------

    public List<AgentHook> getExtraHooks() {
        return extraHooks;
    }

    public void setExtraHooks(List<AgentHook> extraHooks) {
        this.extraHooks = extraHooks != null ? extraHooks : new ArrayList<>();
    }
}
