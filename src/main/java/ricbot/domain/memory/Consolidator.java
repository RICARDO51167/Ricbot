package ricbot.domain.memory;

import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量级会话归档器。
 * <p>
 * 负责监控会话消息的 Token 使用量，并在接近上下文窗口限制时，
 * 将旧消息归档以释放空间。对应 Python 实现中的 Consolidator。
 */
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);
    /**
     * 安全缓冲区 Token 数，防止精确估算误差导致溢出
     */
    private static final int SAFETY_BUFFER = 4096;
    /**
     * 单次触发的最大归档轮数，防止无限循环
     */
    private static final int MAX_CONSOLIDATION_ROUNDS = 8;
    private static final int MIN_KEEP_MESSAGES = 8;
    private static final int MAX_ARCHIVE_PROMPT_CHARS = 60_000;
    private static final int MAX_MESSAGE_SNIPPET_CHARS = 2_000;

    /**
     * 记忆存储接口
     */
    private final MemoryStore store;
    /**
     * LLM 提供者对象
     */
    private final LLMProvider provider;
    /**
     * 使用的模型名称
     */
    private final String model;
    /**
     * 会话管理器
     */
    private final SessionManager sessions;
    /**
     * 上下文窗口最大 Token 数
     */
    private final Integer contextWindowTokens;

    /**
     * 最大完成 Token 数，用于预留响应空间
     */
    private final int maxCompletionTokens;

    /**
     * 用于会话级别锁定的并发映射
     */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 构造 Consolidator
     *
     * @param store               记忆存储
     * @param provider            LLM 提供者
     * @param model               模型名称
     * @param sessions            会话管理器
     * @param contextWindowTokens 上下文窗口大小
     * @param maxCompletionTokens 最大生成 Token 数
     */
    public Consolidator(
            MemoryStore store,
            LLMProvider provider,
            String model,
            SessionManager sessions,
            Integer contextWindowTokens,
            int maxCompletionTokens
    ) {
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = maxCompletionTokens;
    }

    /**
     * 获取指定 key 的锁对象，确保同一会话的并发操作互斥
     *
     * @param key 会话唯一标识
     * @return 锁对象
     */
    public Object getLock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 归档消息列表。
     * 尝试生成摘要并存储，如果失败则原始存储消息列表。
     *
     * @param messages 待归档的消息列表
     * @return 归档摘要字符串，如果发生异常并回退到原始存储则返回 null
     */
    public String archive(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        try {
            StringBuilder conversation = new StringBuilder();
            for (Map<String, Object> msg : messages) {
                String role = msg != null && msg.get("role") != null ? String.valueOf(msg.get("role")) : "unknown";
                Object contentObj = msg != null ? msg.get("content") : null;
                String content = stringifyMessageContent(contentObj);
                if (content.length() > MAX_MESSAGE_SNIPPET_CHARS) {
                    content = content.substring(0, MAX_MESSAGE_SNIPPET_CHARS) + "\n... (truncated)";
                }

                String line = role + ": " + content + "\n";
                if (conversation.length() + line.length() > MAX_ARCHIVE_PROMPT_CHARS) {
                    conversation.append("\n... (conversation truncated)\n");
                    break;
                }
                conversation.append(line);
            }

            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("conversation", conversation.toString());
            String prompt = PromptTemplates.renderTemplate("agent/consolidator_archive.md", true, kwargs);

            List<Map<String, Object>> promptMessages = new ArrayList<>();
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", prompt);
            promptMessages.add(systemMsg);

            LLMResponse response = provider.chat(promptMessages, List.of(), model, null, null, null, null);
            String summary = response.getContent();

            String normalized = summary != null ? summary.trim() : "";
            if (normalized.isBlank() || "(nothing)".equalsIgnoreCase(normalized) || "(无内容)".equals(normalized)) {
                store.rawArchive(messages);
                return "(nothing)";
            }

            store.appendHistory(normalized);
            return normalized;
        } catch (Exception e) {
            log.warn("会话整合失败，转为原始消息存入历史", e);
            store.rawArchive(messages);
            return null;
        }
    }

    /**
     * 根据 Token 估算值判断是否需要执行会话整合（归档）。
     * 如果当前消息估算 Token 超过预算，则分块归档旧消息。
     *
     * @param session 当前会话对象
     */
    public void maybeConsolidateByTokens(Session session) {
        // 检查会话中是否有消息，或者上下文窗口大小是否未配置/无效，若是则直接返回
        if (session.getMessages().isEmpty() || contextWindowTokens == null || contextWindowTokens <= 0) {
            return;
        }

        // 获取基于会话 Key 的锁对象，确保同一会话的并发操作互斥，避免数据竞争
        synchronized (getLock(session.getKey())) {
            // 计算可用于历史消息的 Token 预算：总窗口大小减去最大响应预留和安全缓冲
            int budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
            // 如果计算出的预算非正数，说明没有空间容纳历史消息，直接返回
            if (budget <= 0) {
                return;
            }

            // 估算当前会话所有消息占用的 Token 总数
            int estimated = estimateTokens(session.getMessages());

            // 如果当前估算的 Token 数小于预算，说明空间充足，无需进行整合
            if (estimated < budget) {
                return;
            }

            // 记录日志，表明开始对指定会话进行整合，并输出预估 Token 数和可用预算
            log.info("开始对会话 {} 进行整合，预估 Token: {}, 预算: {}", 
                    session.getKey(), estimated, budget);

            // 进入循环，执行多轮整合操作，直到 Token 使用量降至安全范围或达到最大尝试轮数
            for (int round = 0; round < MAX_CONSOLIDATION_ROUNDS; round++) {
                // 在每轮开始前检查：如果当前估算值已满足预算要求，则退出循环
                if (estimated <= budget) {
                    return;
                }
                // 检查剩余消息数量是否已达到最小保留阈值，若是则停止整合以保留必要上下文
                if (session.getMessages().size() <= MIN_KEEP_MESSAGES) {
                    return;
                }

                // 计算最大可裁剪的消息数量：总消息数减去必须保留的最小消息数
                int maxCut = session.getMessages().size() - MIN_KEEP_MESSAGES;
                // 计算超出预算的 Token 数量（溢出量）
                int overflow = estimated - budget;
                // 确定本轮目标移除的 Token 数：取“预算的四分之一”和“溢出量”中的较大值，确保有效削减
                int targetRemoveTokens = Math.max(budget / 4, overflow);

                // 根据目标移除 Token 数和最大裁剪限制，计算具体的裁剪索引位置
                int cut = pickCutIndex(session.getMessages(), maxCut, targetRemoveTokens);
                // 如果计算出的裁剪索引无效（<=0），说明无法找到合适的切割点，退出方法
                if (cut <= 0) {
                    return;
                }

                // 提取从开头到裁剪索引处的消息列表，作为待归档的数据块
                List<Map<String, Object>> chunk = new ArrayList<>(session.getMessages().subList(0, cut));
                // 调用 archive 方法对提取的消息块进行归档处理，获取归档后的摘要或状态
                String archived = archive(chunk);
                // 如果归档失败（返回 null），则中止后续操作，防止数据不一致
                if (archived == null) {
                    return;
                }

                // 更新会话消息列表，保留从裁剪索引之后到末尾的消息
                session.setMessages(new ArrayList<>(session.getMessages().subList(cut, session.getMessages().size())));
                // 调整最后整合的位置索引，减去已移除的消息数量，并确保不为负数
                session.setLastConsolidated(Math.max(0, session.getLastConsolidated() - cut));
                // 持久化保存更新后的会话状态
                sessions.save(session);

                // 重新估算剩余消息的 Token 总数，用于下一轮循环判断或退出条件
                estimated = estimateTokens(session.getMessages());
            }
        }
    }

    private int pickCutIndex(List<Map<String, Object>> messages, int maxCut, int targetTokens) {
        int tokens = 0;
        int cut = 0;
        for (int i = 0; i < maxCut; i++) {
            tokens += estimateTokens(messages.get(i));
            cut = i + 1;
            if (tokens >= targetTokens) {
                break;
            }
        }
        if (cut <= 0) {
            return 0;
        }
        return adjustCutForToolLegality(messages, cut, maxCut);
    }

    private int adjustCutForToolLegality(List<Map<String, Object>> messages, int cut, int maxCut) {
        if (cut >= messages.size()) {
            return Math.min(cut, maxCut);
        }
        List<Map<String, Object>> remaining = messages.subList(cut, messages.size());
        int legalStart = findLegalMessageStart(remaining);
        int adjusted = cut + legalStart;
        return Math.min(adjusted, maxCut);
    }

    private int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            String role = String.valueOf(msg.get("role"));

            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc) {
                            Object id = tc.get("id");
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    private int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        if (messages == null) {
            return 0;
        }
        for (Map<String, Object> msg : messages) {
            total += estimateTokens(msg);
        }
        return total;
    }

    private int estimateTokens(Map<String, Object> msg) {
        if (msg == null) {
            return 0;
        }
        String role = msg.get("role") != null ? String.valueOf(msg.get("role")) : "";
        Object contentObj = msg.get("content");
        int tokens = 0;

        tokens += approximateTokens(role);
        tokens += approximateTokens(stringifyMessageContent(contentObj));

        Object toolCalls = msg.get("tool_calls");
        if (toolCalls instanceof List<?> list) {
            tokens += 20;
            for (Object item : list) {
                tokens += approximateTokens(String.valueOf(item));
            }
        }

        Object name = msg.get("name");
        if (name != null) {
            tokens += approximateTokens(String.valueOf(name));
        }

        return tokens + 6;
    }

    private String stringifyMessageContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object itemObj : list) {
                if (!(itemObj instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String type = item.get("type") != null ? String.valueOf(item.get("type")) : "";
                if ("text".equals(type)) {
                    sb.append(item.get("text") != null ? String.valueOf(item.get("text")) : "");
                    sb.append("\n");
                } else if ("image_url".equals(type)) {
                    sb.append("[image]").append("\n");
                } else {
                    sb.append("[").append(type).append("]").append("\n");
                }
                if (sb.length() > MAX_MESSAGE_SNIPPET_CHARS) {
                    break;
                }
            }
            return sb.toString().trim();
        }
        return String.valueOf(content);
    }

    private int approximateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7F) {
                ascii++;
            } else {
                nonAscii++;
            }
        }

        int t = (ascii / 4) + (nonAscii / 2);
        return Math.max(1, t);
    }
}
