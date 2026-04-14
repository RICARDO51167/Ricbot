package ricbot.transport.command;

import ricbot.core.message.InboundMessage;
import ricbot.core.message.OutboundMessage;
import ricbot.core.session.Session;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 对应 Python: builtin.py
 *
 * 主要目标：
 * 1. 提供内置 slash command 处理器
 * 2. 负责注册这些命令到 CommandRouter
 *
 * 内置命令包括：
 * - /stop
 * - /restart
 * - /status
 * - /new
 * - /dream
 * - /dream-log
 * - /dream-restore
 * - /help
 */
public final class BuiltinCommands {

    private BuiltinCommands() {
    }

    // =========================================================
    // Public registration entry
    // =========================================================

    /**
     * 注册所有内置命令。
     *
     * 对应 Python register_builtin_commands(router)
     */
    public static void registerBuiltinCommands(CommandRouter router) {
        router.priority("/stop", BuiltinCommands::cmdStop);
        router.priority("/restart", BuiltinCommands::cmdRestart);
        router.priority("/status", BuiltinCommands::cmdStatus);

        router.exact("/new", BuiltinCommands::cmdNew);
        router.exact("/status", BuiltinCommands::cmdStatus);
        router.exact("/dream", BuiltinCommands::cmdDream);

        router.exact("/dream-log", BuiltinCommands::cmdDreamLog);
        router.prefix("/dream-log ", BuiltinCommands::cmdDreamLog);

        router.exact("/dream-restore", BuiltinCommands::cmdDreamRestore);
        router.prefix("/dream-restore ", BuiltinCommands::cmdDreamRestore);

        router.exact("/help", BuiltinCommands::cmdHelp);
    }

    // =========================================================
    // Core commands
    // =========================================================

