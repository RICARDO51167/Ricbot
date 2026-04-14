package ricbot.tool.process;


import ricbot.core.subagent.SubagentManager;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.util.List;

/**
 * 对应 Python: SpawnTool
 *
 * 主要目标：
 * 1. 启动一个后台 subagent
 * 2. 把任务交给 SubagentManager
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
        return "Spawn a background subagent to work on a task asynchronously.";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("task", "string", "Task description for the subagent", true),
                ToolParam.of("label", "string", "Optional short label for the background task", false),
                ToolParam.of("session_key", "string", "Optional session key for grouping/cancellation", false)
        );
    }

    /**
     * 对应 Python: set_context(channel, chat_id)
     */
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
            return "Error: subagent manager is not available.";
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