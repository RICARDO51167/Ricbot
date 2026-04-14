package ricbot.cli;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对应 Python: stream.py
 *
 * 主要目标：
 * 1. CLI 流式输出渲染
 * 2. thinking spinner
 * 3. 增量输出时减少闪烁
 *
 * 说明：
 * Java 这里不直接依赖 Rich/Live，而是用控制台输出来模拟同样职责。
 * 后续你如果接 JLine / Lanterna，可以把这里替换成真正的终端 UI 渲染。
 */
public class StreamRenderer {

    private final boolean renderMarkdown;
    private final boolean showSpinner;

    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean streamed = new AtomicBoolean(false);

    private ThinkingSpinner spinner;
    private long lastRenderMillis = 0L;
    private boolean liveStarted = false;

    public StreamRenderer() {
        this(true, true);
    }

    public StreamRenderer(boolean renderMarkdown, boolean showSpinner) {
        this.renderMarkdown = renderMarkdown;
        this.showSpinner = showSpinner;
        startSpinner();
    }

    public boolean isStreamed() {
        return streamed.get();
    }

    /**
     * 处理增量输出。
     */
    public synchronized void onDelta(String delta) {
        if (delta == null) {
            return;
        }

        streamed.set(true);
        buffer.append(delta);

        if (!liveStarted) {
            if (buffer.toString().trim().isEmpty()) {
                return;
            }
            stopSpinner();
            System.out.println();
            System.out.println("ricbot");
            liveStarted = true;
        }

        long now = System.currentTimeMillis();
        if (delta.contains("\n") || (now - lastRenderMillis) > 50) {
            render();
            lastRenderMillis = now;
        }
    }

    /**
     * 一轮流式输出结束。
     *
     * @param resuming true 表示后面还会继续另一段流式输出
     */
    public synchronized void onEnd(boolean resuming) {
        if (liveStarted) {
            render();
            liveStarted = false;
        }

        stopSpinner();

        if (resuming) {
            buffer.setLength(0);
            startSpinner();
        } else {
            System.out.println();
        }
    }

    /**
     * 在用户输入前停止 spinner，避免和输入冲突。
     */
    public synchronized void stopForInput() {
        stopSpinner();
    }

    /**
     * 完全关闭渲染器。
     */
    public synchronized void close() {
        stopSpinner();
        liveStarted = false;
    }

    private void render() {
        // 这里做最简单的“整块刷新”式输出。
        // 如果你后面要更像 Rich Live，可接 JLine 的 redraw 支持。
        String text = buffer.toString();
        if (!renderMarkdown) {
            System.out.print("\r" + text);
            return;
        }

        // 当前先直接打印 markdown 原文。
        // 后面你可以接 markdown 渲染器。
        System.out.print("\r" + text);
    }

    private void startSpinner() {
        if (!showSpinner) {
            return;
        }
        spinner = new ThinkingSpinner();
        spinner.start();
    }

    private void stopSpinner() {
        if (spinner != null) {
            spinner.stop();
            spinner = null;
        }
    }

    /**
     * CLI thinking spinner。
     */
    public static class ThinkingSpinner {
        private final AtomicBoolean active = new AtomicBoolean(false);
        private Thread worker;

        public void start() {
            if (active.get()) {
                return;
            }
            active.set(true);
            worker = new Thread(() -> {
                String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
                int idx = 0;
                while (active.get()) {
                    System.out.print("\r" + frames[idx % frames.length] + " ricbot is thinking...");
                    idx++;
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "ricbot-thinking-spinner");
            worker.setDaemon(true);
            worker.start();
        }

        public void stop() {
            active.set(false);
            if (worker != null) {
                worker.interrupt();
            }
            System.out.print("\r");
        }

        public PauseContext pause() {
            return new PauseContext(this);
        }

        /**
         * 对应 Python 里的 pause() context manager。
         */
        public static class PauseContext implements AutoCloseable {
            private final ThinkingSpinner spinner;
            private final boolean wasActive;

            public PauseContext(ThinkingSpinner spinner) {
                this.spinner = spinner;
                this.wasActive = spinner != null && spinner.active.get();
                if (wasActive) {
                    spinner.stop();
                }
            }

            @Override
            public void close() {
                if (wasActive && spinner != null) {
                    spinner.start();
                }
            }
        }
    }
}
