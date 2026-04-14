package ricbot.transport.channel;

import org.junit.jupiter.api.Test;
import ricbot.core.message.MessageBus;
import ricbot.infra.config.Config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
}
