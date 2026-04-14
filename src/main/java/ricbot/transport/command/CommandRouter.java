package ricbot.transport.command;

import ricbot.core.message.InboundMessage;
import ricbot.core.message.OutboundMessage;
import ricbot.core.session.Session;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 对应 Python: router.py
 *
 * 主要目标：
 * 1. 提供最小命令路由表
 * 2. 支持四层命令分发：
 *    - priority
 *    - exact
 *    - prefix
 *    - interceptors
 *
 * 分发顺序：
 * 1. priority：最高优先级，通常在主调度锁外执行（如 /stop /restart）
 * 2. exact：精确匹配
 * 3. prefix：前缀匹配，按最长前缀优先
 * 4. interceptors：兜底拦截器
 */
public class CommandRouter {

    /**
     * 命令处理器函数接口。
     *
     * 对应 Python:
     * Handler = Callable[[CommandContext], Awaitable[OutboundMessage | None]]
     */
    @FunctionalInterface
    public interface Handler {
        CompletableFuture<OutboundMessage> handle(CommandContext ctx);
    }

    /**
     * 命令上下文。
     *
     * 对应 Python 的 CommandContext。
     */
    public static class CommandContext {
        private InboundMessage msg;
        private Session session;
        private String key;
        private String raw;
        private String args = "";
        private Object loop;

        public CommandContext() {
        }

        public CommandContext(InboundMessage msg, Session session, String key, String raw, Object loop) {
            this.msg = msg;
            this.session = session;
            this.key = key;
            this.raw = raw;
            this.loop = loop;
        }

        public InboundMessage getMsg() {
            return msg;
        }

        public void setMsg(InboundMessage msg) {
            this.msg = msg;
        }

        public Session getSession() {
            return session;
        }

        public void setSession(Session session) {
            this.session = session;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getRaw() {
            return raw;
        }

        public void setRaw(String raw) {
            this.raw = raw;
        }

        public String getArgs() {
            return args;
        }

        public void setArgs(String args) {
            this.args = args;
        }

        public Object getLoop() {
            return loop;
        }

        public void setLoop(Object loop) {
            this.loop = loop;
        }
    }

    /**
     * 最高优先级命令。
     */
    private final Map<String, Handler> priorityHandlers = new HashMap<>();

    /**
     * 精确匹配命令。
     */
    private final Map<String, Handler> exactHandlers = new HashMap<>();

    /**
     * 前缀匹配命令，按前缀长度从大到小排序。
     */
    private final List<PrefixHandler> prefixHandlers = new ArrayList<>();

    /**
     * 拦截器。
     */
    private final List<Handler> interceptors = new ArrayList<>();

    /**
     * 注册 priority 命令。
     */
    public void priority(String cmd, Handler handler) {
        priorityHandlers.put(normalize(cmd), handler);
    }

    /**
     * 注册 exact 命令。
     */
    public void exact(String cmd, Handler handler) {
        exactHandlers.put(normalize(cmd), handler);
    }

    /**
     * 注册 prefix 命令。
     */
    public void prefix(String prefix, Handler handler) {
        prefixHandlers.add(new PrefixHandler(prefix, handler));
        prefixHandlers.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
    }

    /**
     * 注册 interceptor。
     */
    public void intercept(Handler handler) {
        interceptors.add(handler);
    }

    /**
     * 是否是 priority 命令。
     */
    public boolean isPriority(String text) {
        return priorityHandlers.containsKey(normalize(text));
    }

    /**
     * 分发 priority 命令。
     *
     * 对应 Python dispatch_priority()
     */
    public CompletableFuture<OutboundMessage> dispatchPriority(CommandContext ctx) {
        Handler handler = priorityHandlers.get(normalize(ctx.getRaw()));
        if (handler != null) {
            return handler.handle(ctx);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 常规分发：
     * exact -> prefix -> interceptors
     *
     * 对应 Python dispatch()
     */
    public CompletableFuture<OutboundMessage> dispatch(CommandContext ctx) {
        String cmd = normalize(ctx.getRaw());

        Handler exact = exactHandlers.get(cmd);
        if (exact != null) {
            return exact.handle(ctx);
        }

        for (PrefixHandler item : prefixHandlers) {
            if (cmd.startsWith(item.prefix)) {
                ctx.setArgs(ctx.getRaw().substring(item.prefix.length()));
                return item.handler.handle(ctx);
            }
        }

        return dispatchInterceptors(ctx, 0);
    }

    /**
     * 顺序执行拦截器。
     * 只要有一个返回非 null，就停止。
     */
    private CompletableFuture<OutboundMessage> dispatchInterceptors(CommandContext ctx, int index) {
        if (index >= interceptors.size()) {
            return CompletableFuture.completedFuture(null);
        }

        Handler interceptor = interceptors.get(index);
        return interceptor.handle(ctx).thenCompose(result -> {
            if (result != null) {
                return CompletableFuture.completedFuture(result);
            }
            return dispatchInterceptors(ctx, index + 1);
        });
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * prefix 处理器包装类。
     */
    private static class PrefixHandler {
        private final String prefix;
        private final Handler handler;

        private PrefixHandler(String prefix, Handler handler) {
            this.prefix = normalize(prefix);
            this.handler = handler;
        }
    }
}