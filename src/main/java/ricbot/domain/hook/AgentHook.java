package ricbot.domain.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 生命周期 Hook 抽象基类
 */
public abstract class AgentHook {

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

    public boolean wantsStreaming() {
        return false;
    }

    public void beforeIteration(AgentHookContext context) throws Exception {
    }

    public void afterIteration(AgentHookContext context) throws Exception {
    }

    public void beforeExecuteTools(AgentHookContext context) throws Exception {
    }

    public void afterExecuteTools(AgentHookContext context) throws Exception {
    }

    public void onStream(AgentHookContext context, String delta) throws Exception {
    }

    public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
    }

    public String finalizeContent(AgentHookContext context, String content) {
        return content;
    }

    public void onError(AgentHookContext context, Exception error) throws Exception {
    }

    /**
     * 组合 Hook
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
