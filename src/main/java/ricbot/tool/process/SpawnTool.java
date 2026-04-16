package ricbot.tool.process;


import ricbot.domain.subagent.SubagentManager; // 导入子代理管理器类
import ricbot.tool.api.Tool; // 导入工具基类
import ricbot.tool.api.ToolParam; // 导入工具参数类

import java.util.List; // 导入列表接口

/**
 * 对应 Python: SpawnTool
 *
 * 主要目标：
 * 1. 启动一个后台子代理
 * 2. 把任务交给子代理管理器
 */
public class SpawnTool extends Tool {

    private final SubagentManager manager; // 子代理管理器实例，用于管理子代理的生命周期

    private String channel = "cli"; // 通信渠道，默认为 "cli"
    private String chatId = "direct"; // 聊天ID，默认为 "direct"

    public SpawnTool(SubagentManager manager) {
        this.manager = manager; // 构造函数注入子代理管理器
    }

    @Override
    public String getName() {
        return "spawn"; // 返回工具名称 "spawn"
    }

    @Override
    public String getDescription() {
        return "启动一个后台子代理，以异步方式执行任务。"; // 返回工具的描述信息
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("task", "string", "子代理要执行的任务描述", true), // 定义必需参数：任务描述
                ToolParam.of("label", "string", "可选：后台任务的简短标签", false), // 定义可选参数：任务标签
                ToolParam.of("session_key", "string", "可选：用于分组/取消的会话密钥", false) // 定义可选参数：会话密钥
        );
    }

    /**
     * 对应 Python: set_context(channel, chat_id)
     */
    public void setContext(String channel, String chatId) {
        if (channel != null && !channel.isBlank()) { // 如果渠道不为空且非空白字符串
            this.channel = channel; // 更新渠道
        }
        if (chatId != null && !chatId.isBlank()) { // 如果聊天ID不为空且非空白字符串
            this.chatId = chatId; // 更新聊天ID
        }
    }

    public String execute(String task, String label, String sessionKey) throws Exception {
        if (manager == null) { // 检查子代理管理器是否可用
            return "错误：子代理管理器不可用。"; // 如果不可用，返回错误信息
        }

        return manager.spawn( // 调用管理器的 spawn 方法启动子代理
                task, // 传入任务描述
                label, // 传入任务标签
                channel, // 传入当前渠道
                chatId, // 传入当前聊天ID
                sessionKey // 传入会话密钥
        );
    }
}
