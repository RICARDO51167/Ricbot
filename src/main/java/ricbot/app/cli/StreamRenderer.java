package ricbot.app.cli;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI 流式输出渲染器，支持 thinking spinner 和增量输出。
 */
public class StreamRenderer {

    private final boolean renderMarkdown;
    private final boolean showSpinner;

    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean streamed = new AtomicBoolean(false);

    private ThinkingSpinner spinner;
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

        if (!liveStarted) {
            if (delta.trim().isEmpty()) {
                return;
            }
            stopSpinner();
            System.out.println();
            System.out.println("ricbot");
            liveStarted = true;
        }

        String current = buffer.toString();
        if (!current.isEmpty() && delta.startsWith(current)) {
            String suffix = delta.substring(current.length());
            buffer.setLength(0);
            buffer.append(delta);
            if (!suffix.isEmpty()) {
                System.out.print(suffix);
            }
            return;
        }

        buffer.append(delta);
        System.out.print(delta);
    }

    /**
     * 一轮流式输出结束。
     *
     * @param resuming true 表示后面还会继续另一段流式输出
     */
    public synchronized void onEnd(boolean resuming) {
        liveStarted = false;
        buffer.setLength(0);

        stopSpinner();

        if (resuming) {
            startSpinner();
        } else {
            System.out.println();
        }
    }

    /**
     * 完全关闭渲染器。
     */
    public synchronized void close() {
        stopSpinner();
        liveStarted = false;
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
                    System.out.print("\r" + frames[idx % frames.length] + " ricbot 正在思考…");
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

    }
}
