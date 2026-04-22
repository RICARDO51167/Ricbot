package ricbot.integration.channel;

import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 渠道管理器。
 *
 * 主要职责：
 * 1. 初始化启用的渠道
 * 2. 启动/停止所有渠道
 * 3. 从 MessageBus 的 outbound 队列消费消息
 * 4. 将消息路由到正确渠道
 * 5. 做发送失败重试
 *
 * 对应 Python: nanobot.channels.manager.ChannelManager
 */
public class ChannelManager {

    /**
     * 发送失败重试延迟：1s, 2s, 4s
     */
    private static final int[] SEND_RETRY_DELAYS_MS = {1000, 2000, 4000};

    // 配置对象，用于获取系统配置信息
    private final Config config;
    // 消息总线，用于处理出站和入站消息
    private final MessageBus bus;

    /**
     * 已启用渠道
     * 使用 ConcurrentHashMap 保证线程安全，key 为渠道名称，value 为渠道实例
     */
    private final Map<String, BaseChannel> channels = new ConcurrentHashMap<>();

    /**
     * 出站分发线程池
     * 使用有界线程池，避免渠道异常时任务无限堆积
     */
    private final ExecutorService executor = createExecutor();

    /**
     * 出站消息分发 Future
     * 用于控制分发循环任务的取消
     */
    private Future<?> dispatchFuture;

    // 原子布尔值，用于标记管理器是否正在运行，保证状态切换的原子性
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造函数
     * @param config 配置对象
     * @param bus 消息总线
     */
    public ChannelManager(Config config, MessageBus bus) {
        this.config = config; // 初始化配置
        this.bus = bus; // 初始化消息总线
        initChannels(); // 初始化所有启用的渠道
    }

    private static ExecutorService createExecutor() {
        int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        return new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "channel-manager");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 初始化渠道。
     *
     * 流程：
     * 1. 发现所有 channel
     * 2. 找到 config.channels 对应 section
     * 3. 判断 enabled
     * 4. 实例化 channel
     * 5. 注入 transcription provider/key
     */
    private void initChannels() {
        // 通过注册表发现所有可用的渠道类
        Map<String, Class<? extends BaseChannel>> discovered = ChannelRegistry.discoverAll();

        // 获取配置中的转录服务提供商
        String transcriptionProvider = config.getChannels().getTranscriptionProvider();
        // 解析对应的 API Key
        String transcriptionKey = resolveTranscriptionKey(transcriptionProvider);
        // 解析对应的 API Base URL
        String transcriptionApiBase = resolveTranscriptionApiBase(transcriptionProvider);

        // 遍历所有发现的渠道类
        for (Map.Entry<String, Class<? extends BaseChannel>> entry : discovered.entrySet()) {
            String name = entry.getKey(); // 渠道名称
            Class<? extends BaseChannel> clazz = entry.getValue(); // 渠道类

            // 获取该渠道在配置文件中的具体配置节
            Object section = config.getChannels().getSection(name);
            if (section == null) {
                continue; // 如果没有配置节，跳过
            }

            // 检查该渠道是否启用
            boolean enabled = config.getChannels().isEnabled(name);
            if (!enabled) {
                continue; // 如果未启用，跳过
            }

            try {
                // 通过反射实例化渠道对象，传入配置节和消息总线
                BaseChannel channel = clazz
                        .getConstructor(Object.class, MessageBus.class)
                        .newInstance(section, bus);

                // 设置转录相关配置
                channel.setTranscriptionProvider(transcriptionProvider);
                channel.setTranscriptionApiKey(transcriptionKey);
                channel.setTranscriptionApiBase(transcriptionApiBase);

                // 将实例化的渠道放入映射表
                channels.put(name, channel);
                System.out.println(clazz.getSimpleName() + " 渠道已启用");
            } catch (Exception e) {
                // 捕获实例化或初始化过程中的异常
                System.err.println(name + " 渠道不可用：" + e.getMessage());
            }
        }

        // 校验允许来源配置
        validateAllowFrom();
    }

    /**
     * 选择 transcription provider 对应的 API key。
     * @param provider 提供商名称
     * @return API Key
     */
    private String resolveTranscriptionKey(String provider) {
        try {
            // 如果是 OpenAI，返回 OpenAI 的 Key
            if ("openai".equalsIgnoreCase(provider)) {
                return config.getProviders().getOpenai().getApiKey();
            }
            // 否则默认返回 Groq 的 Key
            return config.getProviders().getGroq().getApiKey();
        } catch (Exception e) {
            // 发生异常时返回空字符串
            return "";
        }
    }

