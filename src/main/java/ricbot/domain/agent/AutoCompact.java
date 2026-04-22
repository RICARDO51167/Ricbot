package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator; // 导入 Consolidator 接口，用于生成对话摘要
import ricbot.domain.session.Session; // 导入 Session 类，表示会话对象
import ricbot.domain.session.SessionManager; // 导入 SessionManager 接口，用于管理会话的存取
import org.slf4j.Logger; // 导入 SLF4J Logger 接口
import org.slf4j.LoggerFactory; // 导入 SLF4J LoggerFactory 工厂类

import java.time.Duration; // 导入 Duration 类，用于计算时间差
import java.time.Instant; // 导入 Instant 类，表示时间戳
import java.util.*; // 导入 Java 集合框架相关类
import java.util.concurrent.CompletableFuture; // 导入 CompletableFuture，用于异步编程
import java.util.concurrent.ConcurrentHashMap; // 导入 ConcurrentHashMap，线程安全的 Map 实现
import java.util.concurrent.Executor; // 导入 Executor 接口，用于执行异步任务
import java.util.concurrent.ForkJoinPool; // 导入 ForkJoinPool，常用的并行执行器

/**
 * AutoCompact：自动压缩空闲会话。
 * <p>
 * 对应 Python autocompact.py
 */
public class AutoCompact {

    // 日志记录器，用于记录运行时的信息和警告
    private static final Logger log = LoggerFactory.getLogger(AutoCompact.class);

    // 定义保留的最近消息数量默认值，用于在归档时保留上下文后缀，避免丢失最近的对话语境
    private static final int DEFAULT_RECENT_SUFFIX_MESSAGES = 8;

    // 会话管理器实例，用于获取、保存和管理会话数据
    private final SessionManager sessions;
    // Consolidator 实例，用于将旧消息压缩成摘要
    private final Consolidator consolidator;
    // 会话过期时间阈值（分钟），超过此时间未活动的会话将被视为过期并触发归档
    private final int ttlMinutes;
    // 实际使用的保留最近消息数量，若构造时传入非法值则使用默认值
    private final int recentSuffixMessages;

    /**
     * 正在归档的 session key 集合，用于避免对同一会话进行重复归档操作。
     * 使用 Collections.synchronizedSet 包装 HashSet 以保证线程安全。
     */
    private final Set<String> archiving = Collections.synchronizedSet(new HashSet<>());

    /**
     * 最近一次摘要缓存映射。
     * Key: 会话键 (String)
     * Value: SummaryRecord (包含摘要文本和最后活动时间)
     * 用途：内存中缓存最近的会话摘要，避免频繁读取元数据或重复计算。
     */
    private final Map<String, SummaryRecord> summaries = new ConcurrentHashMap<>();

