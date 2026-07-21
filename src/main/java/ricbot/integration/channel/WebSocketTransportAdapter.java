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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle and MessageBus adapter for the single supported WebSocket transport. */
@Slf4j
public final class WebSocketTransportAdapter {
    private static final int[] SEND_RETRY_DELAYS_MS = {1000, 2000, 4000};
    private static final int STREAM_DELTA_COALESCE_WAIT_MS = 25;

    private final Config config;
    private final MessageBus bus;
    private final WebSocketChannel transport;
    private final ExecutorService executor = createExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Future<?> dispatchFuture;

    public WebSocketTransportAdapter(Config config, MessageBus bus) {
        this(config, bus, createTransport(config, bus));
    }

    WebSocketTransportAdapter(Config config, MessageBus bus, WebSocketChannel transport) {
        this.config = config != null ? config : new Config();
        this.bus = Objects.requireNonNull(bus, "bus");
        this.transport = transport;
    }

    private static WebSocketChannel createTransport(Config rawConfig, MessageBus bus) {
        Config config = rawConfig != null ? rawConfig : new Config();
        WebSocketChannel.WebSocketConfig websocket = config.getChannels().getWebsocket();
        if (websocket == null || !websocket.isEnabled()) {
            return null;
        }
        List<String> allowFrom = websocket.getAllowFrom();
        if (allowFrom != null && allowFrom.isEmpty()) {
            log.warn("WebSocket transport 已禁用：allowFrom 为空；允许所有来源请配置 [\"*\"]");
            return null;
        }
        return new WebSocketChannel(websocket, bus);
    }

    private static ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "websocket-transport");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void start() {
        if (transport == null) {
            log.info("WebSocket transport 未启用");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        dispatchFuture = executor.submit(this::dispatchOutboundLoop);
        executor.submit(() -> {
            try {
                transport.start();
                log.info("WebSocket transport 已启动");
            } catch (Exception e) {
                log.error("启动 WebSocket transport 失败", e);
            }
        });
        notifyRestartDoneIfNeeded();
    }

    public void stop() {
        running.set(false);
        if (dispatchFuture != null) {
            dispatchFuture.cancel(true);
        }
        if (transport != null) {
            try {
                transport.stop();
            } catch (Exception e) {
                log.warn("停止 WebSocket transport 时出错", e);
            }
        }
        executor.shutdownNow();
    }

    public boolean isEnabled() {
        return transport != null;
    }

    WebSocketChannel transport() {
        return transport;
    }

    private void notifyRestartDoneIfNeeded() {
        RestartNotice notice = RestartUtils.consumeRestartNoticeFromEnv();
        if (notice == null) {
            return;
        }
        OutboundMessage message = new OutboundMessage();
        message.setChannel(notice.channel());
        message.setChatId(notice.chatId());
        message.setContent(RestartUtils.formatRestartCompletedMessage(notice.startedAtRaw()));
        bus.sendOutbound(message);
    }

    private void dispatchOutboundLoop() {
        List<OutboundMessage> pending = new LinkedList<>();
        while (running.get()) {
            try {
                OutboundMessage message = !pending.isEmpty() ? pending.remove(0) : bus.pollOutbound(1000);
                if (message == null) {
                    continue;
                }
                if (!"websocket".equals(message.getChannel())) {
                    log.warn("WebSocket adapter 忽略非 WebSocket 出站消息：{}", message.getChannel());
                    continue;
                }

                Map<String, Object> metadata = messageMetadata(message);
                if (shouldSkipProgressMessage(metadata)) {
                    continue;
                }
                if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                        && !Boolean.TRUE.equals(metadata.get("_stream_end"))) {
                    CoalesceResult merged = coalesceStreamDeltas(message);
                    message = merged.message();
                    pending.addAll(merged.extraPending());
                }
                sendWithRetry(message);
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.error("WebSocket 出站分发失败", e);
            }
        }
    }

    private Map<String, Object> messageMetadata(OutboundMessage message) {
        return message.getMetadata() != null ? message.getMetadata() : Collections.emptyMap();
    }

    private boolean shouldSkipProgressMessage(Map<String, Object> metadata) {
        if (!Boolean.TRUE.equals(metadata.get("_progress"))) {
            return false;
        }
        boolean toolHint = Boolean.TRUE.equals(metadata.get("_tool_hint"));
        return toolHint ? !config.getChannels().isSendToolHints() : !config.getChannels().isSendProgress();
    }

    private void sendOnce(OutboundMessage message) throws Exception {
        Map<String, Object> metadata = messageMetadata(message);
        if (Boolean.TRUE.equals(metadata.get("_stream_delta"))
                || Boolean.TRUE.equals(metadata.get("_stream_end"))) {
            transport.sendDelta(message.getChatId(), message.getContent(), message.getMetadata());
        } else if (!Boolean.TRUE.equals(metadata.get("_streamed"))) {
            transport.send(message);
        }
    }

    private void sendWithRetry(OutboundMessage message) {
        int maxAttempts = Math.max(config.getChannels().getSendMaxRetries(), 1);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                sendOnce(message);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts - 1) {
                    log.error("WebSocket 发送失败（尝试 {} 次）", maxAttempts, e);
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
        String targetChatId = first.getChatId();
        StringBuilder combined = new StringBuilder(first.getContent() != null ? first.getContent() : "");
        List<OutboundMessage> extraPending = new ArrayList<>();

        while (true) {
            OutboundMessage next = bus.pollOutbound(STREAM_DELTA_COALESCE_WAIT_MS);
            if (next == null) {
                break;
            }
            Map<String, Object> metadata = messageMetadata(next);
            boolean sameTarget = "websocket".equals(next.getChannel())
                    && Objects.equals(targetChatId, next.getChatId());
            boolean isDelta = Boolean.TRUE.equals(metadata.get("_stream_delta"));
            boolean isEnd = Boolean.TRUE.equals(metadata.get("_stream_end"));
            if (sameTarget && isDelta) {
                combined.append(next.getContent() != null ? next.getContent() : "");
                if (isEnd) {
                    Map<String, Object> mergedMetadata = new HashMap<>(messageMetadata(first));
                    mergedMetadata.put("_stream_end", true);
                    return new CoalesceResult(
                            mergedMessage(first, combined.toString(), mergedMetadata), extraPending
                    );
                }
                continue;
            }
            extraPending.add(next);
            break;
        }
        return new CoalesceResult(
                mergedMessage(first, combined.toString(), first.getMetadata()), extraPending
        );
    }

    private static OutboundMessage mergedMessage(
            OutboundMessage source,
            String content,
            Map<String, Object> metadata
    ) {
        OutboundMessage merged = new OutboundMessage();
        merged.setChannel("websocket");
        merged.setChatId(source.getChatId());
        merged.setContent(content);
        merged.setMetadata(metadata);
        return merged;
    }

    private record CoalesceResult(OutboundMessage message, List<OutboundMessage> extraPending) {
    }
}
