package ricbot.domain.hook;

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

    /**
     * 默认构造函数，不重新抛出异常
     */
    protected AgentHook() {
        this(false);
    }

    /**
     * 带参数的构造函数
     *
     * @param reraise 是否重新抛出异常
     */
    protected AgentHook(boolean reraise) {
        this.reraise = reraise; // 初始化 reraise 字段
    }

    /**
     * 获取是否重新抛出异常的标志
     *
     * @return 是否重新抛出异常
     */
    public boolean isReraise() {
        return reraise;
    }

    /**
     * 是否希望接收流式 delta
     *
     * @return 是否希望接收流式数据，默认为 false
     */
    public boolean wantsStreaming() {
        return false;
    }

    /**
     * 每轮模型请求前调用的钩子方法
     *
     * @param context AgentHookContext 上下文对象
     * @throws Exception 可能抛出的异常
     */
    public void beforeIteration(AgentHookContext context) throws Exception {
        // 默认实现为空，子类可选择性重写
    }

    /**
     * 每轮模型请求后调用的钩子方法
     *
     * @param context AgentHookContext 上下文对象
     * @throws Exception 可能抛出的异常
     */
    public void afterIteration(AgentHookContext context) throws Exception {
        // 默认实现为空，子类可选择性重写
    }

    /**
     * tool 执行前调用的钩子方法
     *
     * @param context AgentHookContext 上下文对象
     * @throws Exception 可能抛出的异常
     */
    public void beforeExecuteTools(AgentHookContext context) throws Exception {
        // 默认实现为空，子类可选择性重写
    }

    /**
     * tool 执行后调用的钩子方法
     *
     * @param context AgentHookContext 上下文对象
     * @throws Exception 可能抛出的异常
     */
    public void afterExecuteTools(AgentHookContext context) throws Exception {
        // 默认实现为空，子类可选择性重写
    }

    /**
     * 流式输出增量时调用的钩子方法
     *
     * @param context AgentHookContext 上下文对象
     * @param delta   流式输出的增量字符串
     * @throws Exception 可能抛出的异常
     */
    public void onStream(AgentHookContext context, String delta) throws Exception {
        // 默认实现为空，子类可选择性重写
    }

    /**
     * 流式输出结束时调用的钩子方法
     *
     * @param context  AgentHookContext 上下文对象
     * @param resuming 是否正在恢复流式输出
     * @throws Exception 可能抛出的异常
     */
    public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
        // 默认实现为空，子类可选择性重写
    }

    /**
     * 最终输出前做一次清洗/修正
     *
     * @param context AgentHookContext 上下文对象
     * @param content 原始内容
     * @return 清洗后的内容
     */
    public String finalizeContent(AgentHookContext context, String content) {
        return content; // 默认返回原始内容，子类可选择性重写
    }

    /**
     * 错误回调方法
     *
     * @param context AgentHookContext 上下文对象
     * @param error   发生的异常
     * @throws Exception 可能抛出的异常
     */
    public void onError(AgentHookContext context, Exception error) throws Exception {
        // 默认实现为空，子类可选择性重写
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

        private final List<AgentHook> hooks; // 存储多个 Hook 的列表

        /**
         * 构造函数，初始化 CompositeHook
         *
         * @param hooks Hook 列表，如果为 null 则初始化为空列表
         */
        public CompositeHook(List<AgentHook> hooks) {
            super(true); // 调用父类构造函数，设置 reraise 为 true
            this.hooks = hooks != null ? hooks : new ArrayList<>(); // 初始化 hooks 列表
        }

        /**
         * 获取 Hook 列表
         *
         * @return Hook 列表
         */
        public List<AgentHook> getHooks() {
            return hooks;
        }

        /**
         * 检查是否有任何 Hook 需要流式输出
         *
         * @return 如果有任意 Hook 需要流式输出则返回 true，否则返回 false
         */
        @Override
        public boolean wantsStreaming() {
            for (AgentHook hook : hooks) {
                if (hook.wantsStreaming()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 在每轮模型请求前调用所有 Hook 的 beforeIteration 方法
         *
         * @param context AgentHookContext 上下文对象
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void beforeIteration(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.beforeIteration(context), hook); // 调用每个 Hook 的 beforeIteration 方法
            }
        }

        /**
         * 在每轮模型请求后调用所有 Hook 的 afterIteration 方法
         *
         * @param context AgentHookContext 上下文对象
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void afterIteration(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.afterIteration(context), hook); // 调用每个 Hook 的 afterIteration 方法
            }
        }

        /**
         * 在 tool 执行前调用所有 Hook 的 beforeExecuteTools 方法
         *
         * @param context AgentHookContext 上下文对象
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void beforeExecuteTools(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.beforeExecuteTools(context), hook); // 调用每个 Hook 的 beforeExecuteTools 方法
            }
        }

        /**
         * 在 tool 执行后调用所有 Hook 的 afterExecuteTools 方法
         *
         * @param context AgentHookContext 上下文对象
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void afterExecuteTools(AgentHookContext context) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.afterExecuteTools(context), hook); // 调用每个 Hook 的 afterExecuteTools 方法
            }
        }

        /**
         * 在流式输出增量时调用所有 Hook 的 onStream 方法
         *
         * @param context AgentHookContext 上下文对象
         * @param delta   流式输出的增量字符串
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void onStream(AgentHookContext context, String delta) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.onStream(context, delta), hook); // 调用每个 Hook 的 onStream 方法
            }
        }

        /**
         * 在流式输出结束时调用所有 Hook 的 onStreamEnd 方法
         *
         * @param context  AgentHookContext 上下文对象
         * @param resuming 是否正在恢复流式输出
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.onStreamEnd(context, resuming), hook); // 调用每个 Hook 的 onStreamEnd 方法
            }
        }

        /**
         * 在最终输出前调用所有 Hook 的 finalizeContent 方法进行内容清洗
         *
         * @param context AgentHookContext 上下文对象
         * @param content 原始内容
         * @return 清洗后的内容
         */
        @Override
        public String finalizeContent(AgentHookContext context, String content) {
            String result = content;
            for (AgentHook hook : hooks) {
                try {
                    result = hook.finalizeContent(context, result); // 调用每个 Hook 的 finalizeContent 方法
                } catch (Exception e) {
                    if (hook.isReraise()) {
                        throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e); // 如果 Hook 配置为重新抛出异常，则抛出
                    }
                }
            }
            return result;
        }

        /**
         * 在发生错误时调用所有 Hook 的 onError 方法
         *
         * @param context AgentHookContext 上下文对象
         * @param error   发生的异常
         * @throws Exception 可能抛出的异常
         */
        @Override
        public void onError(AgentHookContext context, Exception error) throws Exception {
            for (AgentHook hook : hooks) {
                invoke(() -> hook.onError(context, error), hook); // 调用每个 Hook 的 onError 方法
            }
        }

        /**
         * 安全地调用 Hook 方法，根据 reraise 配置决定是否重新抛出异常
         *
         * @param runnable 要执行的 Runnable
         * @param hook     当前 Hook 对象
         * @throws Exception 可能抛出的异常
         */
        private void invoke(ThrowingRunnable runnable, AgentHook hook) throws Exception {
            try {
                runnable.run(); // 执行 Runnable
            } catch (Exception e) {
                if (hook.isReraise()) {
                    throw e; // 如果 Hook 配置为重新抛出异常，则抛出
                }
            }
        }

        /**
         * 函数式接口，用于表示可能抛出异常的 Runnable
         */
        @FunctionalInterface
        private interface ThrowingRunnable {
            void run() throws Exception;
        }
    }
}
