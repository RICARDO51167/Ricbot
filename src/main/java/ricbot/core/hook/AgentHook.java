package ricbot.core.hook;


import java.util.ArrayList;
import java.util.List;

/**
 * Agent 生命周期 Hook 抽象基类
 *
 * 主要目标：
 * 1. 允许在 agent 执行的关键阶段插入逻辑
 * 2. 用于：
 *    - 流式输出
 *    - tool call 前后通知
 *    - usage 统计
 *    - 最终内容清洗
 *
 * 对应 Python: AgentHook
 */
public abstract class AgentHook {

    /**
     * 是否在 hook 抛错时继续吞掉异常。
     * 对应你前面 Python 代码里 super().__init__(reraise=True) 那种语义。
     */
    private final boolean reraise;

    protected AgentHook() {
        this(false);
    }

    protected AgentHook(boolean reraise) {
        this.reraise = reraise;
    }

    public boolean isReraise() {
        return reraise;
    }

    /**
     * 是否希望接收流式 delta
     */
    public boolean wantsStreaming() {
        return false;
    }

    /**
     * 每轮模型请求前
     */
    public void beforeIteration(AgentHookContext context) throws Exception {
    }

    /**
     * 每轮模型请求后
     */
    public void afterIteration(AgentHookContext context) throws Exception {
    }

    /**
     * tool 执行前
     */
    public void beforeExecuteTools(AgentHookContext context) throws Exception {
    }

    /**
     * tool 执行后
     */
    public void afterExecuteTools(AgentHookContext context) throws Exception {
    }

    /**
     * 流式输出增量
     */
    public void onStream(AgentHookContext context, String delta) throws Exception {
    }

    /**
     * 流式输出结束
     */
    public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
    }

    /**
     * 最终输出前做一次清洗/修正
     */
    public String finalizeContent(AgentHookContext context, String content) {
        return content;
    }

    /**
     * 错误回调
     */
    public void onError(AgentHookContext context, Exception error) throws Exception {
    }

    /**
     * 组合 Hook
     *
     * 主要目标：
     * 1. 把多个 Hook 串起来
     * 2. 顺序执行每个生命周期方法
     *
     * 对应 Python: CompositeHook
     */
    public static class CompositeHook extends AgentHook {

        private final List<AgentHook> hooks;

        public CompositeHook(List<AgentHook> hooks) {
            super(true);
            this.hooks = hooks != null ? hooks : new ArrayList<>();
        }

        public List<AgentHook> getHooks() {
            return hooks;
        }

        @Override
        public boolean wantsStreaming() {
            for (AgentHook hook : hooks) {
                if (hook.wantsStreaming()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void beforeIteration(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.beforeIteration(context), hook);
            }
        }

        @Override
        public void afterIteration(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.afterIteration(context), hook);
            }
        }

        @Override
        public void beforeExecuteTools(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.beforeExecuteTools(context), hook);
            }
        }

        @Override
        public void afterExecuteTools(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.afterExecuteTools(context), hook);
            }
        }

        @Override
        public void onStream(AgentHookContext context, String delta) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.onStream(context, delta), hook);
            }
        }

        @Override
        public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.onStreamEnd(context, resuming), hook);
            }
        }

        @Override
        public String finalizeContent(AgentHookContext context, String content) {
            String result = content;
            for (AgentHook hook : hooks) {
                try {
                    result = hook.finalizeContent(context, result);
                } catch (Exception e) {
                    if (hook.isReraise()) {
                        throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                    }
                }
            }
            return result;
        }

        @Override
        public void onError(AgentHookContext context, Exception error) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.onError(context, error), hook);
            }
        }

        private void invoke(ThrowingRunnable runnable, AgentHook hook) throws Exception {
            try {
                runnable.run();
            } catch (Exception e) {
                if (hook.isReraise()) {
                    throw e;
                }
            }
        }

        @FunctionalInterface
        private interface ThrowingRunnable {
            void run() throws Exception;
        }
    }
}
