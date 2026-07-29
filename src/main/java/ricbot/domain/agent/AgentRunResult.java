package ricbot.domain.agent;


import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.agent.event.AgentEvent;

/**
 * Agent Runtime 运行结果
 *
 * 对应 Python runner.run(...) 的返回对象语义
 */
@Getter
@Setter
@Accessors(chain = true)
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
     * Token 使用情况统计
     */
    private Map<String, Integer> usage = new HashMap<>();
    private UsageLedger usageLedger = UsageLedger.empty();
    private List<AgentEvent> events = new ArrayList<>();

    /**
     * 错误信息
     */
    private String error;

    /**
     * 工具调用事件列表
     */
    private List<Map<String, Object>> toolEvents = new ArrayList<>();

    /**
     * 单次运行的结构化事件轨迹。
     */
    private List<Map<String, Object>> runEvents = new ArrayList<>();

    private String runId;

    private String startedAt;

    private String endedAt;

    private int iterations;

    public void setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed != null ? toolsUsed : new ArrayList<>();
    }

    public AgentRunResult setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        return this;
    }

    public AgentRunResult setUsage(Map<String, Integer> usage) {
        this.usage = usage != null ? usage : new HashMap<>();
        return this;
    }

    public AgentRunResult setUsageLedger(UsageLedger ledger) {
        this.usageLedger = ledger != null ? ledger : UsageLedger.empty();
        this.usage = this.usageLedger.legacyUsage();
        return this;
    }

    public AgentRunResult setEvents(List<AgentEvent> events) {
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
        return this;
    }

    public AgentRunResult setToolEvents(List<Map<String, Object>> toolEvents) {
        this.toolEvents = toolEvents != null ? toolEvents : new ArrayList<>();
        return this;
    }

    public AgentRunResult setRunEvents(List<Map<String, Object>> runEvents) {
        this.runEvents = runEvents != null ? runEvents : new ArrayList<>();
        return this;
    }
}
