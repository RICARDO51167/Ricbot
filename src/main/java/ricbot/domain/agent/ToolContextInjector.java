package ricbot.domain.agent;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ToolContextInjector implements ToolContextApplier {

    private static final Logger log = LoggerFactory.getLogger(ToolContextInjector.class);
    private static final ContextSetter NOOP = (tool, channel, chatId, messageId) -> {
    };

    private final ToolRegistry tools;
    private final Map<Class<?>, ContextSetter> setters = new ConcurrentHashMap<>();

    ToolContextInjector(ToolRegistry tools) {
        this.tools = tools;
    }

    @Override
    public void apply(String channel, String chatId, String messageId) {
        for (String toolName : tools.toolNames()) {
            Tool tool = tools.get(toolName);
            if (tool == null) {
                continue;
            }
            try {
                setters.computeIfAbsent(tool.getClass(), ToolContextInjector::resolveSetter)
                        .apply(tool, channel, chatId, messageId);
            } catch (Exception e) {
                log.debug("注入工具上下文失败: tool={}", toolName, e);
            }
        }
    }

    private static ContextSetter resolveSetter(Class<?> toolClass) {
        try {
            Method m3 = toolClass.getMethod("setContext", String.class, String.class, String.class);
            return (tool, channel, chatId, messageId) -> m3.invoke(tool, channel, chatId, messageId);
        } catch (NoSuchMethodException ignored) {
            // fall through
        }

        try {
            Method m2 = toolClass.getMethod("setContext", String.class, String.class);
            return (tool, channel, chatId, messageId) -> m2.invoke(tool, channel, chatId);
        } catch (NoSuchMethodException ignored) {
            return NOOP;
        }
    }

    @FunctionalInterface
    private interface ContextSetter {
        void apply(Tool tool, String channel, String chatId, String messageId) throws Exception;
    }
}
