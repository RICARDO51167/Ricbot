package ricbot.integration.channel;

import lombok.extern.slf4j.Slf4j;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
@Slf4j
public class ChannelManager {

    /**
     * 发送失败重试延迟：1s, 2s, 4s
     */
    private static final int[] SEND_RETRY_DELAYS_MS = {1000, 2000, 4000};

    private final Config config;
    private final MessageBus bus;
    private final Map<String, BaseChannel> channels = new ConcurrentHashMap<>();
    private final ExecutorService executor = createExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Future<?> dispatchFuture;

    public ChannelManager(Config config, MessageBus bus) {
        this(config, bus, ChannelRegistry.discoverAll());
    }

    ChannelManager(
            Config config,
            MessageBus bus,
            Map<String, Class<? extends BaseChannel>> discoveredChannels
    ) {
        this.config = config != null ? config : new Config();
        this.bus = Objects.requireNonNull(bus, "bus");
        initChannels(discoveredChannels != null ? discoveredChannels : Collections.emptyMap());
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

    private void initChannels(Map<String, Class<? extends BaseChannel>> discoveredChannels) {
        TranscriptionSettings transcription = resolveTranscriptionSettings();
        for (Map.Entry<String, Class<? extends BaseChannel>> entry : discoveredChannels.entrySet()) {
            String name = entry.getKey();
            if (!shouldEnableChannel(name)) {
                continue;
            }

            BaseChannel channel = instantiateChannel(name, entry.getValue(), config.getChannels().getSection(name));
            if (channel == null) {
                continue;
            }

            applyTranscriptionSettings(channel, transcription);
            channels.put(name, channel);
            log.info("{} 渠道已启用", name);
        }
        validateAllowFrom();
    }

    private boolean shouldEnableChannel(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Object section = config.getChannels().getSection(name);
        return section != null && config.getChannels().isEnabled(name);
    }

    private BaseChannel instantiateChannel(String name, Class<? extends BaseChannel> clazz, Object section) {
        if (clazz == null) {
            return null;
        }
        try {
            var constructor = clazz.getDeclaredConstructor(Object.class, MessageBus.class);
            constructor.setAccessible(true);
            return constructor.newInstance(section, bus);
        } catch (Exception e) {
            log.warn("{} 渠道不可用", name, e);
            return null;
        }
    }

    private void applyTranscriptionSettings(BaseChannel channel, TranscriptionSettings settings) {
        channel.setTranscriptionProvider(settings.providerName());
        channel.setTranscriptionApiKey(settings.apiKey());
        channel.setTranscriptionApiBase(settings.apiBase());
    }

    private TranscriptionSettings resolveTranscriptionSettings() {
        String providerName = normalizeTranscriptionProviderName(config.getChannels().getTranscriptionProvider());
        Config.ProviderConfig providerConfig = config.getProviders().get(providerName);
        if (providerConfig == null) {
            providerConfig = new Config.ProviderConfig();
        }
        return new TranscriptionSettings(
                providerName,
                emptyIfBlank(providerConfig.getApiKey()),
                emptyIfBlank(providerConfig.getApiBase())
        );
    }

    private static String normalizeTranscriptionProviderName(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return "groq";
        }
        return switch (providerName.trim().toLowerCase(Locale.ROOT)) {
            case "openai_whisper", "whisper" -> "openai";
            case "groq_whisper" -> "groq";
            default -> providerName.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String emptyIfBlank(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private void validateAllowFrom() {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            List<String> allow = entry.getValue().getAllowFrom();
            if (allow != null && allow.isEmpty()) {
                log.warn(
                        "渠道 {} 配置无效：allowFrom 为空（等价于拒绝所有）。如需允许所有人，请设置为 [\"*\"]；或添加具体的用户 ID。",
                        entry.getKey()
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
            log.error("启动渠道 {} 失败", name, e);
        }
    }

    public void startAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        dispatchFuture = executor.submit(this::dispatchOutboundLoop);

        if (channels.isEmpty()) {
            log.warn("未启用任何渠道");
            return;
        }

        for (Map.Entry<String, BaseChannel> entry : channels.entrySet()) {
            String name = entry.getKey();
            BaseChannel channel = entry.getValue();
            log.info("正在启动渠道 {}…", name);
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
                log.info("已停止渠道 {}", entry.getKey());
            } catch (Exception e) {
                log.warn("停止渠道 {} 时出错", entry.getKey(), e);
            }
        }

        executor.shutdownNow();
    }

    private void notifyRestartDoneIfNeeded() {
        RestartNotice notice = RestartUtils.consumeRestartNoticeFromEnv();
        if (notice == null) {
            return;
        }

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
                OutboundMessage msg = !pending.isEmpty() ? pending.remove(0) : bus.pollOutbound(1000);
                if (msg == null) {
                    continue;
                }

                Map<String, Object> metadata = messageMetadata(msg);
                if (shouldSkipProgressMessage(metadata)) {
                    continue;
                }

                if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                        && !Boolean.TRUE.equals(metadata.get("_stream_end"))) {
                    CoalesceResult merged = coalesceStreamDeltas(msg);
                    msg = merged.message();
                    pending.addAll(merged.extraPending());
                }

                BaseChannel channel = channels.get(msg.getChannel());
                if (channel == null) {
                    log.warn("未知渠道：{}", msg.getChannel());
                    continue;
                }
                sendWithRetry(channel, msg);
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.error("出站分发器错误", e);
            }
        }
    }

    private Map<String, Object> messageMetadata(OutboundMessage msg) {
        return msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap();
    }

    private boolean shouldSkipProgressMessage(Map<String, Object> metadata) {
        if (!Boolean.TRUE.equals(metadata.get("_progress"))) {
            return false;
        }
        boolean toolHint = Boolean.TRUE.equals(metadata.get("_tool_hint"));
        return toolHint ? !config.getChannels().isSendToolHints() : !config.getChannels().isSendProgress();
    }

    private void sendOnce(BaseChannel channel, OutboundMessage msg) throws Exception {
        Map<String, Object> metadata = messageMetadata(msg);
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
                    log.error("发送到 {} 失败（已重试 {} 次）", msg.getChannel(), maxAttempts, e);
                    return;
                }

                int delay = SEND_RETRY_DELAYS_MS[Math.min(attempt, SEND_RETRY_DELAYS_MS.length - 1)];
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
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

            Map<String, Object> metadata = messageMetadata(next);
            boolean sameTarget = Objects.equals(targetChannel, next.getChannel())
                    && Objects.equals(targetChatId, next.getChatId());
            boolean isDelta = Boolean.TRUE.equals(metadata.get("_stream_delta"));
            boolean isEnd = Boolean.TRUE.equals(metadata.get("_stream_end"));

            if (sameTarget && isDelta) {
                combined.append(next.getContent() != null ? next.getContent() : "");
                if (isEnd) {
                    Map<String, Object> mergedMeta = new HashMap<>(messageMetadata(first));
                    mergedMeta.put("_stream_end", true);
                    return new CoalesceResult(mergedMessage(first, combined.toString(), mergedMeta), extraPending);
                }
                continue;
            }

            extraPending.add(next);
            break;
        }

        return new CoalesceResult(mergedMessage(first, combined.toString(), first.getMetadata()), extraPending);
    }

    private OutboundMessage mergedMessage(OutboundMessage source, String content, Map<String, Object> metadata) {
        OutboundMessage merged = new OutboundMessage();
        merged.setChannel(source.getChannel());
        merged.setChatId(source.getChatId());
        merged.setContent(content);
        merged.setMetadata(metadata);
        return merged;
    }

    BaseChannel getChannel(String name) {
        return channels.get(name);
    }

    Set<String> channelNames() {
        return Set.copyOf(channels.keySet());
    }

    private record CoalesceResult(OutboundMessage message, List<OutboundMessage> extraPending) {
    }

    private record TranscriptionSettings(String providerName, String apiKey, String apiBase) {
    }
}
