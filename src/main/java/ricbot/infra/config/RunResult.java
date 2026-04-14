package ricbot.infra.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对应 Python: RunResult dataclass
 *
 * 主要目标：
 * 1. 表示一次 agent.run(...) 的返回结果
 *
 * Python:
 * @dataclass(slots=True)
 * class RunResult:
 *     content: str
 *     tools_used: list[str]
 *     messages: list[dict[str, Any]]
 */
public class RunResult {

    private String content = "";
    private List<String> toolsUsed = new ArrayList<>();
    private List<Map<String, Object>> messages = new ArrayList<>();

    public RunResult() {
    }

    public RunResult(String content, List<String> toolsUsed, List<Map<String, Object>> messages) {
        this.content = content;
        this.toolsUsed = toolsUsed != null ? toolsUsed : new ArrayList<>();
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public void setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages;
    }

    @Override
    public String toString() {
        return "RunResult{" +
                "content='" + content + '\'' +
                ", toolsUsed=" + toolsUsed +
                ", messages=" + messages +
                '}';
    }
}