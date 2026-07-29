package ricbot.domain.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.context.dto.ContextCompactionResult;
import ricbot.domain.agent.context.dto.StructuredContextSummary;
import ricbot.domain.agent.context.enump.MessageMark;
import ricbot.domain.runtime.dto.RuntimeDigest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 非破坏性、感知工具调用的上下文压缩策略。 */
public final class ContextCompactor {
    // 触发压缩的阈值比例：当当前 Token 数超过可用 Token 数的 80% 时触发
    public static final double TRIGGER_RATIO = 0.80d;
    // 目标压缩后的比例：期望将 Token 数降低到可用 Token 数的 60%
    public static final double TARGET_RATIO = 0.60d;
    // 始终保留最近的 N 条消息，防止丢失最新上下文
    public static final int RECENT_MESSAGES = 8;
    // 消息唯一 ID 的键名
    public static final String MESSAGE_ID = "_ricbot_message_id";
    // 消息标记（如已压缩、已保留等）的键名
    public static final String MESSAGE_MARKS = "_ricbot_marks";

    /**
     * 摘要生成器函数式接口。
     * 用于根据给定的消息列表和提示词生成结构化的上下文摘要。
     */
    @FunctionalInterface
    public interface SummaryGenerator {
        StructuredContextSummary summarize(List<Map<String, Object>> messages, String prompt) throws Exception;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * 执行上下文压缩的主入口方法。
     *
     * @param input         原始消息列表
     * @param availableInputTokens 模型可用的输入 Token 总数
     * @param model         使用的模型名称
     * @param generator     自定义摘要生成器，若为 null 则使用保守策略
     * @return 压缩结果对象
     */
    public ContextCompactionResult compact(List<Map<String, Object>> input, int availableInputTokens,
                                           String model, SummaryGenerator generator) {
        return compact(input, availableInputTokens, model, generator, false);
    }

    /**
     * 执行上下文压缩，支持强制模式。
     * 即使本地估算未超标，若 provider 确认溢出且 force=true，也会强制执行压缩。
     *
     * @param input         原始消息列表
     * @param availableInputTokens 模型可用的输入 Token 总数
     * @param model         使用的模型名称
     * @param generator     自定义摘要生成器
     * @param force         是否强制进行压缩
     * @return 压缩结果对象
     */
    public ContextCompactionResult compact(List<Map<String, Object>> input, int availableInputTokens,
                                           String model, SummaryGenerator generator, boolean force) {
        // 1. 标准化输入：确保每条消息都有唯一的 ID 和默认标记
        List<Map<String, Object>> messages = normalize(input);
        
        // 2. 估算当前总 Token 数
        int currentTokens = estimate(messages);
        
        // 3. 判断是否需要压缩：
        //    - 如果可用 Token <= 0，或
        //    - 非强制模式下，当前 Token < 可用 Token * 触发比例 (80%)
        if (availableInputTokens <= 0 || (!force && currentTokens < availableInputTokens * TRIGGER_RATIO)) {
            return unchanged(messages, model, currentTokens);
        }

        // 4. 确定需要保留的消息索引（最近的消息 + 与工具调用相关的消息对）
        Set<Integer> preserved = preservedIndexes(messages);
        
        // 5. 收集可被压缩的消息候选者（排除已保留的）
        List<Integer> candidates = new ArrayList<>();
        int activeTokens = currentTokens;
        
        // 计算目标 Token 数：
        // - 强制模式：取 (可用Token * 60%) 和 (当前Token * 75%) 中的较小值，至少为 1
        // - 普通模式：取 (可用Token * 60%)
        int target = force ? Math.min((int) Math.floor(availableInputTokens * TARGET_RATIO),
                Math.max(1, (int) Math.floor(currentTokens * 0.75d)))
                : (int) Math.floor(availableInputTokens * TARGET_RATIO);
        
        // 贪心算法：从前往后遍历，累加候选者直到剩余 Token 接近目标
        for (int index = 0; index < messages.size() && activeTokens > target; index++) {
            if (preserved.contains(index)) continue; // 跳过必须保留的消息
            candidates.add(index);
            activeTokens -= estimate(messages.get(index));
        }

        // 如果没有候选者可压缩，直接返回不变的结果
        if (candidates.isEmpty()) return unchanged(messages, model, currentTokens);

        // 6. 准备待压缩的消息源和提示词
        List<Map<String, Object>> source = candidates.stream().map(messages::get).toList();
        String prompt = prompt(source);
        
        // 7. 尝试生成摘要（优先使用 AI 生成器，失败则回退到保守策略）
        StructuredContextSummary summary = null;
        boolean degraded = false;
        if (generator != null) {
            // 最多尝试 2 次
            for (int attempt = 0; attempt < 2 && summary == null; attempt++) {
                try { 
                    summary = generator.summarize(source, prompt); 
                }
                catch (Exception ignored) { /* 第二次失败后选择确定性回退方案 */ }
            }
        }
        
        // 如果生成器为空或两次尝试都失败，使用保守的硬编码摘要策略
        if (summary == null) {
            degraded = true;
            summary = conservativeSummary(source);
        }

        // 8. 构建最终的消息列表
        Set<Integer> compressed = Set.copyOf(candidates);
        List<Map<String, Object>> marked = new ArrayList<>(); // 包含所有消息（含摘要）的完整列表
        List<Map<String, Object>> active = new ArrayList<>(); // 仅包含未被压缩的有效消息列表
        List<String> sourceIds = new ArrayList<>(); // 记录被压缩消息的原始 ID
        
        int firstCompressed = candidates.get(0); // 第一条被压缩的消息索引
        Map<String, Object> summaryMessage = summaryMessage(summary, source, model, degraded);
        
        for (int index = 0; index < messages.size(); index++) {
            // 在第一条被压缩的消息位置插入生成的摘要消息
            if (index == firstCompressed) {
                marked.add(summaryMessage);
                active.add(summaryMessage);
            }
            
            Map<String, Object> message = new LinkedHashMap<>(messages.get(index));
            if (compressed.contains(index)) {
                // 标记为已压缩，并记录其原始 ID
                message.put(MESSAGE_MARKS, List.of(MessageMark.COMPRESSED.name()));
                sourceIds.add(String.valueOf(message.get(MESSAGE_ID)));
            } else {
                // 标记为已保留或活跃
                message.put(MESSAGE_MARKS, List.of(preserved.contains(index)
                        ? MessageMark.PRESERVED.name() : MessageMark.ACTIVE.name()));
                active.add(message);
            }
            marked.add(message);
        }
        
        int resultTokens = estimate(active);
        return new ContextCompactionResult(true, degraded, marked, active, sourceIds, clean(model),
                currentTokens, resultTokens, RuntimeDigest.sha256(prompt), RuntimeDigest.sha256(summary));
    }

