package ricbot.integration.command;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 对应 Python: router.py
 *
 * 主要目标：
 * 1. 提供最小命令路由表
 * 2. 支持四层命令分发：
 *    - priority (高优先级)
 *    - exact (精确匹配)
 *    - prefix (前缀匹配)
 *    - interceptors (拦截器)
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
        // 处理命令上下文，返回异步的出站消息结果
        CompletableFuture<OutboundMessage> handle(CommandContext ctx);
    }

    /**
     * 命令上下文。
     *
     * 对应 Python 的 CommandContext。
     */
    public static class CommandContext {
        private InboundMessage msg; // 入站消息对象
        private Session session; // 会话对象
        private String key; // 命令键
        private String raw; // 原始命令文本
        private String args = ""; // 命令参数部分
        private Object loop; // 事件循环对象引用

        // 默认构造函数
        public CommandContext() {
        }

        // 带参数的构造函数，初始化所有字段
        public CommandContext(InboundMessage msg, Session session, String key, String raw, Object loop) {
            this.msg = msg;
            this.session = session;
            this.key = key;
            this.raw = raw;
            this.loop = loop;
        }

        // 获取入站消息
        public InboundMessage getMsg() {
            return msg;
        }

        // 设置入站消息
        public void setMsg(InboundMessage msg) {
            this.msg = msg;
        }

        // 获取会话对象
        public Session getSession() {
            return session;
        }

        // 设置会话对象
        public void setSession(Session session) {
            this.session = session;
        }

        // 获取命令键
        public String getKey() {
            return key;
        }

        // 设置命令键
        public void setKey(String key) {
            this.key = key;
        }

        // 获取原始命令文本
        public String getRaw() {
            return raw;
        }

        // 设置原始命令文本
        public void setRaw(String raw) {
            this.raw = raw;
        }

        // 获取命令参数
        public String getArgs() {
            return args;
        }

        // 设置命令参数
        public void setArgs(String args) {
            this.args = args;
        }

        // 获取事件循环对象
        public Object getLoop() {
            return loop;
        }

        // 设置事件循环对象
        public void setLoop(Object loop) {
            this.loop = loop;
        }
    }

    /**
     * 高优先级命令处理器映射。
     * Key: 标准化后的命令字符串, Value: 对应的处理器
     */
    private final Map<String, Handler> priorityHandlers = new HashMap<>();

    /**
     * 精确匹配命令处理器映射。
     * Key: 标准化后的命令字符串, Value: 对应的处理器
     */
    private final Map<String, Handler> exactHandlers = new HashMap<>();

    /**
     * 前缀匹配命令处理器列表，按前缀长度从大到小排序。
     * 用于实现最长前缀优先匹配策略
     */
    private final List<PrefixHandler> prefixHandlers = new ArrayList<>();

    /**
     * 拦截器列表。
     * 当没有匹配到任何命令时，依次执行这些拦截器
     */
    private final List<Handler> interceptors = new ArrayList<>();

    /**
     * 注册高优先级 (priority) 命令。
     *
     * @param cmd 命令字符串
     * @param handler 命令处理器
     */
    public void priority(String cmd, Handler handler) {
        // 将标准化后的命令与处理器存入映射表
        priorityHandlers.put(normalize(cmd), handler);
    }

    /**
     * 注册精确匹配 (exact) 命令。
     *
     * @param cmd 命令字符串
     * @param handler 命令处理器
     */
    public void exact(String cmd, Handler handler) {
        // 将标准化后的命令与处理器存入映射表
        exactHandlers.put(normalize(cmd), handler);
    }

    /**
     * 注册前缀匹配 (prefix) 命令。
     *
     * @param prefix 前缀字符串
     * @param handler 命令处理器
     */
    public void prefix(String prefix, Handler handler) {
        // 创建前缀处理器并添加到列表
        prefixHandlers.add(new PrefixHandler(prefix, handler));
        // 按前缀长度降序排列，确保最长前缀优先匹配
        prefixHandlers.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
    }

    /**
     * 注册拦截器 (interceptor)。
     *
     * @param handler 拦截器处理器
     */
    public void intercept(Handler handler) {
        // 将拦截器添加到列表末尾
        interceptors.add(handler);
    }

    /**
     * 检查是否为高优先级命令。
     *
     * @param text 待检查的命令文本
     * @return 如果是高优先级命令返回 true，否则返回 false
     */
    public boolean isPriority(String text) {
        // 判断标准化后的文本是否存在于高优先级映射表中
        return priorityHandlers.containsKey(normalize(text));
    }

    /**
     * 分发高优先级命令。
     *
     * 对应 Python dispatch_priority()
     *
     * @param ctx 命令上下文
     * @return 异步处理的出站消息，如果没有匹配则返回 null
     */
    public CompletableFuture<OutboundMessage> dispatchPriority(CommandContext ctx) {
        // 根据标准化后的原始命令文本查找处理器
        Handler handler = priorityHandlers.get(normalize(ctx.getRaw()));
        if (handler != null) {
            // 如果找到处理器，则执行处理逻辑
            return handler.handle(ctx);
        }
        // 如果没有找到处理器，返回一个已完成且结果为 null 的 Future
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 常规命令分发：
     * 精确匹配 (exact) -> 前缀匹配 (prefix) -> 拦截器 (interceptors)
     *
     * 对应 Python dispatch()
     *
     * @param ctx 命令上下文
     * @return 异步处理的出站消息
     */
    public CompletableFuture<OutboundMessage> dispatch(CommandContext ctx) {
        // 获取标准化后的命令文本
        String cmd = normalize(ctx.getRaw());

        // 1. 尝试精确匹配
        Handler exact = exactHandlers.get(cmd);
        if (exact != null) {
            // 如果找到精确匹配的处理器，直接执行并返回结果
            return exact.handle(ctx);
        }

        // 2. 尝试前缀匹配
        for (PrefixHandler item : prefixHandlers) {
            // 检查命令是否以当前项的前缀开头
            if (cmd.startsWith(item.prefix)) {
                // 提取参数部分（原始文本减去前缀长度）并设置到上下文中
                ctx.setArgs(ctx.getRaw().substring(item.prefix.length()));
                // 执行对应的处理器并返回结果
                return item.handler.handle(ctx);
            }
        }

        // 3. 执行拦截器链
        // 如果没有匹配到任何命令，则从第一个拦截器开始执行
        return dispatchInterceptors(ctx, 0);
    }

    /**
     * 顺序执行拦截器。
     * 只要有一个拦截器返回非 null 结果，则停止后续拦截器的执行并返回该结果。
     *
     * @param ctx 命令上下文
     * @param index 当前执行的拦截器索引
     * @return 异步处理的出站消息
     */
    private CompletableFuture<OutboundMessage> dispatchInterceptors(CommandContext ctx, int index) {
        // 如果索引超出拦截器列表大小，说明所有拦截器都已执行完毕且未返回结果
        if (index >= interceptors.size()) {
            return CompletableFuture.completedFuture(null);
        }

        // 获取当前索引处的拦截器
        Handler interceptor = interceptors.get(index);
        // 执行当前拦截器，并在完成后检查结果
        return interceptor.handle(ctx).thenCompose(result -> {
            if (result != null) {
                // 如果结果不为 null，说明拦截器已处理该请求，直接返回结果
                return CompletableFuture.completedFuture(result);
            }
            // 如果结果为 null，继续递归执行下一个拦截器
            return dispatchInterceptors(ctx, index + 1);
        });
    }

    /**
     * 标准化命令文本：去除首尾空格并转换为小写。
     *
     * @param text 原始文本
     * @return 标准化后的文本
     */
    private static String normalize(String text) {
        // 如果文本为 null 则返回空字符串，否则去除空格并转小写
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 前缀处理器包装类。
     * 用于存储前缀字符串和对应的处理器
     */
    private static class PrefixHandler {
        private final String prefix; // 标准化的前缀字符串
        private final Handler handler; // 对应的命令处理器

        // 构造函数，初始化前缀和处理器
        private PrefixHandler(String prefix, Handler handler) {
            this.prefix = normalize(prefix); // 存储时即进行标准化
            this.handler = handler;
        }
    }
}