    /**
     * 解析转录服务的 API Base URL
     * @param provider 提供商名称
     * @return API Base URL
     */
    private String resolveTranscriptionApiBase(String provider) {
        try {
            // 如果是 OpenAI，返回 OpenAI 的 Base URL
            if ("openai".equalsIgnoreCase(provider)) {
                return config.getProviders().getOpenai().getApiBase();
            }
            // 否则默认返回 Groq 的 Base URL
            return config.getProviders().getGroq().getApiBase();
        } catch (Exception e) {
            // 发生异常时返回空字符串
            return "";
        }
    }

    /**
     * 校验 allow_from。
     *
     * 空数组等价于 deny all，这里按 Python 原逻辑直接阻止启动。
     */
    private void validateAllowFrom() {
        List<String> toRemove = new ArrayList<>(); // 存储需要移除的渠道名称
        // 遍历所有已初始化的渠道
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            BaseChannel ch = entry.getValue();
            List<String> allow = ch.getAllowFrom(); // 获取允许的来源列表
            // 如果列表不为 null 但为空，视为无效配置
            if (allow != null && allow.isEmpty()) {
                System.err.println(
                        "渠道 " + entry.getKey() + " 配置无效：allowFrom 为空（等价于拒绝所有）。"
                                + "如需允许所有人，请设置为 [\"*\"]；或添加具体的用户 ID。"
                );
                toRemove.add(entry.getKey()); // 标记为待移除
            }
        }
        // 移除所有标记为无效的渠道
        for (String name : toRemove) {
            channels.remove(name);
        }
    }

    /**
     * 启动单个渠道。
     * @param name 渠道名称
     * @param channel 渠道实例
     */
    private void startChannel(String name, BaseChannel channel) {
        try {
            channel.start(); // 调用渠道的启动方法
        } catch (Exception e) {
            // 捕获启动异常并打印错误信息
            System.err.println("启动渠道 " + name + " 失败：" + e.getMessage());
        }
    }

    /**
     * 启动所有渠道和出站分发器。
     */
    public void startAll() {
        // 原子性地设置运行状态为 true，如果已经是 true 则直接返回，防止重复启动
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // 提交出站消息分发循环任务到线程池
        dispatchFuture = executor.submit(this::dispatchOutboundLoop);

        // 如果没有启用任何渠道，打印警告并返回
        if (channels.isEmpty()) {
            System.err.println("未启用任何渠道");
            return;
        }

        // 遍历所有渠道并异步启动
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            System.out.println("正在启动渠道 " + name + "…");
            executor.submit(() -> startChannel(name, channel)); // 异步启动单个渠道
        }

        // 检查是否需要发送重启完成通知
        notifyRestartDoneIfNeeded();
    }

    /**
     * 停止所有渠道和 dispatcher。
     */
    public void stopAll() {
        // 原子性地设置运行状态为 false，如果已经是 false 则直接返回
        if (!running.getAndSet(false)) {
            return;
        }

        // 取消分发任务
        if (dispatchFuture != null) {
            dispatchFuture.cancel(true);
        }

        // 遍历所有渠道并停止
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            try {
                entry.getValue().stop(); // 调用渠道的停止方法
                System.out.println("已停止渠道 " + entry.getKey());
            } catch (Exception e) {
                // 捕获停止过程中的异常
                System.err.println("停止渠道 " + entry.getKey() + " 时出错：" + e.getMessage());
            }
        }

        // 立即关闭线程池，尝试停止所有正在执行的任务
        executor.shutdownNow();
    }

    /**
     * 若存在 restart notice，则发送一条重启完成消息。
     *
     * 这里保留一个可扩展点；你前面如果已经有 RestartUtils，可直接替换。
     */
    private void notifyRestartDoneIfNeeded() {
        // 从环境变量中消耗重启通知
        RestartNotice notice = RestartUtils.consumeRestartNoticeFromEnv();
        if (notice == null) return; // 如果没有通知，直接返回

        // 构建出站消息
        OutboundMessage msg = new OutboundMessage();
        msg.setChannel(notice.channel()); // 设置渠道
        msg.setChatId(notice.chatId()); // 设置聊天 ID
        msg.setContent(RestartUtils.formatRestartCompletedMessage(notice.startedAtRaw())); // 设置消息内容

        // 通过 bus 发送
        bus.sendOutbound(msg);
    }

    /**
     * 出站消息分发主循环。
     *
     * 逻辑：
     * 1. 先看 pending buffer
     * 2. 再从 outbound 队列取
     * 3. 过滤 progress/tool_hint
     * 4. 合并连续 stream delta
     * 5. 路由到正确 channel
     * 6. sendWithRetry
     */
    private void dispatchOutboundLoop() {
        // 待处理消息缓冲区，用于存放合并流消息时暂存的其他消息
        List<OutboundMessage> pending = new LinkedList<>();

        // 当管理器处于运行状态时循环
        while (running.get()) {
            try {
                OutboundMessage msg;

                // 优先从 pending 缓冲区获取消息
                if (!pending.isEmpty()) {
                    msg = pending.remove(0);
                } else {
                    // 否则从消息总线轮询消息，超时时间 1000ms
                    msg = bus.pollOutbound(1000);
                }

                // 如果没有获取到消息，继续下一次循环
                if (msg == null) {
                    continue;
                }

                // 获取消息元数据，如果为 null 则使用空 Map
                Map<String, Object> metadata = msg.getMetadata() != null
                        ? msg.getMetadata()
                        : Collections.emptyMap();

                // 处理进度消息
                if (Boolean.TRUE.equals(metadata.get("_progress"))) {
                    boolean isToolHint = Boolean.TRUE.equals(metadata.get("_tool_hint"));
                    // 如果是工具提示且配置不允许发送，跳过
                    if (isToolHint && !config.getChannels().isSendToolHints()) {
                        continue;
                    }
                    // 如果不是工具提示（普通进度）且配置不允许发送，跳过
                    if (!isToolHint && !config.getChannels().isSendProgress()) {
                        continue;
                    }
                }

                // 处理流式增量消息合并
                if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                        && !Boolean.TRUE.equals(metadata.get("_stream_end"))) {
                    // 合并连续的流式增量
                    CoalesceResult merged = coalesceStreamDeltas(msg);
                    msg = merged.message; // 更新为合并后的消息
                    pending.addAll(merged.extraPending); // 将暂存的消息加入 pending 缓冲区
                }

                // 根据消息中的渠道名称获取对应的渠道实例
                BaseChannel channel = channels.get(msg.getChannel());
                if (channel != null) {
                    // 如果渠道存在，带重试发送消息
                    sendWithRetry(channel, msg);
                } else {
                    // 如果渠道不存在，打印错误日志
                    System.err.println("未知渠道：" + msg.getChannel());
                }

            } catch (Exception e) {
                // 如果管理器已停止，退出循环
                if (!running.get()) {
                    return;
                }
                // 打印分发器异常日志
                System.err.println("出站分发器错误：" + e.getMessage());
            }
        }
    }

    /**
     * 单次发送，不带重试。
     * @param channel 目标渠道
     * @param msg 待发送消息
     * @throws Exception 发送异常
     */
    private void sendOnce(BaseChannel channel, OutboundMessage msg) throws Exception {
        // 获取消息元数据
        Map<String, Object> metadata = msg.getMetadata() != null
                ? msg.getMetadata()
                : Collections.emptyMap();

        // 如果是流式增量或流式结束标记
        if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                || Boolean.TRUE.equals(metadata.get("_stream_end"))) {
            // 调用渠道的 sendDelta 方法
            channel.sendDelta(msg.getChatId(), msg.getContent(), msg.getMetadata());
        } else if (!Boolean.TRUE.equals(metadata.get("_streamed"))) {
            // 如果不是流式消息，调用普通 send 方法
            channel.send(msg);
        }
    }

    /**
     * 带重试发送。
     * @param channel 目标渠道
     * @param msg 待发送消息
     */
    private void sendWithRetry(BaseChannel channel, OutboundMessage msg) {
        // 获取最大重试次数，至少为 1
        int maxAttempts = Math.max(config.getChannels().getSendMaxRetries(), 1);

        // 重试循环
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                sendOnce(channel, msg); // 尝试发送
                return; // 发送成功，直接返回
            } catch (Exception e) {
                // 如果是最后一次尝试
                if (attempt == maxAttempts - 1) {
                    System.err.println(
                            "发送到 " + msg.getChannel()
                                    + " 失败（已重试 " + maxAttempts + " 次）：" + e.getMessage()
                    );
                    return; // 记录错误并返回
                }

                // 计算重试延迟时间
                int delay = SEND_RETRY_DELAYS_MS[Math.min(attempt, SEND_RETRY_DELAYS_MS.length - 1)];
                try {
                    Thread.sleep(delay); // 等待指定时间
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    return; // 退出重试
                }
            }
        }
    }

    /**
     * 合并连续的 stream delta 消息。
     *
     * 只合并：
     * - 相同 channel
     * - 相同 chat_id
     * - 连续 _stream_delta
     *
     * 其他消息先放到 extraPending。
     * @param first 第一条流式消息
     * @return 合并结果
     */
    private CoalesceResult coalesceStreamDeltas(OutboundMessage first) {
        String targetChannel = first.getChannel(); // 目标渠道
        String targetChatId = first.getChatId(); // 目标聊天 ID

        // 初始化内容构建器
        StringBuilder combined = new StringBuilder(first.getContent() != null ? first.getContent() : "");
        // 存储非合并消息的列表
        List<OutboundMessage> extraPending = new ArrayList<>();

        // 无限循环直到没有更多可合并的消息
        while (true) {
            // 立即轮询下一条消息，不阻塞
            OutboundMessage next = bus.pollOutboundNow();
            if (next == null) {
                break; // 没有更多消息，跳出循环
            }

            // 获取下一条消息的元数据
            Map<String, Object> metadata = next.getMetadata() != null
                    ? next.getMetadata()
                    : Collections.emptyMap();

            // 判断是否是相同的目标渠道和聊天 ID
            boolean sameTarget = Objects.equals(targetChannel, next.getChannel())
                    && Objects.equals(targetChatId, next.getChatId());

            // 判断是否是流式增量
            boolean isDelta = Boolean.TRUE.equals(metadata.get("_stream_delta"));
            // 判断是否是流式结束
            boolean isEnd = Boolean.TRUE.equals(metadata.get("_stream_end"));

            // 如果目标相同且是流式增量
            if (sameTarget && isDelta) {
                // 追加内容
                combined.append(next.getContent() != null ? next.getContent() : "");
                // 如果是流式结束标记
                if (isEnd) {
                    // 创建合并后的元数据
                    Map<String, Object> mergedMeta = new HashMap<>(
                            first.getMetadata() != null ? first.getMetadata() : Collections.emptyMap()
                    );
                    mergedMeta.put("_stream_end", true); // 标记为结束

                    // 创建合并后的消息对象
                    OutboundMessage merged = new OutboundMessage();
                    merged.setChannel(first.getChannel());
                    merged.setChatId(first.getChatId());
                    merged.setContent(combined.toString());
                    merged.setMetadata(mergedMeta);
                    // 返回合并结果
                    return new CoalesceResult(merged, extraPending);
                }
            } else {
                // 如果不满足合并条件，将消息加入 extraPending 并跳出循环
                extraPending.add(next);
                break;
            }
        }

        // 如果没有遇到结束标记，返回当前合并状态
        OutboundMessage merged = new OutboundMessage();
        merged.setChannel(first.getChannel());
        merged.setChatId(first.getChatId());
        merged.setContent(combined.toString());
        merged.setMetadata(first.getMetadata());
        return new CoalesceResult(merged, extraPending);
    }

    /**
     * 获取指定名称的渠道
     * @param name 渠道名称
     * @return 渠道实例
     */
    public BaseChannel getChannel(String name) {
        return channels.get(name);
    }

    /**
     * 获取所有已启用渠道的名称列表
     * @return 渠道名称列表
     */
    public List<String> getEnabledChannels() {
        return new ArrayList<>(channels.keySet());
    }

    /**
     * 获取所有渠道的状态信息
     * @return 状态 Map
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        // 遍历所有渠道
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", true); // 标记为启用
            item.put("running", entry.getValue().isRunning()); // 获取运行状态
            status.put(entry.getKey(), item);
        }
        return status;
    }

    /**
     * 内部类：流式消息合并结果
     */
    private static class CoalesceResult {
        final OutboundMessage message; // 合并后的消息
        final List<OutboundMessage> extraPending; // 暂存的其他消息

        CoalesceResult(OutboundMessage message, List<OutboundMessage> extraPending) {
            this.message = message;
            this.extraPending = extraPending;
        }
    }
}
