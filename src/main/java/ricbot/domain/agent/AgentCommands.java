package ricbot.domain.agent;

import ricbot.domain.memory.Dream;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.integration.command.CommandRouter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class AgentCommands {

    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final Dream dream;
    private final Config.DreamConfig dreamConfig;
    private final String model;
    private final Path workspace;
    private final Function<InboundMessage, String> sessionKeyResolver;
    private final Function<String, List<Future<?>>> activeTaskRemover;
    private final BiConsumer<String, String> sessionInterruptMarker;

    AgentCommands(
            SessionManager sessionManager,
            MemoryStore memoryStore,
            Dream dream,
            Config.DreamConfig dreamConfig,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker
    ) {
        this.sessionManager = sessionManager;
        this.memoryStore = memoryStore;
        this.dream = dream;
        this.dreamConfig = dreamConfig;
        this.model = model;
        this.workspace = workspace;
        this.sessionKeyResolver = sessionKeyResolver;
        this.activeTaskRemover = activeTaskRemover;
        this.sessionInterruptMarker = sessionInterruptMarker;
    }

    void register(CommandRouter router) {
        router.priority("/stop", this::stop);
        router.priority("/restart", this::disabled);
        router.exact("/new", this::startNewSession);
        router.exact("/help", this::help);
        router.exact("/status", this::status);
        router.exact("/dream", this::dream);
        router.exact("/dream-log", this::dreamLog);
        router.prefix("/dream-log ", this::dreamLog);
        router.exact("/dream-restore", this::dreamRestore);
        router.prefix("/dream-restore ", this::dreamRestore);
    }

    private CompletableFuture<OutboundMessage> stop(CommandRouter.CommandContext ctx) {
        String sessionKey = sessionKeyResolver.apply(ctx.getMsg());
        List<Future<?>> tasks = activeTaskRemover.apply(sessionKey);

        int cancelled = 0;
        if (tasks != null) {
            for (Future<?> task : tasks) {
                if (task != null && !task.isDone() && task.cancel(true)) {
                    cancelled++;
                }
            }
        }

        if (cancelled > 0) {
            sessionInterruptMarker.accept(sessionKey, "manual_stop");
        }
        return completedReply(ctx, cancelled > 0 ? "⏹ 已停止 " + cancelled + " 个任务。" : "没有可停止的任务。");
    }

    private CompletableFuture<OutboundMessage> disabled(CommandRouter.CommandContext ctx) {
        return completedReply(ctx, "当前运行时未启用该命令。");
    }

    private CompletableFuture<OutboundMessage> startNewSession(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        session.clear();
        sessionManager.save(session);
        return completedReply(ctx, "已开始新的会话。");
    }

    private CompletableFuture<OutboundMessage> help(CommandRouter.CommandContext ctx) {
        return completedReply(ctx, "ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前任务\n/help — 查看可用命令");
    }

    private CompletableFuture<OutboundMessage> status(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        int sessionMsgCount = session != null ? session.getMessages().size() : 0;
        TaskState taskState = TaskState.fromSession(session);

        StringBuilder sb = new StringBuilder();
        sb.append("ricbot status\n");
        sb.append("model: ").append(model).append("\n");
        sb.append("workspace: ").append(workspace).append("\n");
        sb.append("session messages: ").append(sessionMsgCount).append("\n");
        sb.append("\n").append(taskState.renderStatus());
        return completedReply(ctx, sb.toString());
    }

    private CompletableFuture<OutboundMessage> dream(CommandRouter.CommandContext ctx) {
        if (!dreamEnabled()) {
            return completedReply(ctx, "Dream 未启用。");
        }
        return completedReply(ctx, dream.run()
                ? "Dream 已完成一次整合，记忆文件已更新。"
                : "Dream 本次没有检测到可更新内容。");
    }

    private CompletableFuture<OutboundMessage> dreamLog(CommandRouter.CommandContext ctx) {
        if (!dreamEnabled()) {
            return completedReply(ctx, "Dream 未启用。");
        }

        int maxEntries = 10;
        String args = trim(ctx.getArgs());
        if (!args.isBlank()) {
            maxEntries = Math.max(1, Math.min(50, parseInt(args, 10)));
        }

        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            return completedReply(ctx, "Dream 日志仓库尚未初始化。先执行一次 /dream 后再查看日志。");
        }

        var logs = git.log(maxEntries);
        if (logs.isEmpty()) {
            return completedReply(ctx, "暂无 Dream 历史记录。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("dream log (latest ").append(logs.size()).append(")\n");
        sb.append("cursor: ").append(memoryStore.getLastDreamCursor())
                .append("/").append(memoryStore.getLastCursor()).append("\n\n");
        for (var c : logs) {
            sb.append(c.sha()).append("  ").append(c.timestamp()).append("  ").append(c.message()).append("\n");
        }
        return completedReply(ctx, sb.toString().trim());
    }

    private CompletableFuture<OutboundMessage> dreamRestore(CommandRouter.CommandContext ctx) {
        if (!dreamEnabled()) {
            return completedReply(ctx, "Dream 未启用。");
        }

        String args = trim(ctx.getArgs());
        if (args.isBlank()) {
            return completedReply(ctx, "用法：/dream-restore <commit_sha>");
        }

        String sha = args.split("\\s+")[0];
        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            return completedReply(ctx, "Dream 日志仓库尚未初始化，无法 restore。请先执行 /dream。");
        }

        var found = git.findCommit(sha, 200);
        if (found == null) {
            return completedReply(ctx, "未找到对应提交：" + sha);
        }

        String reverted = git.revert(found.sha());
        if (reverted == null) {
            return completedReply(ctx, "restore 失败，请检查工作区状态后重试。");
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return completedReply(ctx, "Dream 已恢复到 " + found.sha() + "，新提交: " + reverted + " (" + now + ")");
    }

    private boolean dreamEnabled() {
        return dreamConfig != null && dreamConfig.isEnabled();
    }

    private CompletableFuture<OutboundMessage> completedReply(CommandRouter.CommandContext ctx, String content) {
        return CompletableFuture.completedFuture(OutboundMessages.of(
                ctx.getMsg().getChannel(),
                ctx.getMsg().getChatId(),
                content
        ));
    }

    private static int parseInt(String s, int def) {
        try {
            return s != null ? Integer.parseInt(s) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
