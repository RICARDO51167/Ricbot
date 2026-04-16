package ricbot.domain.subagent;

import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.AgentRunner;
import ricbot.domain.agent.ContextBuilder;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理管理器
 */
public class SubagentManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);

    static class SubagentHook extends AgentHook {
        private final Logger log;

        public SubagentHook(String taskId) {
            this.log = LoggerFactory.getLogger("subagent." + taskId);
        }

        @Override
        public void beforeExecuteTools(AgentHookContext context) {
            if (context.getToolCalls() == null) {
                return;
            }

            for (var toolCall : context.getToolCalls()) {
                log.debug("tool: name={}, args={}", toolCall.getName(), toolCall.getArguments());
            }
        }
    }

    private final Path workspace;
    private final MessageBus bus;
    private final String model;
    private final int maxToolResultChars;
    private final Config.ExecToolConfig execConfig;
    private final boolean restrictToWorkspace;
    private final Set<String> disabledSkills;
    private final Config.WebToolsConfig webConfig;
    private final SkillsLoader skillsLoader;
    private final AgentRunner runner;

    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor executor;

    public SubagentManager(
            LLMProvider provider,
            Path workspace,
            MessageBus bus,
            int maxToolResultChars,
            String model,
            Config.WebToolsConfig webConfig,
            Config.ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            List<String> disabledSkills
    ) {
        this.workspace = workspace;
        this.bus = bus;
        this.model = model != null ? model : provider.getDefaultModel();
        this.maxToolResultChars = maxToolResultChars;
        this.webConfig = webConfig != null ? webConfig : new Config.WebToolsConfig();
        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        this.restrictToWorkspace = restrictToWorkspace;
        this.disabledSkills = new HashSet<>(disabledSkills != null ? disabledSkills : List.of());
        this.skillsLoader = new SkillsLoader(workspace, null, this.disabledSkills);
        this.runner = new AgentRunner(provider);

        this.executor = new ThreadPoolExecutor(
                1,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "subagent-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
    }

    public String spawn(
            String task,
            String label,
            String originChannel,
            String originChatId,
            String sessionKey
    ) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String displayLabel = (label != null && !label.isBlank())
                ? label
                : truncate(task, 30);

        Map<String, String> origin = new HashMap<>();
        origin.put("channel", originChannel != null ? originChannel : "cli");
        origin.put("chat_id", originChatId != null ? originChatId : "direct");

        Future<?> future;
        try {
            future = executor.submit(() -> {
                try {
                    runSubagent(taskId, task, displayLabel, origin);
                } finally {
                    runningTasks.remove(taskId);
                    if (sessionKey != null) {
                        Set<String> ids = sessionTasks.get(sessionKey);
                        if (ids != null) {
                            ids.remove(taskId);
                            if (ids.isEmpty()) {
                                sessionTasks.remove(sessionKey);
                            }
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("子代理任务队列已满，拒绝执行: label={}", displayLabel);
            return "子代理系统繁忙，请稍后再试。";
        }

        runningTasks.put(taskId, future);

        if (sessionKey != null) {
            sessionTasks.computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet()).add(taskId);
        }

        log.info("已启动子代理: id={}, label={}", taskId, displayLabel);

        return "子代理 [" + displayLabel + "] 已启动（id：" + taskId + "）。完成后我会通知你。";
    }

    private void runSubagent(
            String taskId,
            String task,
            String label,
            Map<String, String> origin
    ) {
        log.info("子代理开始: id={}, label={}", taskId, label);

        try {
            ToolRegistry tools = new ToolRegistry();

            Path allowedDir = (restrictToWorkspace || isSandboxEnabled(execConfig))
                    ? workspace
                    : null;

            tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
            tools.register(new WriteFileTool(workspace, allowedDir));
            tools.register(new EditFileTool(workspace, allowedDir));
            tools.register(new ListDirTool(workspace, allowedDir));
            tools.register(new GlobTool(workspace, allowedDir));
            tools.register(new GrepTool(workspace, allowedDir));

            if (execConfig.isEnable()) {
                tools.register(new ExecTool(
                        execConfig.getTimeout(),
                        String.valueOf(workspace),
                        null,
                        null,
                        restrictToWorkspace,
                        execConfig.getSandbox(),
                        execConfig.getPathAppend(),
                        execConfig.getAllowedEnvKeys()
                ));
            }

            if (webConfig.isEnable()) {
                tools.register(new WebFetchTool(webConfig.getMaxChars(), webConfig.getProxy()));
                tools.register(new WebSearchTool(webConfig.getSearch(), webConfig.getProxy()));
            }

            String systemPrompt = buildSubagentPrompt(origin);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of(
                    "role", "system",
                    "content", systemPrompt
            ));
            messages.add(Map.of(
                    "role", "user",
                    "content", task
            ));

            AgentRunSpec spec = new AgentRunSpec();
            spec.setInitialMessages(messages);
            spec.setTools(tools);
            spec.setModel(model);
            spec.setMaxIterations(15);
            spec.setMaxToolResultChars(maxToolResultChars);
            spec.setHook(new SubagentHook(taskId));
            spec.setMaxIterationsMessage("任务已结束，但未生成最终回复。");
            spec.setErrorMessage(null);
            spec.setFailOnToolError(true);

            AgentRunResult result = runner.run(spec);

            if ("tool_error".equals(result.getStopReason())) {
                announceResult(
                        taskId,
                        label,
                        task,
                        formatPartialProgress(result),
                        origin,
                        "error"
                );
                return;
            }

            if ("error".equals(result.getStopReason())) {
                announceResult(
                        taskId,
                        label,
                        task,
                        result.getError() != null ? result.getError() : "错误：子代理执行失败。",
                        origin,
                        "error"
                );
                return;
            }

            if ("cancelled".equals(result.getStopReason())) {
                announceResult(taskId, label, task, "任务已取消。", origin, "cancelled");
                return;
            }

            String finalResult = result.getFinalContent() != null
                    ? result.getFinalContent()
                    : "任务已结束，但未生成最终回复。";

            log.info("子代理完成: id={}, label={}", taskId, label);
            announceResult(taskId, label, task, finalResult, origin, "ok");

        } catch (CancellationException e) {
            announceResult(taskId, label, task, "任务已取消。", origin, "cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            announceResult(taskId, label, task, "任务已取消。", origin, "cancelled");
        } catch (Exception e) {
            String errorMsg = "错误：" + e.getMessage();
            log.warn("子代理执行失败: id={}, label={}", taskId, label, e);
            announceResult(taskId, label, task, errorMsg, origin, "error");
        }
    }

    private void announceResult(
            String taskId,
            String label,
            String task,
            String result,
            Map<String, String> origin,
            String status
    ) {
        String statusText = switch (status) {
            case "ok" -> "已完成";
            case "cancelled" -> "已取消";
            default -> "失败";
        };

        String announceContent = PromptTemplates.renderTemplate(
                "agent/subagent_announce.md",
                true,
                Map.of(
                        "label", label,
                        "status_text", statusText,
                        "task", task,
                        "result", result
                )
        );

        InboundMessage msg = new InboundMessage();
        msg.setChannel("system");
        msg.setSenderId("subagent");
        msg.setChatId(origin.get("channel") + ":" + origin.get("chat_id"));
        msg.setContent(announceContent);

        try {
            bus.publishInbound(msg);
            log.info("子代理结果已回传: id={}, to={}:{}, status={}",
                    taskId,
                    origin.get("channel"),
                    origin.get("chat_id"),
                    status
            );
        } catch (Exception e) {
            log.warn("发布子代理结果失败: id={}", taskId, e);
        }
    }

    private String formatPartialProgress(AgentRunResult result) {
        List<Map<String, Object>> completed = new ArrayList<>();
        Map<String, Object> failure = null;

        if (result.getToolEvents() != null) {
            for (Map<String, Object> event : result.getToolEvents()) {
                if ("ok".equals(String.valueOf(event.get("status")))) {
                    completed.add(event);
                }
            }

            for (int i = result.getToolEvents().size() - 1; i >= 0; i--) {
                Map<String, Object> event = result.getToolEvents().get(i);
                if ("error".equals(String.valueOf(event.get("status")))) {
                    failure = event;
                    break;
                }
            }
        }

        List<String> lines = new ArrayList<>();

        if (!completed.isEmpty()) {
            lines.add("已完成步骤：");
            int start = Math.max(0, completed.size() - 3);
            for (int i = start; i < completed.size(); i++) {
                Map<String, Object> event = completed.get(i);
                lines.add("- " + String.valueOf(event.get("name")) + ": " + String.valueOf(event.get("detail")));
            }
        }

        if (failure != null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("失败：");
            lines.add("- " + String.valueOf(failure.get("name")) + ": " + String.valueOf(failure.get("detail")));
        }

        if (result.getError() != null && failure == null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("失败：");
            lines.add("- " + result.getError());
        }

        return lines.isEmpty()
                ? (result.getError() != null ? result.getError() : "错误：子代理执行失败。")
                : String.join("\n", lines);
    }

    private String buildSubagentPrompt(Map<String, String> origin) {
        String channel = origin != null ? origin.get("channel") : null;
        String chatId = origin != null ? origin.get("chat_id") : null;
        String timeCtx = ContextBuilder.buildRuntimeContext(channel, chatId, null);
        String skillsSummary = skillsLoader.buildSkillsSummary();

        return PromptTemplates.renderTemplate(
                "agent/subagent_system.md",
                true,
                Map.of(
                        "time_ctx", timeCtx,
                        "workspace", String.valueOf(workspace),
                        "skills_summary", skillsSummary
                )
        );
    }

    public int cancelBySession(String sessionKey) {
        Set<String> ids = sessionTasks.getOrDefault(sessionKey, Set.of());

        List<Future<?>> futures = new ArrayList<>();
        for (String tid : ids) {
            Future<?> f = runningTasks.get(tid);
            if (f != null && !f.isDone()) {
                futures.add(f);
            }
        }

        for (Future<?> f : futures) {
            f.cancel(true);
        }

        if (!futures.isEmpty()) {
            sessionTasks.remove(sessionKey);
        }

        return futures.size();
    }

    public int getRunningCount() {
        return runningTasks.size();
    }

    @Override
    public void close() {
        for (Future<?> f : runningTasks.values()) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
        runningTasks.clear();
        sessionTasks.clear();
        executor.shutdownNow();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen
                ? text
                : text.substring(0, maxLen) + "...";
    }

    private boolean isSandboxEnabled(Config.ExecToolConfig cfg) {
        return cfg.getSandbox() != null && !cfg.getSandbox().isBlank();
    }
}
