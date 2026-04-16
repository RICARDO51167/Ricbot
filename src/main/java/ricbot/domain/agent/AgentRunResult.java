package ricbot.domain.agent;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentRunner 运行结果
 *
 * 对应 Python runner.run(...) 的返回对象语义
 */
public class AgentRunResult {

    /**
     * 最终生成的内容
     */
    private String finalContent;

    /**
     * 使用过的工具列表
     */
    private List<String> toolsUsed = new ArrayList<>();

    /**
     * 消息历史记录
     */
    private List<Map<String, Object>> messages = new ArrayList<>();

    /**
     * 停止原因，默认为 "stop"
     */
    private String stopReason = "stop";

    /**
     * 是否发生过注入
     */
    private boolean hadInjections = false;

    /**
     * Token 使用情况统计
     */
    private Map<String, Integer> usage = new HashMap<>();

    /**
     * 错误信息
     */
    private String error;

    /**
     * 工具调用事件列表
     */
    private List<Map<String, Object>> toolEvents = new ArrayList<>();

    public String getFinalContent() {
        return finalContent;
    }

    public AgentRunResult setFinalContent(String finalContent) {
        this.finalContent = finalContent;
        return this;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public AgentRunResult setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed != null ? toolsUsed : new ArrayList<>();
        return this;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public AgentRunResult setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public AgentRunResult setStopReason(String stopReason) {
        this.stopReason = stopReason;
        return this;
    }

    public boolean isHadInjections() {
        return hadInjections;
    }

    public AgentRunResult setHadInjections(boolean hadInjections) {
        this.hadInjections = hadInjections;
        return this;
    }

    public Map<String, Integer> getUsage() {
        return usage;
    }

    public AgentRunResult setUsage(Map<String, Integer> usage) {
        this.usage = usage != null ? usage : new HashMap<>();
        return this;
    }

    public String getError() {
        return error;
    }

    public AgentRunResult setError(String error) {
        this.error = error;
        return this;
    }

    public List<Map<String, Object>> getToolEvents() {
        return toolEvents;
    }

    public AgentRunResult setToolEvents(List<Map<String, Object>> toolEvents) {
        this.toolEvents = toolEvents != null ? toolEvents : new ArrayList<>();
        return this;
    }
}