    /**
     * 检查一条消息是否已被标记为“已压缩”。
     *
     * @param message 待检查的消息
     * @return 如果是压缩消息则返回 true，否则 false
     */
    public static boolean compressed(Map<String, Object> message) {
        Object marks = message != null ? message.get(MESSAGE_MARKS) : null;
        return marks instanceof List<?> list && list.stream().map(String::valueOf)
                .anyMatch(MessageMark.COMPRESSED.name()::equals);
    }

    /**
     * 标准化输入消息列表：
     * - 填充缺失的 MESSAGE_ID
     * - 填充缺失的 MESSAGE_MARKS（默认为 ACTIVE）
     * - 处理空输入
     */
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> input) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> original : input != null ? input : List.<Map<String, Object>>of()) {
            Map<String, Object> message = new LinkedHashMap<>(original != null ? original : Map.of());
            // 如果缺少 ID，生成一个基于内容和索引的唯一哈希 ID
            message.putIfAbsent(MESSAGE_ID, "msg-" + RuntimeDigest.sha256(Map.of("index", index, "message", message)));
            // 如果缺少标记，默认为 ACTIVE
            message.putIfAbsent(MESSAGE_MARKS, List.of(MessageMark.ACTIVE.name()));
            normalized.add(message);
            index++;
        }
        return normalized;
    }

    /**
     * 计算必须保留的消息索引集合。
     * 规则：
     * 1. 保留最近的 RECENT_MESSAGES 条消息。
     * 2. 递归保留与这些保留消息有工具调用关联的消息（即 tool_call 和 tool_result 成对出现）。
     */
    private static Set<Integer> preservedIndexes(List<Map<String, Object>> messages) {
        Set<Integer> preserved = new LinkedHashSet<>();
        // 首先保留最近的 N 条消息
        for (int index = Math.max(0, messages.size() - RECENT_MESSAGES); index < messages.size(); index++) {
            preserved.add(index);
        }
        
        boolean changed;
        do {
            changed = false;
            for (int index = 0; index < messages.size(); index++) {
                // 获取当前消息的工具调用 ID 列表
                Set<String> calls = toolCallIds(messages.get(index));
                // 获取当前消息对应的工具结果 ID
                String resultFor = toolResultId(messages.get(index));
                
                // 如果当前消息已被保留，则检查它是否与其他消息有关联
                if (preserved.contains(index)) {
                    // 如果当前消息发起了工具调用，则保留接收该调用结果的对应消息
                    if (!calls.isEmpty()) {
                        for (int other = 0; other < messages.size(); other++) {
                            if (calls.contains(toolResultId(messages.get(other)))) changed |= preserved.add(other);
                        }
                    }
                    // 如果当前消息是工具结果，则保留发起该调用的对应消息
                    if (!resultFor.isBlank()) {
                        for (int other = 0; other < messages.size(); other++) {
                            if (toolCallIds(messages.get(other)).contains(resultFor)) changed |= preserved.add(other);
                        }
                    }
                }
            }
        } while (changed); // 循环直到没有新的消息被加入保留集
        return preserved;
    }

    /**
     * 提取消息中的工具调用 ID 列表。
     */
    private static Set<String> toolCallIds(Map<String, Object> message) {
        Set<String> ids = new LinkedHashSet<>();
        Object calls = message.get("tool_calls");
        if (calls instanceof List<?> list) {
            for (Object call : list) {
                if (call instanceof Map<?, ?> map && map.get("id") != null) {
                    ids.add(String.valueOf(map.get("id")));
                }
            }
        }
        return ids;
    }

    /**
     * 提取消息中的工具调用 ID（即该消息是对哪个工具调用的响应）。
     */
    private static String toolResultId(Map<String, Object> message) {
        return message.get("tool_call_id") != null ? String.valueOf(message.get("tool_call_id")) : "";
    }

    /**
     * 构建压缩后的摘要消息对象。
     * 包含元数据（源ID、模型、Token统计、摘要内容等）和系统角色内容。
     */
    private static Map<String, Object> summaryMessage(StructuredContextSummary summary,
                                                      List<Map<String, Object>> source, String model,
                                                      boolean degraded) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceMessageIds", source.stream().map(message -> message.get(MESSAGE_ID)).toList());
        metadata.put("model", clean(model));
        metadata.put("sourceTokens", estimate(source));
        metadata.put("resultTokens", estimateText(String.valueOf(summary)));
        metadata.put("promptDigest", RuntimeDigest.sha256(prompt(source)));
        metadata.put("resultDigest", RuntimeDigest.sha256(summary));
        metadata.put("degraded", degraded);
        
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", asJson(summary));
        message.put("context_compaction", metadata);
        // 生成摘要消息的唯一 ID
        message.put(MESSAGE_ID, "compact-" + RuntimeDigest.sha256(Map.of("source", metadata.get("sourceMessageIds"),
                "model", clean(model), "result", summary)));
        // 摘要消息本身被视为保留消息
        message.put(MESSAGE_MARKS, List.of(MessageMark.PRESERVED.name()));
        return message;
    }

    /**
     * 当无法调用 AI 生成器时的保守摘要策略。
     * 仅保留首尾内容片段和工具调用内容，作为简单上下文快照。
     */
    private static StructuredContextSummary conservativeSummary(List<Map<String, Object>> source) {
        String first = source.isEmpty() ? "" : bounded(String.valueOf(source.get(0).getOrDefault("content", "")));
        String last = source.isEmpty() ? "" : bounded(String.valueOf(source.get(source.size() - 1).getOrDefault("content", "")));
        return new StructuredContextSummary(first, last, List.of(), List.of("Continue from preserved recent context"),
                source.stream().filter(message -> "tool".equals(message.get("role")))
                        .map(message -> bounded(String.valueOf(message.getOrDefault("content", "")))).toList());
    }

    /**
     * 构建发送给摘要生成器的提示词。
     */
    private static String prompt(List<Map<String, Object>> source) {
        return "Summarize into taskOverview/currentState/importantDiscoveries/nextSteps/contextToPreserve: "
                + asJson(source);
    }

    /**
     * 返回未发生压缩的情况下的结果对象。
     */
    private static ContextCompactionResult unchanged(List<Map<String, Object>> messages, String model, int tokens) {
        List<Map<String, Object>> active = messages.stream().filter(message -> !compressed(message)).toList();
        return new ContextCompactionResult(false, false, messages, active, List.of(), clean(model), tokens,
                estimate(active), "", "");
    }

    /**
     * 估算一组消息的总 Token 数。
     */
    private static int estimate(List<Map<String, Object>> messages) {
        return messages.stream().mapToInt(ContextCompactor::estimate).sum();
    }
    
    /**
     * 估算单条消息的 Token 数（JSON 字符串长度 / 4 + 6 个开销 Token）。
     */
    private static int estimate(Map<String, Object> message) { 
        return estimateText(asJson(message)) + 6; 
    }
    
    /**
     * 估算文本的 Token 数（粗略估计：每 4 个字符约等于 1 个 Token）。
     * 至少返回 1，避免除以零或结果为 0。
     */
    private static int estimateText(String value) { 
        return Math.max(1, (value != null ? value.length() : 0) / 4); 
    }
    
    /**
     * 限制字符串长度，防止过长内容导致问题（截断至 800 字符）。
     */
    private static String bounded(String value) { 
        return value.length() <= 800 ? value : value.substring(0, 800); 
    }
    
    /**
     * 清理字符串：去除首尾空格，处理 null。
     */
    private static String clean(String value) { 
        return value != null ? value.trim() : ""; 
    }
    
    /**
     * 将对象转换为 JSON 字符串，失败时返回 toString() 结果。
     */
    private static String asJson(Object value) {
        try { 
            return MAPPER.writeValueAsString(value); 
        }
        catch (Exception e) { 
            return String.valueOf(value); 
        }
    }
}
