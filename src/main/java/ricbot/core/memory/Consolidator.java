package ricbot.core.memory;

import ricbot.core.session.Session;
import ricbot.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
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

    /**
     * 记忆存储接口
     */
    private final MemoryStore store;
    /**
     * LLM 提供者对象
     */
    private final Object provider;
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
     * 构建消息列表的方法引用或对象
     */
    private final Object buildMessages;
    /**
     * 获取工具定义的方法引用或对象
     */
    private final Object getToolDefinitions;
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
     * @param buildMessages       构建消息的对象
     * @param getToolDefinitions  获取工具定义的对象
     * @param maxCompletionTokens 最大生成 Token 数
     */
    public Consolidator(
            MemoryStore store,
            Object provider,
            String model,
            SessionManager sessions,
            Integer contextWindowTokens,
            Object buildMessages,
            Object getToolDefinitions,
            int maxCompletionTokens
    ) {
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.buildMessages = buildMessages;
        this.getToolDefinitions = getToolDefinitions;
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
        try {
            String summary = "Archived " + messages.size() + " messages";
            store.appendHistory(summary);
            return summary;
        } catch (Exception e) {
            log.warn("Consolidation failed, raw-dumping to history");
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
        // 如果没有消息或未配置上下文窗口，直接返回
        if (session.getMessages().isEmpty() || contextWindowTokens == null || contextWindowTokens <= 0) {
            return;
        }

        // 使用会话特定的锁，避免并发修改问题
        synchronized (getLock(session.getKey())) {
            // 计算可用预算：总窗口 - 最大响应预留 - 安全缓冲
            int budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
            // 简化版 Token 估算：假设每条消息平均 200 tokens
            int estimated = session.getMessages().size() * 200;

            // 如果估算值在预算内，无需整合
            if (estimated < budget) {
                return;
            }

            // 执行多轮整合，直到估算值降低到安全范围或达到最大轮数
            for (int round = 0; round < MAX_CONSOLIDATION_ROUNDS; round++) {
                // 如果估算值已降至预算的一半以下，停止整合
                if (estimated <= budget / 2) return;

                // 确定本次整合的消息片段范围
                int start = session.getLastConsolidated();
                int end = Math.min(session.getMessages().size(), start + 10);
                
                // 如果没有更多消息可整合，退出
                if (end <= start) return;

                // 截取消息片段并归档
                List<Map<String, Object>> chunk = session.getMessages().subList(start, end);
                String archived = archive(chunk);
                
                // 如果归档失败（返回 null），中止后续操作
                if (archived == null) return;

                // 更新最后整合位置并保存会话状态
                session.setLastConsolidated(end);
                sessions.save(session);
                
                // 更新估算 Token 数
                estimated -= chunk.size() * 200;
            }
        }
    }
}