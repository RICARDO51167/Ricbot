package ricbot.transport.channel;

import org.junit.jupiter.api.Test;
import ricbot.domain.message.MessageBus;
import ricbot.integration.channel.WebSocketChannel;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketChannelTest {

    @Test
    void testWebSocketChannelInitialization() throws Exception {
        // Manual mock instead of Mockito to avoid Java 25 compatibility issues
        MessageBus bus = new MessageBus();
        WebSocketChannel.WebSocketConfig config = new WebSocketChannel.WebSocketConfig();
        config.setEnabled(true);
        config.setPort(0); // Random port
        config.setPath("/ws");

        final boolean[] startCalled = {false};
        final boolean[] stopCalled = {false};

        WebSocketChannel.WsServer mockServer = new WebSocketChannel.WsServer() {
            @Override
            public void start(String host, int port, String path, WebSocketChannel.WsServerListener listener) {
                startCalled[0] = true;
                assertEquals("127.0.0.1", host);
                assertEquals("/ws", path);
            }

            @Override
            public void stop() {
                stopCalled[0] = true;
            }
        };
        
        WebSocketChannel channel = new WebSocketChannel(config, bus);
        channel.setServer(mockServer);
        
        channel.start();
        assertTrue(startCalled[0], "Server.start should be called");
        
        channel.stop();
        assertTrue(stopCalled[0], "Server.stop should be called");
    }

    @Test
    void startStop_areIdempotent() throws Exception {
        MessageBus bus = new MessageBus();
        WebSocketChannel.WebSocketConfig config = new WebSocketChannel.WebSocketConfig();
        config.setEnabled(true);
        config.setPort(0);
        config.setPath("ws");

        final int[] startCount = {0};
        final int[] stopCount = {0};

        WebSocketChannel.WsServer mockServer = new WebSocketChannel.WsServer() {
            @Override
            public void start(String host, int port, String path, WebSocketChannel.WsServerListener listener) {
                startCount[0]++;
                assertEquals("/ws", path);
            }

            @Override
            public void stop() {
                stopCount[0]++;
            }
        };

        WebSocketChannel channel = new WebSocketChannel(config, bus);
        channel.setServer(mockServer);

        channel.start();
        channel.start();
        assertEquals(1, startCount[0]);

        channel.stop();
        channel.stop();
        assertEquals(1, stopCount[0]);
    }
}
