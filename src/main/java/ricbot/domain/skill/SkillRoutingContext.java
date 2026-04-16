package ricbot.domain.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 技能路由上下文记录类
 */
public record SkillRoutingContext(
        Path workspace,
        String channel,
        String chatId,
        String message,
        List<String> toolNames,
        Map<String, Object> metadata,
        Map<String, String> variables
) {
}

