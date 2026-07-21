package ricbot.domain.agent;

import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;

/** Owns only tool-node concurrency resources; GraphRunService owns scheduling and transitions. */
public final class ToolNodeExecutor implements AutoCloseable {
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public ToolNodeExecutor(ExecutorService executor, boolean ownsExecutor) {
        if (executor == null) throw new IllegalArgumentException("tool executor is required");
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public <T> CompletionService<T> completionService() {
        return new ExecutorCompletionService<>(executor);
    }

    @Override
    public void close() {
        if (ownsExecutor) executor.shutdownNow();
    }
}
