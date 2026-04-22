package ricbot.integration.channel;

import org.junit.jupiter.api.Test;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelManagerTest {

    @Test
    void initChannels_injectsTranscriptionSettingsAndRemovesDenyAllChannels() {
        Config config = new Config();
        config.getChannels().setTranscriptionProvider("openai_whisper");
        config.getProviders().getOpenai().setApiKey("openai-key");
        config.getProviders().getOpenai().setApiBase("https://openai.example.com");

        config.getChannels().getWebsocket().setEnabled(true);
        config.getChannels().getWebsocket().setAllowFrom(List.of("*"));

        config.getChannels().getQq().setEnabled(true);
        config.getChannels().getQq().setAllowFrom(List.of());

        Map<String, Class<? extends BaseChannel>> discovered = new LinkedHashMap<>();
        discovered.put("websocket", RecordingWebSocketChannel.class);
        discovered.put("qq", DenyAllQqChannel.class);

        ChannelManager manager = new ChannelManager(config, new MessageBus(), discovered);

        assertEquals(Set.of("websocket"), manager.channelNames());
        RecordingWebSocketChannel websocket = (RecordingWebSocketChannel) manager.getChannel("websocket");
        assertNotNull(websocket);
        assertEquals("openai", websocket.recordedProviderName());
        assertEquals("openai-key", websocket.recordedApiKey());
        assertEquals("https://openai.example.com", websocket.recordedApiBase());
        assertNull(manager.getChannel("qq"));
    }

    @Test
    void dispatchLoop_skipsProgressMessagesWhenDisabled() throws Exception {
        Config config = new Config();
        config.getChannels().setSendProgress(false);
        config.getChannels().setSendToolHints(false);
        config.getChannels().getWebsocket().setEnabled(true);
        config.getChannels().getWebsocket().setAllowFrom(List.of("*"));

        Map<String, Class<? extends BaseChannel>> discovered = Map.of(
                "websocket", RecordingWebSocketChannel.class
        );

        MessageBus bus = new MessageBus();
        ChannelManager manager = new ChannelManager(config, bus, discovered);
        RecordingWebSocketChannel channel = (RecordingWebSocketChannel) manager.getChannel("websocket");
        assertNotNull(channel);
        channel.expectSends(1);

        manager.startAll();
        try {
            bus.sendOutbound(outbound("websocket", "c1", "progress", Map.of("_progress", true)));
            bus.sendOutbound(outbound("websocket", "c1", "tool hint", Map.of("_progress", true, "_tool_hint", true)));
            bus.sendOutbound(outbound("websocket", "c1", "visible", Map.of()));

            assertTrue(channel.awaitSend(2), "regular outbound message should be delivered");
            Thread.sleep(150);

            assertEquals(1, channel.sendCount());
            assertEquals("visible", channel.lastSentContent());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void dispatchLoop_coalescesStreamDeltas() throws Exception {
        Config config = new Config();
        config.getChannels().getWebsocket().setEnabled(true);
        config.getChannels().getWebsocket().setAllowFrom(List.of("*"));

        Map<String, Class<? extends BaseChannel>> discovered = Map.of(
                "websocket", RecordingWebSocketChannel.class
        );

        MessageBus bus = new MessageBus();
        ChannelManager manager = new ChannelManager(config, bus, discovered);
        RecordingWebSocketChannel channel = (RecordingWebSocketChannel) manager.getChannel("websocket");
        assertNotNull(channel);
        channel.expectDeltas(1);

        manager.startAll();
        try {
            bus.sendOutbound(outbound("websocket", "c1", "hel", Map.of("_stream_delta", true)));
            bus.sendOutbound(outbound("websocket", "c1", "lo ", Map.of("_stream_delta", true)));
            bus.sendOutbound(outbound("websocket", "c1", "world", Map.of("_stream_delta", true, "_stream_end", true)));

            assertTrue(channel.awaitDelta(2), "merged delta should be delivered");
            assertEquals(1, channel.deltaCount());
            assertEquals("hello world", channel.lastDeltaContent());
            assertTrue(Boolean.TRUE.equals(channel.lastDeltaMetadata().get("_stream_end")));
        } finally {
            manager.stopAll();
        }
    }

    private static OutboundMessage outbound(String channel, String chatId, String content, Map<String, Object> metadata) {
        OutboundMessage msg = new OutboundMessage();
        msg.setChannel(channel);
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setMetadata(metadata);
        return msg;
    }

    static class RecordingWebSocketChannel extends BaseChannel {
        private final List<String> allowFrom;
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger deltaCount = new AtomicInteger();
        private volatile CountDownLatch sendLatch = new CountDownLatch(0);
        private volatile CountDownLatch deltaLatch = new CountDownLatch(0);
        private volatile String lastSentContent;
        private volatile String lastDeltaContent;
        private volatile Map<String, Object> lastDeltaMetadata = Map.of();

        RecordingWebSocketChannel(Object config, MessageBus bus) {
            super(config, bus);
            this.name = "websocket";
            this.allowFrom = config instanceof WebSocketChannel.WebSocketConfig ws
                    ? ws.getAllowFrom()
                    : List.of("*");
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public List<String> getAllowFrom() {
            return allowFrom;
        }

        @Override
        public void send(OutboundMessage msg) {
            lastSentContent = msg.getContent();
            sendCount.incrementAndGet();
            sendLatch.countDown();
        }

        @Override
        public void sendDelta(String chatId, String delta, Map<String, Object> metadata) {
            lastDeltaContent = delta;
            lastDeltaMetadata = metadata != null ? new LinkedHashMap<>(metadata) : Map.of();
            deltaCount.incrementAndGet();
            deltaLatch.countDown();
        }

        void expectSends(int expected) {
            sendLatch = new CountDownLatch(expected);
            lastSentContent = null;
            sendCount.set(0);
        }

        void expectDeltas(int expected) {
            deltaLatch = new CountDownLatch(expected);
            lastDeltaContent = null;
            lastDeltaMetadata = Map.of();
            deltaCount.set(0);
        }

        boolean awaitSend(int timeoutSeconds) throws InterruptedException {
            return sendLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        boolean awaitDelta(int timeoutSeconds) throws InterruptedException {
            return deltaLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        int sendCount() {
            return sendCount.get();
        }

        int deltaCount() {
            return deltaCount.get();
        }

        String lastSentContent() {
            return lastSentContent;
        }

        String lastDeltaContent() {
            return lastDeltaContent;
        }

        Map<String, Object> lastDeltaMetadata() {
            return lastDeltaMetadata;
        }

        String recordedProviderName() {
            return transcriptionProvider;
        }

        String recordedApiKey() {
            return transcriptionApiKey;
        }

        String recordedApiBase() {
            return transcriptionApiBase;
        }
    }

    static class DenyAllQqChannel extends BaseChannel {
        private final List<String> allowFrom;

        DenyAllQqChannel(Object config, MessageBus bus) {
            super(config, bus);
            this.name = "qq";
            this.allowFrom = config instanceof QQChannel.QQConfig qq
                    ? qq.getAllowFrom()
                    : List.of();
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public List<String> getAllowFrom() {
            return allowFrom;
        }

        @Override
        public void send(OutboundMessage msg) {
        }
    }
}
