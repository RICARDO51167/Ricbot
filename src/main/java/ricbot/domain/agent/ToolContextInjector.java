package ricbot.domain.agent;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具上下文注入器，负责将聊天上下文（channel, chatId, messageId）注入到工具实例中。
 */
final class ToolContextInjector implements ToolContextApplier {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(ToolContextInjector.class);

    // 空操作上下文设置器，用于当工具类没有定义 setContext 方法时
    private static final ContextSetter NOOP = (tool, channel, chatId, messageId) -> {
    };

    // 工具注册表，用于获取所有已注册的工具
    private final ToolRegistry tools;

    // 缓存每个工具类对应的上下文设置器，避免重复反射查找
    private final Map<Class<?>, ContextSetter> setters = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param tools 工具注册表
     */
    ToolContextInjector(ToolRegistry tools) {
        this.tools = tools;
    }

    /**
     * 应用上下文到所有工具
     *
     * @param channel   渠道标识
     * @param chatId    聊天ID
     * @param messageId 消息ID
     */
    @Override
    public void apply(String channel, String chatId, String messageId) {
        // 遍历所有已注册的工具名称
        for (String toolName : tools.toolNames()) {
            // 获取工具实例
            Tool tool = tools.get(toolName);
            // 如果工具实例为空，跳过
            if (tool == null) {
                continue;
            }
            try {
                // 获取或创建该工具类的上下文设置器，并应用上下文
                setters.computeIfAbsent(tool.getClass(), ToolContextInjector::resolveSetter)
                        .apply(tool, channel, chatId, messageId);
            } catch (Exception e) {
                // 记录注入失败的调试日志
                log.debug("注入工具上下文失败: tool={}", toolName, e);
            }
        }
    }

    /**
     * 解析工具类的上下文设置器
     * 优先查找 setContext(String, String, String) 方法，其次查找 setContext(String, String) 方法
     *
     * @param toolClass 工具类
     * @return 对应的上下文设置器
     */
    private static ContextSetter resolveSetter(Class<?> toolClass) {
        try {
            // 尝试获取三个参数的 setContext 方法
            Method m3 = toolClass.getMethod("setContext", String.class, String.class, String.class);
            // 返回调用该方法的 lambda 表达式
            return (tool, channel, chatId, messageId) -> m3.invoke(tool, channel, chatId, messageId);
        } catch (NoSuchMethodException ignored) {
            // 如果不存在三个参数的方法，继续尝试两个参数的方法
        }

        try {
            // 尝试获取两个参数的 setContext 方法
            Method m2 = toolClass.getMethod("setContext", String.class, String.class);
            // 返回调用该方法的 lambda 表达式，忽略 messageId
            return (tool, channel, chatId, messageId) -> m2.invoke(tool, channel, chatId);
        } catch (NoSuchMethodException ignored) {
            // 如果都不存在，返回空操作设置器
            return NOOP;
        }
    }

    /**
     * 函数式接口，定义上下文设置的行为
     */
    @FunctionalInterface
    private interface ContextSetter {
        /**
         * 应用上下文到工具
         *
         * @param tool      工具实例
         * @param channel   渠道标识
         * @param chatId    聊天ID
         * @param messageId 消息ID
         * @throws Exception 反射调用异常
         */
        void apply(Tool tool, String channel, String chatId, String messageId) throws Exception;
    }
}
