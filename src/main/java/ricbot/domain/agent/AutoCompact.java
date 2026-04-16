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
 */
public class AutoCompact {

    private static final Logger log = LoggerFactory.getLogger(AutoCompact.class);

    private static final int DEFAULT_RECENT_SUFFIX_MESSAGES = 8;

    private final SessionManager sessions;
    private final Consolidator consolidator;
    private final int ttlMinutes;
    private final int recentSuffixMessages;

    private final Set<String> archiving = Collections.synchronizedSet(new HashSet<>());

    private final Map<String, SummaryRecord> summaries = new ConcurrentHashMap<>();

    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        this(sessions, consolidator, sessionTtlMinutes, DEFAULT_RECENT_SUFFIX_MESSAGES);
    }

    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes, int recentSuffixMessages) {
        this.sessions = sessions;
        this.consolidator = consolidator;
        this.ttlMinutes = sessionTtlMinutes;
        this.recentSuffixMessages = recentSuffixMessages > 0 ? recentSuffixMessages : DEFAULT_RECENT_SUFFIX_MESSAGES;
    }

    public boolean isExpired(Instant ts, Instant now) {
        if (ttlMinutes <= 0 || ts == null) {
            return false;
        }
        Instant ref = now != null ? now : Instant.now();
        return Duration.between(ts, ref).toMinutes() >= ttlMinutes;
    }

    public String formatSummary(String text, Instant lastActive) {
        long idleMin = Math.max(0, Duration.between(lastActive, Instant.now()).toMinutes());
        return "已闲置 " + idleMin + " 分钟。\n之前的对话摘要：" + text;
    }

    public SplitResult splitUnconsolidated(Session session) {
        List<Map<String, Object>> tail =
                new ArrayList<>(session.getMessages().subList(
                        Math.min(session.getLastConsolidated(), session.getMessages().size()),
                        session.getMessages().size()
                ));

        if (tail.isEmpty()) {
            return new SplitResult(List.of(), List.of());
        }

        Session probe = new Session(
                session.getKey(),
                new ArrayList<>(tail),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                new HashMap<>(),
                0
        );
        probe.retainRecentLegalSuffix(recentSuffixMessages);

        List<Map<String, Object>> kept = probe.getMessages();
        int cut = tail.size() - kept.size();

        return new SplitResult(
                new ArrayList<>(tail.subList(0, Math.max(0, cut))),
                kept
        );
    }

    public void checkExpired(
            Executor executor,
            Collection<String> activeSessionKeys
    ) {
        Instant now = Instant.now();

        for (Map<String, Object> info : sessions.listSessions()) {
            String key = String.valueOf(info.getOrDefault("key", ""));
            if (key.isBlank()) {
                continue;
            }
            if (archiving.contains(key)) {
                continue;
            }
            if (activeSessionKeys != null && activeSessionKeys.contains(key)) {
                continue;
            }

            Instant updatedAt = parseInstantSafely(info.get("updated_at"));

            if (isExpired(updatedAt, now)) {
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

    public void archive(String key) {
        try {
            sessions.invalidate(key);
            Session session = sessions.getOrCreate(key);

            SplitResult split = splitUnconsolidated(session);
            List<Map<String, Object>> archiveMsgs = split.archiveable();
            List<Map<String, Object>> keptMsgs = split.kept();

            if (archiveMsgs.isEmpty() && keptMsgs.isEmpty()) {
                session.setUpdatedAt(Instant.now());
                sessions.save(session);
                return;
            }

            Instant lastActive = session.getUpdatedAt();
            String summary = "";

            if (!archiveMsgs.isEmpty()) {
                try {
                    summary = consolidator.archive(archiveMsgs);
                } catch (Exception e) {
                    summary = "";
                    log.warn("归档摘要生成失败: sessionKey={}", key, e);
                }
            }

            if (summary != null && !summary.isBlank() && !"(无内容)".equals(summary)) {
                summaries.put(key, new SummaryRecord(summary, lastActive));
                session.getMetadata().put("_last_summary_text", summary);
                session.getMetadata().put("_last_summary_time", lastActive.toString());
            }

            List<Map<String, Object>> prefix =
                    new ArrayList<>(session.getMessages().subList(0, session.getLastConsolidated()));
            List<Map<String, Object>> merged = new ArrayList<>(prefix);
            merged.addAll(keptMsgs);

            session.setMessages(merged);
            session.setLastConsolidated(prefix.size());
            session.setUpdatedAt(Instant.now());

            sessions.save(session);
        } finally {
            archiving.remove(key);
        }
    }

    public PreparedSession prepareSession(Session session, String key) {
        Object txt = session.getMetadata().get("_last_summary_text");
        Object ts = session.getMetadata().get("_last_summary_time");

        if (txt instanceof String summaryText) {
            Instant lastActive = parseInstantSafely(ts);
            if (lastActive != null) {
                summaries.put(key, new SummaryRecord(summaryText, lastActive));
                return new PreparedSession(session, formatSummary(summaryText, lastActive));
            }
        }

        SummaryRecord cached = summaries.get(key);
        if (cached != null) {
            return new PreparedSession(session, formatSummary(cached.text(), cached.lastActive()));
        }

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

    public record SummaryRecord(String text, Instant lastActive) {}

    public record PreparedSession(Session session, String summary) {}

    public record SplitResult(
            List<Map<String, Object>> archiveable,
            List<Map<String, Object>> kept
    ) {}
}
