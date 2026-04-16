package ricbot.domain.hook;


import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hook 上下文
 *
 * 主要目标：
 * 1. 给 Hook 暴露当前执行状态
 * 2. 包含：
 *    - 当前 messages
 *    - 当前模型响应
 *    - 当前 tool calls
 *    - usage
 *    - iteration
 *    - stop reason
 *
 * 对应 Python: AgentHookContext
 */
public class AgentHookContext {

    // 消息列表，存储对话历史
    private List<Map<String, Object>> messages = new ArrayList<>();
    // LLM 的响应对象
    private LLMResponse response;
    // 工具调用请求列表
    private List<ToolCallRequest> toolCalls = new ArrayList<>();
    // Token 使用情况统计
    private Map<String, Integer> usage;
    // 当前迭代次数
    private int iteration;
    // 停止原因
    private String stopReason;
    // 会话密钥
    private String sessionKey;
    // 额外数据，用于扩展
    private Object extra;

    // 默认构造函数
    public AgentHookContext() {
    }

    // 获取消息列表
    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    // 设置消息列表，如果传入 null 则初始化为空列表，支持链式调用
    public AgentHookContext setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        return this;
    }

    // 获取 LLM 响应对象
    public LLMResponse getResponse() {
        return response;
    }

    // 设置 LLM 响应对象，支持链式调用
    public AgentHookContext setResponse(LLMResponse response) {
        this.response = response;
        return this;
    }

    // 获取工具调用请求列表
    public List<ToolCallRequest> getToolCalls() {
        return toolCalls;
    }

    // 设置工具调用请求列表，如果传入 null 则初始化为空列表，支持链式调用
    public AgentHookContext setToolCalls(List<ToolCallRequest> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        return this;
    }

    // 获取 Token 使用情况
    public Map<String, Integer> getUsage() {
        return usage;
    }

    // 设置 Token 使用情况，支持链式调用
    public AgentHookContext setUsage(Map<String, Integer> usage) {
        this.usage = usage;
        return this;
    }

    // 获取当前迭代次数
    public int getIteration() {
        return iteration;
    }

    // 设置当前迭代次数，支持链式调用
    public AgentHookContext setIteration(int iteration) {
        this.iteration = iteration;
        return this;
    }

    // 获取停止原因
    public String getStopReason() {
        return stopReason;
    }

    // 设置停止原因，支持链式调用
    public AgentHookContext setStopReason(String stopReason) {
        this.stopReason = stopReason;
        return this;
    }

    // 获取会话密钥
    public String getSessionKey() {
        return sessionKey;
    }

    // 设置会话密钥，支持链式调用
    public AgentHookContext setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
        return this;
    }

    // 获取额外数据
    public Object getExtra() {
        return extra;
    }

    // 设置额外数据，支持链式调用
    public AgentHookContext setExtra(Object extra) {
        this.extra = extra;
        return this;
    }
}