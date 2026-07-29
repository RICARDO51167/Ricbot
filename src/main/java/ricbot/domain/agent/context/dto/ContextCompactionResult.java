package ricbot.domain.agent.context.dto;

import java.util.List;
import java.util.Map;

/**
 * 上下文压缩操作的结果。
 * 包含状态标志、消息列表、令牌计数以及用于审计/调试的摘要信息。
 * @author rcd
 */
public record ContextCompactionResult(
        /** 表示上下文是否已成功压缩。 */
        boolean compacted,
        /** 表示结果是否降级（例如，由于截断）。 */
        boolean degraded,
        /** 压缩过程中处理的事件消息列表。 */
        List<Map<String, Object>> eventMessages,
        /** 压缩后保留的活动消息列表。 */
        List<Map<String, Object>> activeMessages,
        /** 参与压缩的源消息ID列表。 */
        List<String> sourceMessageIds,
        /** 用于处理的AI模型。 */
        String model,
        /** 原始源上下文中的令牌数量。 */
        int sourceTokens,
        /** 压缩后结果上下文中的令牌数量。 */
        int resultTokens,
        /** 输入提示的摘要/哈希值，用于验证。 */
        String promptDigest,
        /** 输出结果的摘要/哈希值，用于验证。 */
        String resultDigest
) {
    public ContextCompactionResult {
        // 确保集合字段是不可变且安全的（非空）
        eventMessages = immutable(eventMessages);
        activeMessages = immutable(activeMessages);
        sourceMessageIds = List.copyOf(sourceMessageIds != null ? sourceMessageIds : List.of());
        
        // 确保字符串字段非空，默认值为空字符串
        model = model != null ? model : "";
        promptDigest = promptDigest != null ? promptDigest : "";
        resultDigest = resultDigest != null ? resultDigest : "";
    }

    /**
     * 创建一个不可变的列表，其中每个映射条目也被复制以实现深层不可变性。
     * 如果输入为null，则返回空列表。
     */
    private static List<Map<String, Object>> immutable(List<Map<String, Object>> messages) {
        return messages == null ? List.of() : messages.stream()
                .map(message -> Map.copyOf(message != null ? message : Map.of())).toList();
    }
}