    /**
     * 构造函数，使用默认的保留消息数量。
     *
     * @param sessions           会话管理器
     * @param consolidator       摘要生成器
     * @param sessionTtlMinutes  会话生存时间（分钟）
     */
    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        // 调用全参构造函数，传入默认的保留消息数量
        this(sessions, consolidator, sessionTtlMinutes, DEFAULT_RECENT_SUFFIX_MESSAGES);
    }

    /**
     * 全参构造函数。
     *
     * @param sessions              会话管理器
     * @param consolidator          摘要生成器
     * @param sessionTtlMinutes     会话生存时间（分钟）
     * @param recentSuffixMessages  归档时保留的最近消息数量
     */
    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes, int recentSuffixMessages) {
        this.sessions = sessions; // 初始化会话管理器
        this.consolidator = consolidator; // 初始化摘要生成器
        this.ttlMinutes = sessionTtlMinutes; // 初始化 TTL 阈值
        // 初始化保留消息数量，若传入值小于等于0则使用默认值
        this.recentSuffixMessages = recentSuffixMessages > 0 ? recentSuffixMessages : DEFAULT_RECENT_SUFFIX_MESSAGES;
    }

    /**
     * 判断给定时间戳是否已过期。
     *
     * @param ts  最后活动时间戳
     * @param now 当前参考时间戳（若为 null 则使用系统当前时间）
     * @return 如果已过期返回 true，否则返回 false
     */
    public boolean isExpired(Instant ts, Instant now) {
        // 如果 TTL 设置无效（<=0）或时间戳为空，则认为未过期，不进行归档
        if (ttlMinutes <= 0 || ts == null) {
            return false;
        }
        // 确定参考时间：优先使用传入的 now，否则使用当前系统时间
        Instant ref = now != null ? now : Instant.now();
        // 计算最后活动时间与参考时间之间的分钟差，判断是否超过 TTL 阈值
        return Duration.between(ts, ref).toMinutes() >= ttlMinutes;
    }

    /**
     * 格式化摘要信息，在原始摘要前添加闲置时间提示。
     *
     * @param text       原始摘要文本
     * @param lastActive 最后活动时间
     * @return 格式化后的摘要字符串，格式如："已闲置 X 分钟。\n之前的对话摘要：..."
     */
    public String formatSummary(String text, Instant lastActive) {
        // 计算从最后活动时刻到当前时刻的闲置分钟数，确保非负
        long idleMin = Math.max(0, Duration.between(lastActive, Instant.now()).toMinutes());
        // 拼接闲置提示和原始摘要文本
        return "已闲置 " + idleMin + " 分钟。\n之前的对话摘要：" + text;
    }

    /**
     * 将会话中未合并（unconsolidated）的尾部消息分割为两部分：
     * 1. 可归档的前缀消息（archiveable）
     * 2. 需要保留在最近上下文中的合法后缀消息（kept）
     *
     * @param session 当前会话对象
     * @return SplitResult 包含可归档消息列表和需保留的消息列表
     */
    public SplitResult splitUnconsolidated(Session session) {
        // 获取从上次合并点（lastConsolidated）到消息列表末尾的所有消息，作为待处理的尾部
        // 使用 Math.min 防止索引越界
        List<Map<String, Object>> tail =
                new ArrayList<>(session.getMessages().subList(
                        Math.min(session.getLastConsolidated(), session.getMessages().size()),
                        session.getMessages().size()
                ));

        // 如果尾部消息为空，直接返回空的分割结果
        if (tail.isEmpty()) {
            return new SplitResult(List.of(), List.of());
        }

        // 创建一个临时的会话对象 probe，仅包含尾部消息，用于模拟保留逻辑
        // 这样可以在不影响原会话的情况下计算哪些消息应该被保留
        Session probe = new Session(
                session.getKey(), // 保持相同的 Key
                new ArrayList<>(tail), // 复制尾部消息
                session.getCreatedAt(), // 保持创建时间
                session.getUpdatedAt(), // 保持更新时间
                new HashMap<>(), // 空的元数据
                0 // 初始化合并点为0，因为这是临时会话
        );
        // 调用 Session 的 retainRecentLegalSuffix 方法，根据配置保留最近的合法后缀消息（如完整的用户-AI 对话对）
        probe.retainRecentLegalSuffix(recentSuffixMessages);

        // 获取经过处理后保留下来的消息列表
        List<Map<String, Object>> kept = probe.getMessages();
        // 计算切割点：尾部总长度减去保留的长度，即为可归档部分的长度
        int cut = tail.size() - kept.size();

        // 构建并返回分割结果
        // archiveable: 从尾部开头到切割点的部分
        // kept: 保留下来的后缀部分
        return new SplitResult(
                new ArrayList<>(tail.subList(0, Math.max(0, cut))), // 确保切割点非负
                kept
        );
    }

    /**
     * 检查所有会话，找出过期的会话，并将归档任务提交到后台执行器异步执行。
     *
     * @param executor          用于执行归档任务的线程池/执行器，若为 null 则使用 ForkJoinPool.commonPool()
     * @param activeSessionKeys 当前活跃的会话键集合，活跃会话不会被归档
     */
    public void checkExpired(
            Executor executor,
            Collection<String> activeSessionKeys
    ) {
        // 获取当前时间点，用于后续判断过期
        Instant now = Instant.now();

        // 遍历会话管理器中所有会话的基本信息
        for (Map<String, Object> info : sessions.listSessions()) {
            // 从信息中提取会话键，默认为空字符串
            String key = String.valueOf(info.getOrDefault("key", ""));
            // 如果键为空或空白，跳过该条目
            if (key.isBlank()) {
                continue;
            }
            // 如果该会话已经在归档处理队列中，跳过以避免重复处理
            if (archiving.contains(key)) {
                continue;
            }
            // 如果该会话在当前活跃会话列表中，跳过不处理
            if (activeSessionKeys != null && activeSessionKeys.contains(key)) {
                continue;
            }

            // 安全解析会话的最后更新时间戳
            Instant updatedAt = parseInstantSafely(info.get("updated_at"));

            // 判断会话是否已过期
            if (isExpired(updatedAt, now)) {
                // 将该会话键加入归档集合，标记为正在处理
                archiving.add(key);
                try {
                    // 确定使用的执行器：优先使用传入的 executor，否则使用公共 ForkJoinPool
                    Executor exec = executor != null ? executor : ForkJoinPool.commonPool();
                    // 异步执行归档任务
                    CompletableFuture.runAsync(() -> archive(key), exec)
                            // 处理异步任务中可能发生的异常
                            .exceptionally(err -> {
                                log.warn("自动归档任务执行失败: sessionKey={}", key, err);
                                return null;
                            });
                } catch (Exception e) {
                    // 如果提交任务失败（极少见），立即从归档集合中移除该 key，以便下次重试
                    archiving.remove(key);
                    log.warn("调度自动归档任务失败: sessionKey={}", key, e);
                }
            }
        }
    }

    /**
     * 执行具体的会话归档逻辑。
     * <p>
     * 主要步骤：
     * 1. 加载会话
     * 2. 分割消息为可归档部分和保留部分
     * 3. 对可归档部分生成摘要
     * 4. 更新会话元数据和消息列表
     * 5. 保存会话
     *
     * @param key 会话键
     */
    public void archive(String key) {
        try {
            // 使会话缓存失效，确保从存储中获取最新的会话数据
            sessions.invalidate(key);
            // 获取或创建会话对象
            Session session = sessions.getOrCreate(key);

            // 分割未合并的消息：获取可归档的消息列表和需要保留的消息列表
            SplitResult split = splitUnconsolidated(session);
            List<Map<String, Object>> archiveMsgs = split.archiveable();
            List<Map<String, Object>> keptMsgs = split.kept();

            // 如果既没有可归档消息也没有保留消息（极端情况），仅更新更新时间并保存后返回
            if (archiveMsgs.isEmpty() && keptMsgs.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return;
            }

            // 记录会话的最后活动时间，用于摘要缓存
            Instant lastActive = session.getUpdatedAt();
            String summary = "";

            // 如果存在可归档的消息，尝试生成摘要
            if (!archiveMsgs.isEmpty()) {
                try {
                    // 调用 consolidator 生成这些消息的摘要
                    summary = consolidator.archive(archiveMsgs);
                } catch (Exception e) {
                    // 如果生成摘要过程中发生异常，记录警告并将摘要置为空
                    summary = "";
                    log.warn("归档摘要生成失败: sessionKey={}", key, e);
                }
            }

            // 如果生成的摘要有效（非空且不是特定的无内容标记），则进行缓存和持久化
            if (summary != null && !summary.isBlank() && !"(无内容)".equals(summary)) {
                // 放入内存缓存，方便快速读取
                summaries.put(key, new SummaryRecord(summary, lastActive));
                // 存入会话的元数据中，确保持久化
                session.getMetadata().put("_last_summary_text", summary);
                session.getMetadata().put("_last_summary_time", lastActive.toString());
            }

            // 构建新的消息列表：
            // 1. 获取之前已经合并过的前缀消息（0 到 lastConsolidated）
            List<Map<String, Object>> prefix =
                    new ArrayList<>(session.getMessages().subList(0, session.getLastConsolidated()));
            // 2. 将前缀与本次保留的后缀消息合并
            List<Map<String, Object>> merged = new ArrayList<>(prefix);
            merged.addAll(keptMsgs);

            // 更新会话对象的状态
            session.setMessages(merged); // 设置新的消息列表
            session.setLastConsolidated(prefix.size()); // 更新最后合并点索引
            session.setUpdatedAt(Instant.now()); // 更新最后修改时间

            // 保存更新后的会话到存储
            sessions.save(session);
        } finally {
            // 无论成功与否，最后在 finally 块中从归档集合中移除该 key
            // 这确保了即使发生异常，该会话也不会被永久锁定，允许后续再次尝试归档
            archiving.remove(key);
        }
    }

    /**
     * 准备会话以供恢复使用。
     * <p>
     * 如果会话有之前的摘要，则将其格式化并返回，以便注入到新的对话上下文中。
     *
     * @param session 会话对象
     * @param key     会话键
     * @return PreparedSession 包含会话对象和格式化后的摘要信息（如果有）
     */
    public PreparedSession prepareSession(Session session, String key) {
        // 从会话元数据中获取上次保存的摘要文本
        Object txt = session.getMetadata().get("_last_summary_text");
        // 从会话元数据中获取上次保存的摘要时间
        Object ts = session.getMetadata().get("_last_summary_time");

        // 如果元数据中存在有效的摘要文本
        if (txt instanceof String summaryText) {
            // 安全解析时间戳
            Instant lastActive = parseInstantSafely(ts);
            // 如果时间戳也有效
            if (lastActive != null) {
                // 更新内存缓存
                summaries.put(key, new SummaryRecord(summaryText, lastActive));
                // 返回包含格式化摘要的 PreparedSession
                return new PreparedSession(session, formatSummary(summaryText, lastActive));
            }
        }

        // 如果元数据中没有有效摘要，尝试从内存缓存中获取
        SummaryRecord cached = summaries.get(key);
        if (cached != null) {
            // 如果缓存中有，返回格式化后的摘要
            return new PreparedSession(session, formatSummary(cached.text(), cached.lastActive()));
        }

        // 如果都没有找到摘要，返回不带摘要的会话
        return new PreparedSession(session, null);
    }

    /**
     * 安全地将对象解析为 Instant 时间戳。
     *
     * @param raw 原始对象，预期为 ISO-8601 格式的字符串
     * @return 解析后的 Instant，如果解析失败或输入无效则返回 null
     */
    private Instant parseInstantSafely(Object raw) {
        // 检查对象是否为非空字符串
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            // 尝试解析 ISO-8601 格式的时间字符串
            return Instant.parse(s);
        } catch (Exception e) {
            // 解析失败返回 null
            return null;
        }
    }

    /**
     * 记录摘要信息的内部记录类。
     *
     * @param text       摘要文本
     * @param lastActive 最后活动时间
     */
    public record SummaryRecord(String text, Instant lastActive) {}

    /**
     * 准备会话的内部记录类，用于返回给调用者。
     *
     * @param session 会话对象
     * @param summary 待注入的格式化摘要，若无则为 null
     */
    public record PreparedSession(Session session, String summary) {}

    /**
     * 消息分割结果的内部记录类。
     *
     * @param archiveable 可归档的消息列表（旧消息）
     * @param kept        需保留的消息列表（最近消息）
     */
    public record SplitResult(
            List<Map<String, Object>> archiveable,
            List<Map<String, Object>> kept
    ) {}
}
