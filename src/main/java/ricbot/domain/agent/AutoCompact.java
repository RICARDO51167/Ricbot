package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * AutoCompact：自动压缩空闲会话。
 *
 * 对应 Python autocompact.py
 */
public class AutoCompact {

    private static final Logger log = LoggerFactory.getLogger(AutoCompact.class);

    // 定义保留的最近消息数量，用于在归档时保留上下文后缀
    private static final int DEFAULT_RECENT_SUFFIX_MESSAGES = 8;

    // 会话管理器，用于获取、保存和管理会话
    private final SessionManager sessions;
    //  consolidator 用于生成对话摘要
    private final Consolidator consolidator;
    // 会话过期时间（分钟），超过此时间未活动的会话将被视为过期
    private final int ttlMinutes;
    private final int recentSuffixMessages;

    /**
     * 正在归档的 session key，避免重复归档
     * 使用线程安全的 HashSet 来存储正在处理归档的会话键
     */
    private final Set<String> archiving = Collections.synchronizedSet(new HashSet<>());

    /**
     * 最近一次摘要缓存
     * key -> (summary, lastActive)
     * 用于内存中缓存最近的会话摘要，避免重复计算或读取
     */
    private final Map<String, SummaryRecord> summaries = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param sessions           会话管理器
     * @param consolidator       摘要 consolidator
     * @param sessionTtlMinutes  会话生存时间（分钟）
     */
    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        this(sessions, consolidator, sessionTtlMinutes, DEFAULT_RECENT_SUFFIX_MESSAGES);
    }

    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes, int recentSuffixMessages) {
        this.sessions = sessions;
        this.consolidator = consolidator;
        this.ttlMinutes = sessionTtlMinutes;
        this.recentSuffixMessages = recentSuffixMessages > 0 ? recentSuffixMessages : DEFAULT_RECENT_SUFFIX_MESSAGES;
    }

    /**
     * 判断给定时间戳是否已过期
     *
     * @param ts  最后活动时间戳
     * @param now 当前时间戳（若为 null 则使用系统当前时间）
     * @return 如果已过期返回 true，否则返回 false
     */
    public boolean isExpired(Instant ts, Instant now) {
        // 如果 TTL 设置无效或时间戳为空，则认为未过期
        if (ttlMinutes <= 0 || ts == null) {
            return false;
        }
        // 确定参考时间：如果传入 now 则使用它，否则使用当前系统时间
        Instant ref = now != null ? now : Instant.now();
        // 计算时间差并判断是否超过 TTL 分钟数
        return Duration.between(ts, ref).toMinutes() >= ttlMinutes;
    }

    /**
     * 格式化摘要信息，添加闲置时间提示
     *
     * @param text       原始摘要文本
     * @param lastActive 最后活动时间
     * @return 格式化后的摘要字符串
     */
    public String formatSummary(String text, Instant lastActive) {
        // 计算从最后活动到现在的闲置分钟数
        long idleMin = Math.max(0, Duration.between(lastActive, Instant.now()).toMinutes());
        // 返回包含闲置时间和之前对话摘要的字符串
        return "已闲置 " + idleMin + " 分钟。\n之前的对话摘要：" + text;
    }

    /**
     * 把未 consolidate 的 tail 分成：
     * 1. 可归档前缀
     * 2. 保留的最近合法后缀
     *
     * @param session 当前会话对象
     * @return SplitResult 包含可归档消息列表和需保留的消息列表
     */
    public SplitResult splitUnconsolidated(Session session) {
        // 获取从未合并点（lastConsolidated）到消息末尾的所有消息作为尾部
        List<Map<String, Object>> tail =
                new ArrayList<>(session.getMessages().subList(
                        Math.min(session.getLastConsolidated(), session.getMessages().size()),
                        session.getMessages().size()
                ));

        // 如果尾部为空，直接返回空结果
        if (tail.isEmpty()) {
            return new SplitResult(List.of(), List.of());
        }

        // 创建一个临时会话对象 probe，仅包含尾部消息，用于模拟保留逻辑
        Session probe = new Session(
                session.getKey(),
                new ArrayList<>(tail),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                new HashMap<>(),
                0
        );
        // 调用 retainRecentLegalSuffix 保留最近的合法后缀消息（例如保留最后的用户-AI 对话对）
        probe.retainRecentLegalSuffix(recentSuffixMessages);

        // 获取保留下来的消息列表
        List<Map<String, Object>> kept = probe.getMessages();
        // 计算需要切割的位置：总尾部长度减去保留的长度
        int cut = tail.size() - kept.size();

        // 返回分割结果：前部分是可归档的，后部分是需保留的
        return new SplitResult(
                new ArrayList<>(tail.subList(0, Math.max(0, cut))),
                kept
        );
    }

    /**
     * 检查过期 session，并把归档任务丢到后台执行器。
     *
     * @param activeSessionKeys  当前活跃的会话键集合，活跃会话不进行归档
     */
    public void checkExpired(
            Executor executor,
            Collection<String> activeSessionKeys
    ) {
        // 获取当前时间
        Instant now = Instant.now();

        // 遍历所有会话的基本信息
        for (Map<String, Object> info : sessions.listSessions()) {
            // 获取会话键
            String key = String.valueOf(info.getOrDefault("key", ""));
            // 如果键为空，跳过
            if (key.isBlank()) {
                continue;
            }
            // 如果该会话正在归档中，跳过以避免重复处理
            if (archiving.contains(key)) {
                continue;
            }
            // 如果该会话在当前活跃列表中，跳过
            if (activeSessionKeys != null && activeSessionKeys.contains(key)) {
                continue;
            }

            // 解析最后更新时间
            Instant updatedAt = parseInstantSafely(info.get("updated_at"));

            // 判断会话是否过期
            if (isExpired(updatedAt, now)) {
                // 标记该会话为正在归档
                archiving.add(key);
                try {
                    Executor exec = executor != null ? executor : ForkJoinPool.commonPool();
                    CompletableFuture.runAsync(() -> archive(key), exec)
                            .exceptionally(err -> {
                                log.warn("自动归档任务执行失败: sessionKey={}", key, err);
                                return null;
                            });
                } catch (Exception e) {
                    archiving.remove(key);
                    log.warn("调度自动归档任务失败: sessionKey={}", key, e);
                }
            }
        }
    }

    /**
     * 真正执行归档。
     *
     * @param key 会话键
     */
    public void archive(String key) {
        try {
            // 使缓存中的会话失效，确保获取最新数据
            sessions.invalidate(key);
            // 获取或创建会话对象
            Session session = sessions.getOrCreate(key);

            // 分割未合并的消息为可归档部分和保留部分
            SplitResult split = splitUnconsolidated(session);
            List<Map<String, Object>> archiveMsgs = split.archiveable();
            List<Map<String, Object>> keptMsgs = split.kept();

            // 如果没有可归档消息且没有保留消息，仅更新更新时间并保存
            if (archiveMsgs.isEmpty() && keptMsgs.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return;
            }

            // 记录最后活动时间
            Instant lastActive = session.getUpdatedAt();
            String summary = "";

            // 如果有可归档消息，尝试生成摘要
            if (!archiveMsgs.isEmpty()) {
                try {
                    // 调用 consolidator 生成摘要
                    summary = consolidator.archive(archiveMsgs);
                } catch (Exception e) {
                    // 如果生成摘要失败，置为空字符串
                    summary = "";
                    log.warn("归档摘要生成失败: sessionKey={}", key, e);
                }
            }

            // 如果摘要有效且非空且不是特定无内容标记，则缓存并保存到元数据
            if (summary != null && !summary.isBlank() && !"(无内容)".equals(summary)) {
                // 放入内存缓存
                summaries.put(key, new SummaryRecord(summary, lastActive));
                // 存入会话元数据
                session.getMetadata().put("_last_summary_text", summary);
                session.getMetadata().put("_last_summary_time", lastActive.toString());
            }

            // 构建新的消息列表：已合并的前缀 + 保留的后缀
            List<Map<String, Object>> prefix =
                    new ArrayList<>(session.getMessages().subList(0, session.getLastConsolidated()));
            List<Map<String, Object>> merged = new ArrayList<>(prefix);
            merged.addAll(keptMsgs);

            // 更新会话的消息列表、最后合并点和更新时间
            session.setMessages(merged);
            session.setLastConsolidated(prefix.size());
            session.setUpdatedAt(Instant.now());

            // 保存会话
            sessions.save(session);
        } finally {
            // 无论成功与否，最后都要从归档集合中移除该 key，允许后续再次处理
            archiving.remove(key);
        }
    }

    /**
     * 恢复 session 时，返回 session 和待注入摘要。
     *
     * @param session 会话对象
     * @param key     会话键
     * @return PreparedSession 包含会话对象和格式化的摘要信息
     */
    public PreparedSession prepareSession(Session session, String key) {
        // 从会话元数据中获取上次保存的摘要文本和时间
        Object txt = session.getMetadata().get("_last_summary_text");
        Object ts = session.getMetadata().get("_last_summary_time");

        // 如果元数据中存在有效的摘要信息，则格式化后返回
        if (txt instanceof String summaryText) {
            Instant lastActive = parseInstantSafely(ts);
            if (lastActive != null) {
                summaries.put(key, new SummaryRecord(summaryText, lastActive));
                return new PreparedSession(session, formatSummary(summaryText, lastActive));
            }
        }

        // 如果元数据中没有，尝试从内存缓存中获取
        SummaryRecord cached = summaries.get(key);
        if (cached != null) {
            return new PreparedSession(session, formatSummary(cached.text(), cached.lastActive()));
        }

        // 如果都没有，返回不带摘要的会话
        return new PreparedSession(session, null);
    }

    private Instant parseInstantSafely(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 记录摘要信息的记录类
     *
     * @param text       摘要文本
     * @param lastActive 最后活动时间
     */
    public record SummaryRecord(String text, Instant lastActive) {}

    /**
     * 准备会话的记录类
     *
     * @param session 会话对象
     * @param summary 待注入的摘要
     */
    public record PreparedSession(Session session, String summary) {}

    /**
     * 分割结果的记录类
     *
     * @param archiveable 可归档的消息列表
     * @param kept        需保留的消息列表
     */
    public record SplitResult(
            List<Map<String, Object>> archiveable,
            List<Map<String, Object>> kept
    ) {}
}
