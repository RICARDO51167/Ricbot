package ricbot.domain.agent;

import ricbot.domain.hook.AgentHook;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

final class AgentExecutionService {

    private final AgentRunner runner;
    private final ToolRegistry tools;
    private final Path workspace;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int contextWindowTokens;
    private final Integer contextBlockLimit;

    AgentExecutionService(
            AgentRunner runner,
            ToolRegistry tools,
            Path workspace,
            String model,
            int maxIterations,
            int maxToolResultChars,
            String providerRetryMode,
            int contextWindowTokens,
            Integer contextBlockLimit
    ) {
        this.runner = runner;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        this.maxToolResultChars = maxToolResultChars;
        this.providerRetryMode = providerRetryMode;
        this.contextWindowTokens = contextWindowTokens;
        this.contextBlockLimit = contextBlockLimit;
    }

    ExecutionOutcome executeInteractive(AgentRequestContext request, Consumer<Map<String, Object>> checkpointCallback) throws Exception {
        AgentRunResult result = runner.run(buildSpec(
                request.initialMessages(),
                request.session().getKey(),
                request.hook(),
                maxIterations,
                maxIterationsMessage(maxIterations),
                checkpointCallback,
                request.session()
        ));

        AgentHook hook = request.hook();
        if (hook == null || !hook.wantsStreaming()) {
            if ("tool_loop".equals(result.getStopReason()) || "tool_error_loop".equals(result.getStopReason())) {
                int bumped = Math.min(30, Math.max(maxIterations + 6, maxIterations * 2));
                if (bumped > maxIterations) {
                    result = runner.run(buildSpec(
                            result.getMessages(),
                            request.session().getKey(),
                            hook,
                            bumped,
                            maxIterationsMessage(bumped),
                            checkpointCallback,
                            request.session()
                    ));
                }
            }
        }

        String finalContent = result.getFinalContent();
        if (RuntimeUtils.isBlankText(finalContent)) {
            finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
        }
        return new ExecutionOutcome(result, finalContent);
    }

    ExecutionOutcome executeSystem(AgentRequestContext request) throws Exception {
        AgentRunResult result = runner.run(buildSpec(
                request.initialMessages(),
                request.session().getKey(),
                null,
                maxIterations,
                maxIterationsMessage(maxIterations),
                null,
                request.session()
        ));

        String finalContent = RuntimeUtils.isBlankText(result.getFinalContent())
                ? "后台任务已完成。"
                : result.getFinalContent();
        return new ExecutionOutcome(result, finalContent);
    }

    private AgentRunSpec buildSpec(
            java.util.List<java.util.Map<String, Object>> initialMessages,
            String sessionKey,
            AgentHook hook,
            int iterations,
            String maxIterationMessage,
            Consumer<Map<String, Object>> checkpointCallback,
            ricbot.domain.session.Session session
    ) {
        return new AgentRunSpec()
                .setInitialMessages(initialMessages)
                .setTools(tools)
                .setModel(model)
                .setMaxIterations(iterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setHook(hook)
                .setProviderRetryMode(providerRetryMode)
                .setErrorMessage("抱歉，调用模型时遇到错误。")
                .setMaxIterationsMessage(maxIterationMessage)
                .setConcurrentTools(true)
                .setWorkspace(workspace)
                .setSessionKey(sessionKey)
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit)
                .setCheckpointCallback(checkpointCallback)
                .setToolLifecycleCallback(new AgentRunSpec.ToolLifecycleCallback() {
                    @Override
                    public void onToolStart(String toolName, Map<String, Object> arguments) {
                        synchronized (session) {
                            TaskState taskState = TaskState.fromSession(session);
                            taskState.markToolStart(toolName, arguments);
                            taskState.persist(session);
                        }
                    }

                    @Override
                    public void onToolFinish(Map<String, Object> event) {
                        synchronized (session) {
                            TaskState taskState = TaskState.fromSession(session);
                            taskState.markToolFinish(event);
                            taskState.persist(session);
                        }
                    }
                });
    }

    private String maxIterationsMessage(int iterations) {
        return "我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + iterations + "），但仍未完成任务。可尝试提高该值（例如 12 或 16）后重试。";
    }
}
