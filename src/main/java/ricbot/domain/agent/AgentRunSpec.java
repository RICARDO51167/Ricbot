package ricbot.domain.agent;


import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import ricbot.domain.hook.AgentHook;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * AgentRunner 的运行参数
 *
 * 主要目标：
 * 1. 封装一次 runner.run(...) 所需的全部参数
 *
 * 对应 Python: AgentRunSpec
 */
@Getter
@Setter
@Accessors(chain = true)
public class AgentRunSpec {

    // 初始消息列表，用于启动 Agent 对话
    private List<Map<String, Object>> initialMessages = new ArrayList<>();
    // 工具注册表，包含 Agent 可调用的所有工具
    private ToolRegistry tools;
    // 使用的模型名称
    private String model;
    // 最大迭代次数，防止无限循环
    private int maxIterations = 20;
    // 工具结果的最大字符数限制
    private int maxToolResultChars = 16000;
    // Agent 钩子，用于拦截和处理 Agent 生命周期事件
    private AgentHook hook;
    // 发生错误时的默认错误消息
    private String errorMessage = "抱歉，我在调用 AI 模型时遇到了错误。";
    // 达到最大迭代次数时的提示消息
    private String maxIterationsMessage =
            "我已达到最大工具调用迭代次数，但仍未完成任务。";
    // 是否在工具执行出错时立即失败
    private boolean failOnToolError = false;
    // 是否允许并发执行工具
    private boolean concurrentTools = false;
    // 工作空间路径
    private Path workspace;
    // 会话密钥，用于标识和隔离不同会话
    private String sessionKey;
    // 上下文窗口令牌数限制
    private Integer contextWindowTokens;
    // 上下文块数量限制
    private Integer contextBlockLimit;
    // 提供商重试模式，默认为标准模式
    private String providerRetryMode = "standard";

    /**
     * checkpoint 回调，用于保存中间状态
     */
    private Consumer<Map<String, Object>> checkpointCallback;

    /**
     * 进度回调，用于报告执行进度
     */
    private ProgressCallback progressCallback;

    /**
     * 注入 follow-up user message 的回调，用于动态插入用户消息
     */
    private InjectionCallback injectionCallback;

    private ToolLifecycleCallback toolLifecycleCallback;

    /**
     * 设置初始消息列表
     * @param initialMessages 初始消息列表
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setInitialMessages(List<Map<String, Object>> initialMessages) {
        this.initialMessages = initialMessages != null ? initialMessages : new ArrayList<>();
        return this;
    }

    /**
     * 获取错误消息
     * @return 错误消息
     */
    public AgentRunSpec setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        return this;
    }

    public AgentRunSpec setMaxIterationsMessage(String maxIterationsMessage) {
        this.maxIterationsMessage = maxIterationsMessage;
        return this;
    }

    /**
     * 进度回调函数式接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * 当有进度更新时调用
         * @param content 进度内容
         * @param toolHint 是否为工具提示
         * @throws Exception 异常
         */
        void onProgress(String content, boolean toolHint) throws Exception;
    }

    /**
     * 注入回调函数式接口
     */
    @FunctionalInterface
    public interface InjectionCallback {
        /**
         * 注入额外的用户消息
         * @return 消息列表
         * @throws Exception 异常
         */
        List<Map<String, Object>> inject() throws Exception;
    }

    interface ToolLifecycleCallback {
        void onToolStart(String toolName, Map<String, Object> arguments);

        void onToolFinish(Map<String, Object> event);
    }
}
