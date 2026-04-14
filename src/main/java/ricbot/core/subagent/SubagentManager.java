package ricbot.core.subagent;


import ricbot.core.agent.AgentRunResult;
import ricbot.core.agent.AgentRunSpec;
import ricbot.core.agent.AgentRunner;
import ricbot.core.hook.AgentHook;
import ricbot.core.hook.AgentHookContext;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import ricbot.core.message.InboundMessage;
import ricbot.core.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.infra.template.PromptTemplates;
import ricbot.llm.api.LLMProvider;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 子代理管理器。
 *
 * 作用：
 * 1. 启动后台子代理任务
 * 2. 跟踪正在运行的子代理
 * 3. 在任务完成后把结果回传给主 Agent
 * 4. 支持按 session 取消子代理
 *
 * 对应 Python 中的 SubagentManager。
 */
public class SubagentManager {

    /**
     * 子代理执行时的简单日志 Hook。
     *
     * 只负责在工具执行前打印日志，方便排查。
     */
    static class SubagentHook extends AgentHook {
        private final String taskId;

        public SubagentHook(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void beforeExecuteTools(AgentHookContext context) {
            if (context.getToolCalls() == null) {
                return;
            }

            for (var toolCall : context.getToolCalls()) {
                System.out.println(
                        "Subagent [" + taskId + "] executing: "
                                + toolCall.getName()
                                + " with arguments: "
                                + toolCall.getArguments()
                );
            }
        }
    }

    private final LLMProvider provider;
    private final Path workspace;
    private final MessageBus bus;
    private final String model;
    private final int maxToolResultChars;
    private final Config.ExecToolConfig execConfig;
    private final boolean restrictToWorkspace;
    private final Set<String> disabledSkills;
    private final AgentRunner runner;

    /**
     * taskId -> Future
     */
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    /**
     * sessionKey -> taskId set
     */
    private final Map<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    /**
     * 用线程池模拟后台执行。
     * Python 里是 asyncio.create_task，这里用 ExecutorService。
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();

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
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.model = model != null ? model : provider.getDefaultModel();
        this.maxToolResultChars = maxToolResultChars;
        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        this.restrictToWorkspace = restrictToWorkspace;
        this.disabledSkills = new HashSet<>(disabledSkills != null ? disabledSkills : List.of());
        this.runner = new AgentRunner(provider);
    }

    /**
     * 启动一个后台子代理任务。
     *
     * 返回给主 Agent 的是“已启动”提示；
     * 真正执行发生在 runSubagent(...) 里。
     */
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

        Future<?> future = executor.submit(() -> {
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

        runningTasks.put(taskId, future);

        if (sessionKey != null) {
            sessionTasks.computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet()).add(taskId);
        }

        System.out.println("Spawned subagent [" + taskId + "]: " + displayLabel);

        return "Subagent [" + displayLabel + "] started (id: " + taskId + "). I'll notify you when it completes.";
    }

    /**
     * 真正执行子代理任务。
     */
    private void runSubagent(
            String taskId,
            String task,
            String label,
            Map<String, String> origin
    ) {
        System.out.println("Subagent [" + taskId + "] starting task: " + label);

        try {
            // -----------------------------
            // 1. 构造子代理自己的工具集
            // -----------------------------
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

            // web tools are intentionally not part of the minimal runnable build

            // -----------------------------
            // 2. 构造子代理 prompt
            // -----------------------------
            String systemPrompt = buildSubagentPrompt();

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of(
                    "role", "system",
                    "content", systemPrompt
            ));
            messages.add(Map.of(
                    "role", "user",
                    "content", task
            ));

            // -----------------------------
            // 3. 跑子代理
            // -----------------------------
            AgentRunSpec spec = new AgentRunSpec();
            spec.setInitialMessages(messages);
            spec.setTools(tools);
            spec.setModel(model);
            spec.setMaxIterations(15);
            spec.setMaxToolResultChars(maxToolResultChars);
            spec.setHook(new SubagentHook(taskId));
            spec.setMaxIterationsMessage("Task completed but no final response was generated.");
            spec.setErrorMessage(null);
            spec.setFailOnToolError(true);

            AgentRunResult result = runner.run(spec);

            // -----------------------------
            // 4. 处理结果
            // -----------------------------
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
                        result.getError() != null ? result.getError() : "Error: subagent execution failed.",
                        origin,
                        "error"
                );
                return;
            }

            String finalResult = result.getFinalContent() != null
                    ? result.getFinalContent()
                    : "Task completed but no final response was generated.";

            System.out.println("Subagent [" + taskId + "] completed successfully");
            announceResult(taskId, label, task, finalResult, origin, "ok");

        } catch (Exception e) {
            String errorMsg = "Error: " + e.getMessage();
            System.err.println("Subagent [" + taskId + "] failed: " + e.getMessage());
            announceResult(taskId, label, task, errorMsg, origin, "error");
        }
    }

    /**
     * 把子代理结果回传给主 Agent。
     *
     * 注意：
     * 这里不是直接发给用户，而是构造成 system message，
     * 重新进入主 Agent 的 MessageBus。
     */
    private void announceResult(
            String taskId,
            String label,
            String task,
            String result,
            Map<String, String> origin,
            String status
    ) {
        String statusText = "ok".equals(status) ? "completed successfully" : "failed";

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
            System.out.println(
                    "Subagent [" + taskId + "] announced result to "
                            + origin.get("channel") + ":" + origin.get("chat_id")
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish subagent result", e);
        }
    }

    /**
     * 格式化子代理失败前的部分执行进度。
     */
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
            lines.add("Completed steps:");
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
            lines.add("Failure:");
            lines.add("- " + String.valueOf(failure.get("name")) + ": " + String.valueOf(failure.get("detail")));
        }

        if (result.getError() != null && failure == null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("Failure:");
            lines.add("- " + result.getError());
        }

        return lines.isEmpty()
                ? (result.getError() != null ? result.getError() : "Error: subagent execution failed.")
                : String.join("\n", lines);
    }

    /**
     * 构造子代理专用 system prompt。
     */
    private String buildSubagentPrompt() {
        String timeCtx = "[Runtime Context Placeholder]";
        String skillsSummary = "";

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

    /**
     * 按 session 取消该会话下所有仍在运行的子代理。
     *
     * 返回取消数量。
     */
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

        return futures.size();
    }

    /**
     * 当前运行中的子代理数量。
     */
    public int getRunningCount() {
        return runningTasks.size();
    }

    // =========================================================
    // 小工具方法
    // =========================================================

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen
                ? text
                : text.substring(0, maxLen) + "...";
    }

    /**
     * 兼容你前面 Config.ExecToolConfig 里 sandbox 是 String 的写法。
     */
    private boolean isSandboxEnabled(Config.ExecToolConfig cfg) {
        return cfg.getSandbox() != null && !cfg.getSandbox().isBlank();
    }
}
