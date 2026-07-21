package ricbot.integration.channel;

import org.junit.jupiter.api.Test;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTransportAdapterTest {

    @Test
    void createsOnlyTheConfiguredWebSocketTransport() {
        Config disabled = new Config();
        assertFalse(new WebSocketTransportAdapter(disabled, new MessageBus()).isEnabled());

        Config enabled = enabledConfig();
        assertTrue(new WebSocketTransportAdapter(enabled, new MessageBus()).isEnabled());

        enabled.getChannels().getWebsocket().setAllowFrom(List.of());
        assertFalse(new WebSocketTransportAdapter(enabled, new MessageBus()).isEnabled());
    }

    @Test
    void dispatchesOnlyWebSocketMessages() throws Exception {
        Config config = enabledConfig();
        MessageBus bus = new MessageBus();
        RecordingWebSocketChannel transport = new RecordingWebSocketChannel(config, bus, 1);
        WebSocketTransportAdapter adapter = new WebSocketTransportAdapter(config, bus, transport);

        bus.sendOutbound(outbound("cli", "ignored", Map.of()));
        bus.sendOutbound(outbound("websocket", "delivered", Map.of()));
        adapter.start();
        try {
            assertTrue(transport.await(2));
            assertEquals(List.of("delivered"), transport.messages);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void coalescesStreamingDeltasForOneConnection() throws Exception {
        Config config = enabledConfig();
        MessageBus bus = new MessageBus();
        RecordingWebSocketChannel transport = new RecordingWebSocketChannel(config, bus, 1);
        WebSocketTransportAdapter adapter = new WebSocketTransportAdapter(config, bus, transport);

        bus.sendOutbound(outbound("websocket", "a", Map.of("_stream_delta", true)));
        bus.sendOutbound(outbound("websocket", "b", Map.of("_stream_delta", true)));
        bus.sendOutbound(outbound("websocket", "c", Map.of("_stream_delta", true, "_stream_end", true)));
        adapter.start();
        try {
            assertTrue(transport.await(2));
            assertEquals(List.of("abc"), transport.deltas);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void disabledProgressMessagesDoNotReachTransport() throws Exception {
        Config config = enabledConfig();
        config.getChannels().setSendProgress(false);
        MessageBus bus = new MessageBus();
        RecordingWebSocketChannel transport = new RecordingWebSocketChannel(config, bus, 1);
        WebSocketTransportAdapter adapter = new WebSocketTransportAdapter(config, bus, transport);

        bus.sendOutbound(outbound("websocket", "progress", Map.of("_progress", true)));
        adapter.start();
        try {
            assertFalse(transport.await(1));
            assertTrue(transport.messages.isEmpty());
        } finally {
            adapter.stop();
        }
    }

    private static Config enabledConfig() {
        Config config = new Config();
        config.getChannels().getWebsocket().setEnabled(true);
        config.getChannels().getWebsocket().setAllowFrom(List.of("*"));
        return config;
    }

    private static OutboundMessage outbound(String channel, String content, Map<String, Object> metadata) {
        OutboundMessage message = new OutboundMessage();
        message.setChannel(channel);
        message.setChatId("client");
        message.setContent(content);
        message.setMetadata(metadata);
        return message;
    }

    private static final class RecordingWebSocketChannel extends WebSocketChannel {
        private final CountDownLatch sent;
        private final List<String> messages = new ArrayList<>();
        private final List<String> deltas = new ArrayList<>();

        private RecordingWebSocketChannel(Config config, MessageBus bus, int expectedSends) {
            super(config.getChannels().getWebsocket(), bus);
            this.sent = new CountDownLatch(expectedSends);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void send(OutboundMessage message) {
            messages.add(message.getContent());
            sent.countDown();
        }

        @Override
        public void sendDelta(String chatId, String delta, Map<String, Object> metadata) {
            deltas.add(delta);
            sent.countDown();
        }

        private boolean await(int seconds) throws InterruptedException {
            return sent.await(seconds, TimeUnit.SECONDS);
        }
    }
}
