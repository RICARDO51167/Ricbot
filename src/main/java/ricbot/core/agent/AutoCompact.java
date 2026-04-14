package ricbot.core.agent;

import ricbot.core.memory.Consolidator;
import ricbot.core.session.Session;
import ricbot.core.session.SessionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * AutoCompact：自动压缩空闲会话。
 *
 * 对应 Python autocompact.py
 */
public class AutoCompact {

    private static final int RECENT_SUFFIX_MESSAGES = 8;

    private final SessionManager sessions;
    private final Consolidator consolidator;
    private final int ttlMinutes;

    /**
     * 正在归档的 session key，避免重复归档
     */
    private final Set<String> archiving = Collections.synchronizedSet(new HashSet<>());

    /**
     * 最近一次摘要缓存
     * key -> (summary, lastActive)
     */
    private final Map<String, SummaryRecord> summaries = new HashMap<>();

    public AutoCompact(SessionManager sessions, Consolidator consolidator, int sessionTtlMinutes) {
        this.sessions = sessions;
        this.consolidator = consolidator;
        this.ttlMinutes = sessionTtlMinutes;
    }

    public boolean isExpired(Instant ts, Instant now) {
        if (ttlMinutes <= 0 || ts == null) {
            return false;
        }
        Instant ref = now != null ? now : Instant.now();
        return Duration.between(ts, ref).toMinutes() >= ttlMinutes;
    }

    public String formatSummary(String text, Instant lastActive) {
        long idleMin = Duration.between(lastActive, Instant.now()).toMinutes();
        return "Inactive for " + idleMin + " minutes.\nPrevious conversation summary: " + text;
    }

    /**
     * 把未 consolidate 的 tail 分成：
     * 1. 可归档前缀
     * 2. 保留的最近合法后缀
     */
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
        probe.retainRecentLegalSuffix(RECENT_SUFFIX_MESSAGES);

        List<Map<String, Object>> kept = probe.getMessages();
        int cut = tail.size() - kept.size();

        return new SplitResult(
                new ArrayList<>(tail.subList(0, Math.max(0, cut))),
                kept
        );
    }

    /**
     * 检查过期 session，并把归档任务丢到后台执行器。
     */
    public void checkExpired(
            Consumer<CompletableFuture<Void>> scheduleBackground,
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

            Instant updatedAt = null;
            Object updatedObj = info.get("updated_at");
            if (updatedObj instanceof String s && !s.isBlank()) {
                updatedAt = Instant.parse(s);
            }

            if (isExpired(updatedAt, now)) {
                archiving.add(key);
                scheduleBackground.accept(
                        CompletableFuture.runAsync(() -> archive(key))
                );
            }
        }
    }

    /**
     * 真正执行归档。
     */
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
                }
            }

            if (summary != null && !summary.isBlank() && !"(nothing)".equals(summary)) {
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

    /**
     * 恢复 session 时，返回 session 和待注入摘要。
     */
    public PreparedSession prepareSession(Session session, String key) {
        Object txt = session.getMetadata().get("_last_summary_text");
        Object ts = session.getMetadata().get("_last_summary_time");

        if (txt instanceof String summaryText && ts instanceof String timeText) {
            Instant lastActive = Instant.parse(timeText);
            return new PreparedSession(session, formatSummary(summaryText, lastActive));
        }

        SummaryRecord cached = summaries.get(key);
        if (cached != null) {
            return new PreparedSession(session, formatSummary(cached.text(), cached.lastActive()));
        }

        return new PreparedSession(session, null);
    }

    public record SummaryRecord(String text, Instant lastActive) {}

    public record PreparedSession(Session session, String summary) {}

    public record SplitResult(
            List<Map<String, Object>> archiveable,
            List<Map<String, Object>> kept
    ) {}
}