    /**
     * 对应 Python cmd_stop()
     *
     * 功能：
     * 1. 取消当前 session 的活动任务
     * 2. 取消当前 session 的 subagent
     * 3. 返回停止数量
     */
    public static CompletableFuture<OutboundMessage> cmdStop(CommandRouter.CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            Object loop = ctx.getLoop();
            InboundMessage msg = ctx.getMsg();

            int cancelled = 0;
            int subCancelled = 0;

            try {
                // loop._active_tasks.pop(msg.session_key, [])
                Method getActiveTasksMethod = loop.getClass().getMethod("getActiveTasks");
                @SuppressWarnings("unchecked")
                Map<String, List<Thread>> activeTasks =
                        (Map<String, List<Thread>>) getActiveTasksMethod.invoke(loop);

                List<Thread> tasks = activeTasks.remove(msg.getSessionKey());
                if (tasks != null) {
                    for (Thread t : tasks) {
                        if (t != null && t.isAlive()) {
                            t.interrupt();
                            cancelled++;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Method getSubagentsMethod = loop.getClass().getMethod("getSubagents");
                Object subagents = getSubagentsMethod.invoke(loop);
                if (subagents != null) {
                    Method cancelBySession = subagents.getClass().getMethod("cancelBySession", String.class);
                    Object result = cancelBySession.invoke(subagents, msg.getSessionKey());
                    if (result instanceof Number n) {
                        subCancelled = n.intValue();
                    }
                }
            } catch (Exception ignored) {
            }

            int total = cancelled + subCancelled;
            String content = total > 0 ? "Stopped " + total + " task(s)." : "No active task to stop.";

            OutboundMessage out = new OutboundMessage();
            out.setChannel(msg.getChannel());
            out.setChatId(msg.getChatId());
            out.setContent(content);
            out.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
            return out;
        });
    }

    /**
     * 对应 Python cmd_restart()
     *
     * Java 里通常不会像 Python 那样直接 os.execv 自重启，
     * 所以这里先做“发出重启中提示 + 预留重启钩子”。
     */
    public static CompletableFuture<OutboundMessage> cmdRestart(CommandRouter.CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            InboundMessage msg = ctx.getMsg();

            // TODO:
            // 这里后续你可以对接真正的 Java 进程重启逻辑
            // 例如 ProcessBuilder 重启当前 jar / main class

            OutboundMessage out = new OutboundMessage();
            out.setChannel(msg.getChannel());
            out.setChatId(msg.getChatId());
            out.setContent("Restarting...");
            out.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
            return out;
        });
    }

    /**
     * 对应 Python cmd_status()
     *
     * 构建当前 bot 状态输出。
     */
    public static CompletableFuture<OutboundMessage> cmdStatus(CommandRouter.CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            Object loop = ctx.getLoop();
            Session session = ctx.getSession();

            if (session == null) {
                session = tryGetSession(loop, ctx.getKey());
            }

            int ctxEstimate = 0;
            try {
                Method getLastUsageMethod = loop.getClass().getMethod("getLastUsage");
                @SuppressWarnings("unchecked")
                Map<String, Integer> usage = (Map<String, Integer>) getLastUsageMethod.invoke(loop);
                ctxEstimate = usage != null ? usage.getOrDefault("prompt_tokens", 0) : 0;
            } catch (Exception ignored) {
            }

            String version = "0.1.5";
            String model = tryGetString(loop, "getModel", "unknown");
            String startTime = tryFormatStartTime(loop);

            int sessionMsgCount = 0;
            if (session != null) {
                try {
                    sessionMsgCount = session.getHistory(0).size();
                } catch (Exception ignored) {
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("nanobot status\n");
            sb.append("version: ").append(version).append("\n");
            sb.append("model: ").append(model).append("\n");
            sb.append("started: ").append(startTime).append("\n");
            sb.append("session messages: ").append(sessionMsgCount).append("\n");
            sb.append("context tokens estimate: ").append(ctxEstimate).append("\n");

            OutboundMessage out = new OutboundMessage();
            out.setChannel(ctx.getMsg().getChannel());
            out.setChatId(ctx.getMsg().getChatId());
            out.setContent(sb.toString());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("render_as", "text");
            if (ctx.getMsg().getMetadata() != null) {
                metadata.putAll(ctx.getMsg().getMetadata());
            }
            out.setMetadata(metadata);
            return out;
        });
    }

    /**
     * 对应 Python cmd_new()
     *
     * 功能：
     * 1. 清空当前 session
     * 2. 持久化
     * 3. 返回新会话提示
     */
    public static CompletableFuture<OutboundMessage> cmdNew(CommandRouter.CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            Object loop = ctx.getLoop();
            Session session = ctx.getSession();

            if (session == null) {
                session = tryGetSession(loop, ctx.getKey());
            }

            if (session != null) {
                try {
                    List<?> snapshot = session.getMessagesFromLastConsolidated();
                    session.clear();

                    Object sessionsManager = invokeNoArg(loop, "getSessions");
                    if (sessionsManager != null) {
                        invokeOneArg(sessionsManager, "save", session);
                        invokeOneArg(sessionsManager, "invalidate", session.getKey());
                    }

                    // TODO:
                    // 如果你后面把 consolidator.archive(snapshot) 补上，这里可以继续接
                    if (snapshot != null && !snapshot.isEmpty()) {
                        // reserve background archive hook
                    }
                } catch (Exception ignored) {
                }
            }

            OutboundMessage out = new OutboundMessage();
            out.setChannel(ctx.getMsg().getChannel());
            out.setChatId(ctx.getMsg().getChatId());
            out.setContent("New session started.");
            out.setMetadata(ctx.getMsg().getMetadata() != null ? new HashMap<>(ctx.getMsg().getMetadata()) : new HashMap<>());
            return out;
        });
    }

    /**
     * 对应 Python cmd_dream()
     *
     * 手动触发一次 Dream consolidation。
     */
    public static CompletableFuture<OutboundMessage> cmdDream(CommandRouter.CommandContext ctx) {
        Object loop = ctx.getLoop();
        InboundMessage msg = ctx.getMsg();

        // 异步后台执行
        CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            String content;

            try {
                Object dream = invokeNoArg(loop, "getDream");
                if (dream == null) {
                    content = "Dream is not available.";
                } else {
                    Object result = invokeNoArg(dream, "run");
                    boolean didWork = result instanceof Boolean b && b;
                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    content = didWork
                            ? String.format(Locale.US, "Dream completed in %.1fs.", seconds)
                            : "Dream: nothing to process.";
                }
            } catch (Exception e) {
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                content = String.format(Locale.US, "Dream failed after %.1fs: %s", seconds, e.getMessage());
            }

            tryPublishOutbound(loop, msg.getChannel(), msg.getChatId(), content);
        });

        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent("Dreaming...");
        return CompletableFuture.completedFuture(out);
    }

    /**
     * 对应 Python cmd_dream_log()
     *
     * 展示最近一次 Dream 变更，或者指定 sha 的 diff。
     */
    public static CompletableFuture<OutboundMessage> cmdDreamLog(CommandRouter.CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String content;

            try {
                Object loop = ctx.getLoop();
                Object consolidator = invokeNoArg(loop, "getConsolidator");
                Object store = invokeNoArg(consolidator, "getStore");
                Object git = invokeNoArg(store, "getGit");

                Boolean initialized = (Boolean) invokeNoArg(git, "isInitialized");
                if (!Boolean.TRUE.equals(initialized)) {
                    content = "Dream history is not available because memory versioning is not initialized.";
                } else {
                    String args = ctx.getArgs() != null ? ctx.getArgs().trim() : "";

                    if (!args.isBlank()) {
                        String sha = args.split("\\s+")[0];
                        Object result = invokeOneArg(git, "showCommitDiff", sha);
                        if (result == null) {
                            content = "Couldn't find Dream change `" + sha + "`.\n\n"
                                    + "Use `/dream-restore` to list recent versions, or `/dream-log` to inspect the latest one.";
                        } else {
                            content = formatDreamLogContent(result, sha);
                        }
                    } else {
                        Object commitsObj = invokeOneArg(git, "log", 1);
                        List<?> commits = commitsObj instanceof List<?> list ? list : Collections.emptyList();

                        if (commits.isEmpty()) {
                            content = "Dream memory has no saved versions yet.";
                        } else {
                            Object first = commits.get(0);
                            String sha = String.valueOf(readFieldOrGetter(first, "sha"));
                            Object result = invokeOneArg(git, "showCommitDiff", sha);
                            content = result != null
                                    ? formatDreamLogContent(result, null)
                                    : "Dream memory has no saved versions yet.";
                        }
                    }
                }
            } catch (Exception e) {
                content = "Failed to read Dream log: " + e.getMessage();
            }

            OutboundMessage out = new OutboundMessage();
            out.setChannel(ctx.getMsg().getChannel());
            out.setChatId(ctx.getMsg().getChatId());
            out.setContent(content);
            out.setMetadata(Map.of("render_as", "text"));
            return out;
        });
    }

    /**
     * 对应 Python cmd_dream_restore()
     *
     * 用法：
     * - /dream-restore         -> 列出最近版本
     * - /dream-restore <sha>   -> 恢复到某个版本
     */
    public static CompletableFuture<OutboundMessage> cmdDreamRestore(CommandRouter.CommandContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String content;

            try {
                Object loop = ctx.getLoop();
                Object consolidator = invokeNoArg(loop, "getConsolidator");
                Object store = invokeNoArg(consolidator, "getStore");
                Object git = invokeNoArg(store, "getGit");

                Boolean initialized = (Boolean) invokeNoArg(git, "isInitialized");
                if (!Boolean.TRUE.equals(initialized)) {
                    content = "Dream history is not available because memory versioning is not initialized.";
                } else {
                    String args = ctx.getArgs() != null ? ctx.getArgs().trim() : "";
                    if (args.isBlank()) {
                        Object commitsObj = invokeOneArg(git, "log", 10);
                        List<?> commits = commitsObj instanceof List<?> list ? list : Collections.emptyList();

                        if (commits.isEmpty()) {
                            content = "Dream memory has no saved versions to restore yet.";
                        } else {
                            content = formatDreamRestoreList(commits);
                        }
                    } else {
                        String sha = args.split("\\s+")[0];

                        Object showResult = invokeOneArg(git, "showCommitDiff", sha);
                        String changedFiles = showResult != null ? formatChangedFilesFromShowResult(showResult) : "the tracked memory files";

                        Object newShaObj = invokeOneArg(git, "revert", sha);
                        if (newShaObj != null) {
                            String newSha = String.valueOf(newShaObj);
                            content = "Restored Dream memory to the state before `" + sha + "`.\n\n"
                                    + "- New safety commit: `" + newSha + "`\n"
                                    + "- Restored files: " + changedFiles + "\n\n"
                                    + "Use `/dream-log " + newSha + "` to inspect the restore diff.";
                        } else {
                            content = "Couldn't restore Dream change `" + sha + "`.\n\n"
                                    + "It may not exist, or it may be the first saved version with no earlier state to restore.";
                        }
                    }
                }
            } catch (Exception e) {
                content = "Dream restore failed: " + e.getMessage();
            }

            OutboundMessage out = new OutboundMessage();
            out.setChannel(ctx.getMsg().getChannel());
            out.setChatId(ctx.getMsg().getChatId());
            out.setContent(content);
            out.setMetadata(Map.of("render_as", "text"));
            return out;
        });
    }

    /**
     * 对应 Python cmd_help()
     */
    public static CompletableFuture<OutboundMessage> cmdHelp(CommandRouter.CommandContext ctx) {
        return CompletableFuture.completedFuture(buildTextResponse(
                ctx.getMsg(),
                buildHelpText(),
                Map.of("render_as", "text")
        ));
    }

    // =========================================================
    // Help text
    // =========================================================

    /**
     * 对应 Python build_help_text()
     */
    public static String buildHelpText() {
        return String.join("\n",
                "🐈 nanobot commands:",
                "/new — Start a new conversation",
                "/stop — Stop the current task",
                "/restart — Restart the bot",
                "/status — Show bot status",
                "/dream — Manually trigger Dream consolidation",
                "/dream-log — Show what the last Dream changed",
                "/dream-restore — Revert memory to a previous state",
                "/help — Show available commands"
        );
    }

    // =========================================================
    // Dream diff helpers
    // =========================================================

    /**
     * 对应 Python _extract_changed_files(diff)
     */
    public static List<String> extractChangedFiles(String diff) {
        List<String> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (diff == null || diff.isBlank()) {
            return files;
        }

        String[] lines = diff.split("\\R");
        for (String line : lines) {
            if (!line.startsWith("diff --git ")) {
                continue;
            }

            String[] parts = line.split("\\s+");
            if (parts.length < 4) {
                continue;
            }

            String path = parts[3];
            if (path.startsWith("b/")) {
                path = path.substring(2);
            }

            if (seen.add(path)) {
                files.add(path);
            }
        }

        return files;
    }

    /**
     * 对应 Python _format_changed_files(diff)
     */
    public static String formatChangedFiles(String diff) {
        List<String> files = extractChangedFiles(diff);
        if (files.isEmpty()) {
            return "No tracked memory files changed.";
        }

        List<String> quoted = new ArrayList<>();
        for (String f : files) {
            quoted.add("`" + f + "`");
        }
        return String.join(", ", quoted);
    }

    private static String formatDreamLogContent(Object showCommitDiffResult, String requestedSha) {
        Object commit = null;
        String diff = "";

        if (showCommitDiffResult instanceof List<?> list && list.size() >= 2) {
            commit = list.get(0);
            diff = String.valueOf(list.get(1));
        } else if (showCommitDiffResult != null && showCommitDiffResult.getClass().isArray()) {
            Object[] arr = (Object[]) showCommitDiffResult;
            if (arr.length >= 2) {
                commit = arr[0];
                diff = String.valueOf(arr[1]);
            }
        } else {
            return "Dream recorded this version, but there is no file diff to display.";
        }

        String sha = String.valueOf(readFieldOrGetter(commit, "sha"));
        String timestamp = String.valueOf(readFieldOrGetter(commit, "timestamp"));
        String filesLine = formatChangedFiles(diff);

        List<String> lines = new ArrayList<>();
        lines.add("## Dream Update");
        lines.add("");
        lines.add(requestedSha != null
                ? "Here is the selected Dream memory change."
                : "Here is the latest Dream memory change.");
        lines.add("");
        lines.add("- Commit: `" + sha + "`");
        lines.add("- Time: " + timestamp);
        lines.add("- Changed files: " + filesLine);

        if (diff != null && !diff.isBlank()) {
            lines.add("");
            lines.add("Use `/dream-restore " + sha + "` to undo this change.");
            lines.add("");
            lines.add("```diff");
            lines.add(diff.stripTrailing());
            lines.add("```");
        } else {
            lines.add("");
            lines.add("Dream recorded this version, but there is no file diff to display.");
        }

        return String.join("\n", lines);
    }

    private static String formatDreamRestoreList(List<?> commits) {
        List<String> lines = new ArrayList<>();
        lines.add("## Dream Restore");
        lines.add("");
        lines.add("Choose a Dream memory version to restore. Latest first:");
        lines.add("");

        for (Object c : commits) {
            String sha = String.valueOf(readFieldOrGetter(c, "sha"));
            String timestamp = String.valueOf(readFieldOrGetter(c, "timestamp"));
            String message = String.valueOf(readFieldOrGetter(c, "message"));
            String firstLine = message.contains("\n") ? message.substring(0, message.indexOf('\n')) : message;
            lines.add("- `" + sha + "` " + timestamp + " - " + firstLine);
        }

        lines.add("");
        lines.add("Preview a version with `/dream-log <sha>` before restoring it.");
        lines.add("Restore a version with `/dream-restore <sha>`.");

        return String.join("\n", lines);
    }

    private static String formatChangedFilesFromShowResult(Object showCommitDiffResult) {
        String diff = "";

        if (showCommitDiffResult instanceof List<?> list && list.size() >= 2) {
            diff = String.valueOf(list.get(1));
        } else if (showCommitDiffResult != null && showCommitDiffResult.getClass().isArray()) {
            Object[] arr = (Object[]) showCommitDiffResult;
            if (arr.length >= 2) {
                diff = String.valueOf(arr[1]);
            }
        }

        return formatChangedFiles(diff);
    }

    // =========================================================
    // Reflection helpers
    // =========================================================

    private static Session tryGetSession(Object loop, String key) {
        try {
            Object sessions = invokeNoArg(loop, "getSessions");
            Object session = invokeOneArg(sessions, "getOrCreate", key);
            if (session instanceof Session s) {
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String tryGetString(Object target, String getterName, String fallback) {
        try {
            Object value = invokeNoArg(target, getterName);
            return value != null ? String.valueOf(value) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String tryFormatStartTime(Object loop) {
        try {
            Object value = invokeNoArg(loop, "getStartTime");
            if (value != null) {
                return String.valueOf(value);
            }
        } catch (Exception ignored) {
        }
        return Instant.now().toString();
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object invokeOneArg(Object target, String methodName, Object arg) {
        if (target == null) return null;
        Method[] methods = target.getClass().getMethods();
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != 1) continue;
            try {
                m.setAccessible(true);
                return m.invoke(target, arg);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Object readFieldOrGetter(Object target, String name) {
        if (target == null) return null;

        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Method method = target.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
        }

        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ignored) {
        }

        return null;
    }

    // =========================================================
    // Bus publishing helper
    // =========================================================

    private static void tryPublishOutbound(Object loop, String channel, String chatId, String content) {
        try {
            Object bus = invokeNoArg(loop, "getBus");
            if (bus == null) return;

            OutboundMessage out = new OutboundMessage();
            out.setChannel(channel);
            out.setChatId(chatId);
            out.setContent(content);

            Method publish = bus.getClass().getMethod("publishOutbound", OutboundMessage.class);
            publish.invoke(bus, out);
        } catch (Exception ignored) {
        }
    }

    // =========================================================
    // Response helper
    // =========================================================

    private static OutboundMessage buildTextResponse(InboundMessage msg, String content, Map<String, Object> extraMeta) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(content);

        Map<String, Object> metadata = new HashMap<>();
        if (msg.getMetadata() != null) {
            metadata.putAll(msg.getMetadata());
        }
        if (extraMeta != null) {
            metadata.putAll(extraMeta);
        }
        out.setMetadata(metadata);
        return out;
    }
}