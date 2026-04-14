package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;
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

    private final Config config;
    private final MessageBus bus;

    /**
     * 已启用渠道
     */
    private final Map<String, BaseChannel> channels = new ConcurrentHashMap<>();

    /**
     * 出站分发线程池
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 出站消息分发 Future
     */
    private Future<?> dispatchFuture;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public ChannelManager(Config config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
        initChannels();
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
        Map<String, Class<? extends BaseChannel>> discovered = ChannelRegistry.discoverAll();

        String transcriptionProvider = config.getChannels().getTranscriptionProvider();
        String transcriptionKey = resolveTranscriptionKey(transcriptionProvider);

        for (Map.Entry<String, Class<? extends BaseChannel>> entry : discovered.entrySet()) {
            String name = entry.getKey();
            Class<? extends BaseChannel> clazz = entry.getValue();

            Object section = config.getChannels().getSection(name);
            if (section == null) {
                continue;
            }

            boolean enabled = config.getChannels().isEnabled(name);
            if (!enabled) {
                continue;
            }

            try {
                BaseChannel channel = clazz
                        .getConstructor(Object.class, MessageBus.class)
                        .newInstance(section, bus);

                channel.setTranscriptionProvider(transcriptionProvider);
                channel.setTranscriptionApiKey(transcriptionKey);

                channels.put(name, channel);
                System.out.println(clazz.getSimpleName() + " channel enabled");
            } catch (Exception e) {
                System.err.println(name + " channel not available: " + e.getMessage());
            }
        }

        validateAllowFrom();
    }

    /**
     * 选择 transcription provider 对应的 API key。
     */
    private String resolveTranscriptionKey(String provider) {
        try {
            if ("openai".equalsIgnoreCase(provider)) {
                return config.getProviders().getOpenai().getApiKey();
            }
            return config.getProviders().getGroq().getApiKey();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 校验 allow_from。
     *
     * 空数组等价于 deny all，这里按 Python 原逻辑直接阻止启动。
     */
    private void validateAllowFrom() {
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            BaseChannel ch = entry.getValue();
            List<String> allow = ch.getAllowFrom();
            if (allow != null && allow.isEmpty()) {
                throw new IllegalStateException(
                        "Error: \"" + entry.getKey() + "\" has empty allowFrom (denies all). "
                                + "Set [\"*\"] to allow everyone, or add specific user IDs."
                );
            }
        }
    }

    /**
     * 启动单个渠道。
     */
    private void startChannel(String name, BaseChannel channel) {
        try {
            channel.start();
        } catch (Exception e) {
            System.err.println("Failed to start channel " + name + ": " + e.getMessage());
        }
    }

    /**
     * 启动所有渠道和出站分发器。
     */
    public void startAll() {
        if (channels.isEmpty()) {
            System.err.println("No channels enabled");
            return;
        }

        running.set(true);

        dispatchFuture = executor.submit(this::dispatchOutboundLoop);

        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            System.out.println("Starting " + name + " channel...");
            executor.submit(() -> startChannel(name, channel));
        }

        notifyRestartDoneIfNeeded();
    }

    /**
     * 停止所有渠道和 dispatcher。
     */
    public void stopAll() {
        running.set(false);

        if (dispatchFuture != null) {
            dispatchFuture.cancel(true);
        }

        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            try {
                entry.getValue().stop();
                System.out.println("Stopped " + entry.getKey() + " channel");
            } catch (Exception e) {
                System.err.println("Error stopping " + entry.getKey() + ": " + e.getMessage());
            }
        }

        executor.shutdownNow();
    }

    /**
     * 若存在 restart notice，则发送一条重启完成消息。
     *
     * 这里保留一个可扩展点；你前面如果已经有 RestartUtils，可直接替换。
     */
    private void notifyRestartDoneIfNeeded() {
        // TODO: 若你已有 restart 环境变量逻辑，可在这里接入
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
        List<OutboundMessage> pending = new LinkedList<>();

        while (running.get()) {
            try {
                OutboundMessage msg;

                if (!pending.isEmpty()) {
                    msg = pending.remove(0);
                } else {
                    msg = bus.pollOutbound(1000);
                }

                if (msg == null) {
                    continue;
                }

                Map<String, Object> metadata = msg.getMetadata() != null
                        ? msg.getMetadata()
                        : Collections.emptyMap();

                if (Boolean.TRUE.equals(metadata.get("_progress"))) {
                    boolean isToolHint = Boolean.TRUE.equals(metadata.get("_tool_hint"));
                    if (isToolHint && !config.getChannels().isSendToolHints()) {
                        continue;
                    }
                    if (!isToolHint && !config.getChannels().isSendProgress()) {
                        continue;
                    }
                }

                if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                        && !Boolean.TRUE.equals(metadata.get("_stream_end"))) {
                    CoalesceResult merged = coalesceStreamDeltas(msg);
                    msg = merged.message;
                    pending.addAll(merged.extraPending);
                }

                BaseChannel channel = channels.get(msg.getChannel());
                if (channel != null) {
                    sendWithRetry(channel, msg);
                } else {
                    System.err.println("Unknown channel: " + msg.getChannel());
                }

            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                System.err.println("Outbound dispatcher error: " + e.getMessage());
            }
        }
    }

    /**
     * 单次发送，不带重试。
     */
    private void sendOnce(BaseChannel channel, OutboundMessage msg) throws Exception {
        Map<String, Object> metadata = msg.getMetadata() != null
                ? msg.getMetadata()
                : Collections.emptyMap();

        if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                || Boolean.TRUE.equals(metadata.get("_stream_end"))) {
            channel.sendDelta(msg.getChatId(), msg.getContent(), msg.getMetadata());
        } else if (!Boolean.TRUE.equals(metadata.get("_streamed"))) {
            channel.send(msg);
        }
    }

    /**
     * 带重试发送。
     */
    private void sendWithRetry(BaseChannel channel, OutboundMessage msg) {
        int maxAttempts = Math.max(config.getChannels().getSendMaxRetries(), 1);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                sendOnce(channel, msg);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts - 1) {
                    System.err.println(
                            "Failed to send to " + msg.getChannel()
                                    + " after " + maxAttempts + " attempts: " + e.getMessage()
                    );
                    return;
                }

                int delay = SEND_RETRY_DELAYS_MS[Math.min(attempt, SEND_RETRY_DELAYS_MS.length - 1)];
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
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
     */
    private CoalesceResult coalesceStreamDeltas(OutboundMessage first) {
        String targetChannel = first.getChannel();
        String targetChatId = first.getChatId();

        StringBuilder combined = new StringBuilder(first.getContent() != null ? first.getContent() : "");
        List<OutboundMessage> extraPending = new ArrayList<>();

        while (true) {
            OutboundMessage next = bus.pollOutboundNow();
            if (next == null) {
                break;
            }

            Map<String, Object> metadata = next.getMetadata() != null
                    ? next.getMetadata()
                    : Collections.emptyMap();

            boolean sameTarget = Objects.equals(targetChannel, next.getChannel())
                    && Objects.equals(targetChatId, next.getChatId());

            boolean isDelta = Boolean.TRUE.equals(metadata.get("_stream_delta"));
            boolean isEnd = Boolean.TRUE.equals(metadata.get("_stream_end"));

            if (sameTarget && isDelta) {
                combined.append(next.getContent() != null ? next.getContent() : "");
                if (isEnd) {
                    Map<String, Object> mergedMeta = new HashMap<>(
                            first.getMetadata() != null ? first.getMetadata() : Collections.emptyMap()
                    );
                    mergedMeta.put("_stream_end", true);

                    OutboundMessage merged = new OutboundMessage();
                    merged.setChannel(first.getChannel());
                    merged.setChatId(first.getChatId());
                    merged.setContent(combined.toString());
                    merged.setMetadata(mergedMeta);
                    return new CoalesceResult(merged, extraPending);
                }
            } else {
                extraPending.add(next);
                break;
            }
        }

        OutboundMessage merged = new OutboundMessage();
        merged.setChannel(first.getChannel());
        merged.setChatId(first.getChatId());
        merged.setContent(combined.toString());
        merged.setMetadata(first.getMetadata());
        return new CoalesceResult(merged, extraPending);
    }

    public BaseChannel getChannel(String name) {
        return channels.get(name);
    }

    public List<String> getEnabledChannels() {
        return new ArrayList<>(channels.keySet());
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", true);
            item.put("running", entry.getValue().isRunning());
            status.put(entry.getKey(), item);
        }
        return status;
    }

    private static class CoalesceResult {
        final OutboundMessage message;
        final List<OutboundMessage> extraPending;

        CoalesceResult(OutboundMessage message, List<OutboundMessage> extraPending) {
            this.message = message;
            this.extraPending = extraPending;
        }
    }
}