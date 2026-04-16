package ricbot.domain.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 技能路由上下文记录类，封装了路由决策所需的所有信息。
 *
 * @param workspace   工作空间路径
 * @param channel     通信渠道标识
 * @param chatId      聊天会话ID
 * @param message     用户消息内容
 * @param toolNames   可用的工具名称列表
 * @param metadata    附加的元数据映射
 * @param variables   变量映射表
 */
public record SkillRoutingContext(
        Path workspace,              // 工作空间路径，用于定位项目文件
        String channel,              // 通信渠道，如 slack, discord 等
        String chatId,               // 当前聊天的唯一标识符
        String message,              // 用户输入的原始消息文本
        List<String> toolNames,      // 当前上下文中可用的工具名称列表
        Map<String, Object> metadata,// 额外的元数据信息，用于传递自定义上下文
        Map<String, String> variables// 变量映射，用于模板替换或状态存储
) {
}

