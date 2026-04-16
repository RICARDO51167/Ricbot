package ricbot.integration.channel;

import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 渠道管理器。
 */
public class ChannelManager {

    private static final int[] SEND_RETRY_DELAYS_MS = {1000, 2000, 4000};

    private final Config config;
    private final MessageBus bus;

    private final Map<String, BaseChannel> channels = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Future<?> dispatchFuture;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public ChannelManager(Config config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
        initChannels();
    }

    private void initChannels() {
        Map<String, Class<? extends BaseChannel>> discovered = ChannelRegistry.discoverAll();

        String transcriptionProvider = config.getChannels().getTranscriptionProvider();
        String transcriptionKey = resolveTranscriptionKey(transcriptionProvider);
        String transcriptionApiBase = resolveTranscriptionApiBase(transcriptionProvider);

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
                channel.setTranscriptionApiBase(transcriptionApiBase);

                channels.put(name, channel);
                System.out.println(clazz.getSimpleName() + " 渠道已启用");
            } catch (Exception e) {
                System.err.println(name + " 渠道不可用：" + e.getMessage());
            }
        }

        validateAllowFrom();
    }

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

    private String resolveTranscriptionApiBase(String provider) {
        try {
            if ("openai".equalsIgnoreCase(provider)) {
                return config.getProviders().getOpenai().getApiBase();
            }
            return config.getProviders().getGroq().getApiBase();
        } catch (Exception e) {
            return "";
        }
    }

    private void validateAllowFrom() {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            BaseChannel ch = entry.getValue();
            List<String> allow = ch.getAllowFrom();
            if (allow != null && allow.isEmpty()) {
                System.err.println(
                        "渠道 " + entry.getKey() + " 配置无效：allowFrom 为空（等价于拒绝所有）。"
                                + "如需允许所有人，请设置为 [\"*\"]；或添加具体的用户 ID。"
                );
                toRemove.add(entry.getKey());
            }
        }
        for (String name : toRemove) {
            channels.remove(name);
        }
    }

    private void startChannel(String name, BaseChannel channel) {
        try {
            channel.start();
        } catch (Exception e) {
            System.err.println("启动渠道 " + name + " 失败：" + e.getMessage());
        }
    }

    public void startAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        dispatchFuture = executor.submit(this::dispatchOutboundLoop);

        if (channels.isEmpty()) {
            System.err.println("未启用任何渠道");
            return;
        }

        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            System.out.println("正在启动渠道 " + name + "…");
            executor.submit(() -> startChannel(name, channel));
        }

        notifyRestartDoneIfNeeded();
    }

    public void stopAll() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (dispatchFuture != null) {
            dispatchFuture.cancel(true);
        }

        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            try {
                entry.getValue().stop();
                System.out.println("已停止渠道 " + entry.getKey());
            } catch (Exception e) {
                System.err.println("停止渠道 " + entry.getKey() + " 时出错：" + e.getMessage());
            }
        }

        executor.shutdownNow();
    }

    private void notifyRestartDoneIfNeeded() {
        RestartNotice notice = RestartUtils.consumeRestartNoticeFromEnv();
        if (notice == null) return;

        OutboundMessage msg = new OutboundMessage();
        msg.setChannel(notice.channel());
        msg.setChatId(notice.chatId());
        msg.setContent(RestartUtils.formatRestartCompletedMessage(notice.startedAtRaw()));

        bus.sendOutbound(msg);
    }

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
                    System.err.println("未知渠道：" + msg.getChannel());
                }

            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                System.err.println("出站分发器错误：" + e.getMessage());
            }
        }
    }

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

    private void sendWithRetry(BaseChannel channel, OutboundMessage msg) {
        int maxAttempts = Math.max(config.getChannels().getSendMaxRetries(), 1);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                sendOnce(channel, msg);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts - 1) {
                    System.err.println(
                            "发送到 " + msg.getChannel()
                                    + " 失败（已重试 " + maxAttempts + " 次）：" + e.getMessage()
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
