package ricbot.integration.command;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 命令路由器，支持高优先级、精确匹配、前缀匹配及拦截器四层分发。
 */
public class CommandRouter {

    @FunctionalInterface
    public interface Handler {
        CompletableFuture<OutboundMessage> handle(CommandContext ctx);
    }

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

    private final Map<String, Handler> priorityHandlers = new HashMap<>();

    private final Map<String, Handler> exactHandlers = new HashMap<>();

    private final List<PrefixHandler> prefixHandlers = new ArrayList<>();

    private final List<Handler> interceptors = new ArrayList<>();

    public void priority(String cmd, Handler handler) {
        priorityHandlers.put(normalize(cmd), handler);
    }

    public void exact(String cmd, Handler handler) {
        exactHandlers.put(normalize(cmd), handler);
    }

    public void prefix(String prefix, Handler handler) {
        prefixHandlers.add(new PrefixHandler(prefix, handler));
        prefixHandlers.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
    }

    public void intercept(Handler handler) {
        interceptors.add(handler);
    }

    public boolean isPriority(String text) {
        return priorityHandlers.containsKey(normalize(text));
    }

    public CompletableFuture<OutboundMessage> dispatchPriority(CommandContext ctx) {
        Handler handler = priorityHandlers.get(normalize(ctx.getRaw()));
        if (handler != null) {
            return handler.handle(ctx);
        }
        return CompletableFuture.completedFuture(null);
    }

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

    private static class PrefixHandler {
        private final String prefix;
        private final Handler handler;

        private PrefixHandler(String prefix, Handler handler) {
            this.prefix = normalize(prefix);
            this.handler = handler;
        }
    }
}