package ricbot.transport.channel;

import org.junit.jupiter.api.Test;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.integration.channel.WebSocketChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @Test
    void websocketConfig_normalizesConfiguredPaths() {
        WebSocketChannel.WebSocketConfig config = new WebSocketChannel.WebSocketConfig();
        config.setPath("ws/");
        config.setTokenIssuePath("issue/");

        assertEquals("/ws", config.getPath());
        assertEquals("/issue", config.getTokenIssuePath());
    }

    @Test
    void issuedToken_canOpenConnectionAndPublishInboundEvents() throws Exception {
        MessageBus bus = new MessageBus();
        WebSocketChannel.WebSocketConfig config = new WebSocketChannel.WebSocketConfig();
        config.setEnabled(true);
        config.setPort(0);
        config.setPath("/ws");
        config.setTokenIssuePath("/issue");
        config.setTokenIssueSecret("secret");

        CapturingServer server = new CapturingServer();
        WebSocketChannel channel = new WebSocketChannel(config, bus);
        channel.setServer(server);
        channel.start();

        WebSocketChannel.HttpResponseData issueResponse = server.listener.onHttpGet(
                "/issue",
                Map.of("Authorization", "Bearer secret")
        );
        assertEquals(200, issueResponse.status);
        assertTrue(issueResponse.body.contains("\"token\""));

        String token = extractJsonValue(issueResponse.body, "token");
        assertNotNull(token);

        RecordingConnection connection = new RecordingConnection("conn-1");
        server.listener.onOpen(connection, "/ws?client_id=client-1&token=" + token, Map.of());
        assertFalse(connection.closed);

        server.listener.onMessage(connection, "{\"content\":\"/ping now\"}");
        InboundMessage commandMessage = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(commandMessage);
        assertEquals("websocket", commandMessage.getChannel());
        assertEquals("client-1", commandMessage.getSenderId());
        assertEquals("/ping now", commandMessage.getContent());
        assertEquals("websocket:client-1", commandMessage.getSessionKey());

        RecordingConnection secondConnection = new RecordingConnection("conn-2");
        server.listener.onOpen(secondConnection, "/ws?client_id=client-2&token=" + token, Map.of());
        assertTrue(secondConnection.closed);
        assertEquals("Token 无效", secondConnection.closeReason);

        server.listener.onMessage(connection, "{\"text\":\"plain text\"}");
        InboundMessage plainMessage = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(plainMessage);
        assertEquals("plain text", plainMessage.getContent());
        assertEquals("client-1", plainMessage.getSenderId());
    }

    @Test
    void open_rejectsWrongPathAndUnauthorizedTokenIssueRequest() throws Exception {
        MessageBus bus = new MessageBus();
        WebSocketChannel.WebSocketConfig config = new WebSocketChannel.WebSocketConfig();
        config.setEnabled(true);
        config.setPort(0);
        config.setPath("/ws");
        config.setToken("fixed-token");
        config.setTokenIssuePath("/issue");
        config.setTokenIssueSecret("secret");

        CapturingServer server = new CapturingServer();
        WebSocketChannel channel = new WebSocketChannel(config, bus);
        channel.setServer(server);
        channel.start();

        WebSocketChannel.HttpResponseData unauthorized = server.listener.onHttpGet("/issue", Map.of());
        assertEquals(401, unauthorized.status);

        RecordingConnection wrongPath = new RecordingConnection("conn-3");
        server.listener.onOpen(wrongPath, "/wrong?client_id=client-3&token=fixed-token", Map.of());
        assertTrue(wrongPath.closed);
        assertEquals("路径无效", wrongPath.closeReason);

        RecordingConnection wrongToken = new RecordingConnection("conn-4");
        server.listener.onOpen(wrongToken, "/ws?client_id=client-4&token=bad-token", Map.of());
        assertTrue(wrongToken.closed);
        assertEquals("Token 无效", wrongToken.closeReason);
    }

    private static String extractJsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return valueEnd > valueStart ? json.substring(valueStart, valueEnd) : null;
    }

    private static final class CapturingServer implements WebSocketChannel.WsServer {
        private WebSocketChannel.WsServerListener listener;

        @Override
        public void start(String host, int port, String path, WebSocketChannel.WsServerListener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
        }
    }

    private static final class RecordingConnection implements WebSocketChannel.WsConnection {
        private final String id;
        private final Map<String, Object> sentPayloads = new HashMap<>();
        private boolean closed;
        private int closeCode;
        private String closeReason;

        private RecordingConnection(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String clientId() {
            return null;
        }

        @Override
        public void sendText(String text) {
            sentPayloads.put("text", text);
        }

        @Override
        public void close(int code, String reason) {
            this.closed = true;
            this.closeCode = code;
            this.closeReason = reason;
        }
    }
}
