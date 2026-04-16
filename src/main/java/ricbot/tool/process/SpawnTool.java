package ricbot.tool.process;

import ricbot.domain.subagent.SubagentManager;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.util.List;

/**
 * 启动后台子代理的工具类
 */
public class SpawnTool extends Tool {

    private final SubagentManager manager;

    private String channel = "cli";
    private String chatId = "direct";

    public SpawnTool(SubagentManager manager) {
        this.manager = manager;
    }

    @Override
    public String getName() {
        return "spawn";
    }

    @Override
    public String getDescription() {
        return "启动一个后台子代理，以异步方式执行任务。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("task", "string", "子代理要执行的任务描述", true),
                ToolParam.of("label", "string", "可选：后台任务的简短标签", false),
                ToolParam.of("session_key", "string", "可选：用于分组/取消的会话密钥", false)
        );
    }

    public void setContext(String channel, String chatId) {
        if (channel != null && !channel.isBlank()) {
            this.channel = channel;
        }
        if (chatId != null && !chatId.isBlank()) {
            this.chatId = chatId;
        }
    }

    public String execute(String task, String label, String sessionKey) throws Exception {
        if (manager == null) {
            return "错误：子代理管理器不可用。";
        }

        return manager.spawn(
                task,
                label,
                channel,
                chatId,
                sessionKey
        );
    }
}
