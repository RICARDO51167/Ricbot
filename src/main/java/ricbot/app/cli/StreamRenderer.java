package ricbot.app.cli; // 定义当前类所在的包名

import java.util.concurrent.atomic.AtomicBoolean; // 导入原子布尔值类，用于线程安全的状态标记

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

    private final boolean renderMarkdown; // 是否渲染 Markdown 格式的标志
    private final boolean showSpinner; // 是否显示思考中旋转动画的标志

    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean streamed = new AtomicBoolean(false); // 标记是否已经开始流式输出，用于线程安全访问

    private ThinkingSpinner spinner; // 思考中旋转动画实例
    private boolean liveStarted = false; // 标记实时输出模式是否已启动

    public StreamRenderer() {
        this(true, true); // 默认构造函数，启用 Markdown 渲染和旋转动画
    }

    public StreamRenderer(boolean renderMarkdown, boolean showSpinner) {
        this.renderMarkdown = renderMarkdown; // 初始化 Markdown 渲染标志
        this.showSpinner = showSpinner; // 初始化旋转动画显示标志
        startSpinner(); // 构造函数中立即启动旋转动画
    }

    public boolean isStreamed() {
        return streamed.get(); // 获取是否已经进行过流式输出的状态
    }

    /**
     * 处理增量输出。
     */
    public synchronized void onDelta(String delta) {
        if (delta == null) { // 如果增量为空，直接返回
            return;
        }

        streamed.set(true); // 标记已开始流式输出

        if (!liveStarted) { // 如果实时输出模式尚未启动
            if (delta.trim().isEmpty()) { // 如果当前增量去除空白后仍为空
                return; // 暂不处理，等待非空内容
            }
            stopSpinner(); // 停止旋转动画
            System.out.println(); // 打印换行符
            System.out.println("ricbot"); // 打印标识符 "ricbot"
            liveStarted = true; // 标记实时输出模式已启动
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

        stopSpinner(); // 停止旋转动画

        if (resuming) { // 如果即将恢复（即还有下一轮流式输出）
            startSpinner(); // 重新启动旋转动画
        } else { // 如果完全结束
            System.out.println(); // 打印换行符，结束当前行
        }
    }

    /**
     * 在用户输入前停止 spinner，避免和输入冲突。
     */
    public synchronized void stopForInput() {
        stopSpinner(); // 停止旋转动画，防止干扰用户输入
    }

    /**
     * 完全关闭渲染器。
     */
    public synchronized void close() {
        stopSpinner(); // 停止旋转动画
        liveStarted = false; // 重置实时输出模式标志
    }

    private void startSpinner() {
        if (!showSpinner) { // 如果配置为不显示旋转动画
            return; // 直接返回
        }
        spinner = new ThinkingSpinner(); // 创建新的旋转动画实例
        spinner.start(); // 启动旋转动画线程
    }

    private void stopSpinner() {
        if (spinner != null) { // 如果旋转动画实例存在
            spinner.stop(); // 停止旋转动画
            spinner = null; // 释放引用
        }
    }

    /**
     * CLI thinking spinner。
     */
    public static class ThinkingSpinner {
        private final AtomicBoolean active = new AtomicBoolean(false); // 标记旋转动画是否处于活动状态
        private Thread worker; // 执行旋转动画的工作线程

        public void start() {
            if (active.get()) { // 如果已经在活动状态
                return; // 避免重复启动
            }
            active.set(true); // 设置为活动状态
            worker = new Thread(() -> { // 创建工作线程
                String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}; // 定义旋转动画帧
                int idx = 0; // 当前帧索引
                while (active.get()) { // 当处于活动状态时循环
                    System.out.print("\r" + frames[idx % frames.length] + " ricbot 正在思考…"); // 打印当前帧
                    idx++; // 索引递增
                    try {
                        Thread.sleep(120); // 休眠120毫秒以控制动画速度
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        break; // 退出循环
                    }
                }
            }, "ricbot-thinking-spinner"); // 设置线程名称
            worker.setDaemon(true); // 设置为守护线程，随主线程结束而结束
            worker.start(); // 启动线程
        }

        public void stop() {
            active.set(false); // 设置为非活动状态，停止循环
            if (worker != null) { // 如果工作线程存在
                worker.interrupt(); // 中断线程，使其尽快退出
            }
            System.out.print("\r"); // 打印回车符，清除当前行的旋转动画显示
        }

        public PauseContext pause() {
            return new PauseContext(this); // 创建并返回暂停上下文对象
        }

        /**
         * 对应 Python 里的 pause() context manager。
         */
        public static class PauseContext implements AutoCloseable {
            private final ThinkingSpinner spinner; // 关联的旋转动画实例
            private final boolean wasActive; // 记录暂停前是否处于活动状态

            public PauseContext(ThinkingSpinner spinner) {
                this.spinner = spinner; // 初始化关联的旋转动画实例
                this.wasActive = spinner != null && spinner.active.get(); // 记录暂停前的活动状态
                if (wasActive) { // 如果之前是活动的
                    spinner.stop(); // 停止旋转动画
                }
            }

            @Override
            public void close() {
                if (wasActive && spinner != null) { // 如果之前是活动的且实例存在
                    spinner.start(); // 重新启动旋转动画
                }
            }
        }
    }
}
