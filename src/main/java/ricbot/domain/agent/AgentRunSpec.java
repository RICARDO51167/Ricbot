package ricbot.domain.agent;


import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import ricbot.domain.config.ProviderCapability;
import ricbot.domain.config.ModelCard;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.security.ApprovalService;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.*;

/**
 * Agent Runtime 的不可变运行参数
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

    /** Optional caller-assigned durable Run ID (including ordinary child Runs). */
    private String runId;

    // 初始消息列表，用于启动 Agent 对话
    private List<Map<String, Object>> initialMessages = new ArrayList<>();
    // 工具注册表，包含 Agent 可调用的所有工具
    private ToolRegistry tools;
    // 使用的模型名称
    private String model;
    /** Optional independent model for structured compaction; defaults to the main model. */
    private String compactModel;
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
    // 工作空间路径
    private Path workspace;
    /** Runtime database workspace; tool workspace may be an isolated child worktree. */
    private Path runtimeWorkspace;
    // 会话密钥，用于标识和隔离不同会话
    private String sessionKey;
    // 上下文窗口令牌数限制
    private Integer contextWindowTokens;
    // 上下文块数量限制
    private Integer contextBlockLimit;
    // 提供商重试模式，默认为标准模式
    private String providerRetryMode = "standard";
    // 静态/启发式 Provider capability，用于运行时保守降级。
    private ProviderCapability providerCapability;
    private ModelCard.Pricing modelPricing;
    private BudgetPolicy budgetPolicy = BudgetPolicy.unlimited();
    /** Root hard limit shared by related child Runs. */
    private BudgetPolicy rootBudgetPolicy;
    private boolean contextOffloadEnabled = true;
    private int offloadPreviewChars = 1200;
    private int artifactReadChunkChars = 16000;
    private long maxArtifactBytesPerTool = 67_108_864L;
    private double contextTriggerRatio = 0.80d;
    private double contextWarningRatio = 0.60d;
    private double contextTargetRatio = 0.60d;
    private int timeHintIntervalMinutes = 30;
    private boolean externalActionsEnabled;
    private int maxParallelReadCalls = 4;
    private boolean requireReadReceipt = true;
    private String timezone = "UTC";
    // 运行模式元数据；普通 agent 模式可为空，team-worker 等适配层用于审计与测试。
    private Map<String, Object> metadata = new LinkedHashMap<>();
    // 适配层声明的允许工具名；实际限制由传入的 ToolRegistry 决定。
    private List<String> allowedTools = new ArrayList<>();
    /** Persistent approval authority used by the durable runtime approval boundary. */
    private ApprovalService approvalService;

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

    public interface ToolLifecycleCallback {
        void onToolStart(String toolName, Map<String, Object> arguments);

        void onToolFinish(Map<String, Object> event);
    }
}
