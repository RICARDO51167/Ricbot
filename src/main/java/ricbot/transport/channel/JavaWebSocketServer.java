package ricbot.transport.channel;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Java-WebSocket 库的 WebSocket 服务端实现。
 */
public class JavaWebSocketServer extends WebSocketServer implements WebSocketChannel.WsServer {

    private WebSocketChannel.WsServerListener listener;
    private final Map<WebSocket, WebSocketChannel.WsConnection> connMap = new ConcurrentHashMap<>();

    public JavaWebSocketServer(InetSocketAddress address) {
        super(address);
    }

    @Override
    public void start(String host, int port, String path, WebSocketChannel.WsServerListener listener) throws Exception {
        this.listener = listener;
        this.start();
    }

    @Override
    public void stop() throws InterruptedException {
        super.stop(5000);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor();
        Map<String, String> headers = new HashMap<>();
        handshake.iterateHttpFields().forEachRemaining(key -> headers.put(key, handshake.getFieldValue(key)));

        WebSocketChannel.WsConnection connection = new WebSocketChannel.WsConnection() {
            private final String id = java.util.UUID.randomUUID().toString();
            @Override public String id() { return id; }
            @Override public String clientId() { return null; } 
            @Override public void sendText(String text) throws Exception { conn.send(text); }
            @Override public void close(int code, String reason) throws Exception { conn.close(code, reason); }
        };

        connMap.put(conn, connection);
        if (listener != null) {
            listener.onOpen(connection, path, headers);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        WebSocketChannel.WsConnection connection = connMap.remove(conn);
        if (connection != null && listener != null) {
            listener.onClose(connection, code, reason);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        WebSocketChannel.WsConnection connection = connMap.get(conn);
        if (connection != null && listener != null) {
            listener.onMessage(connection, message);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("WebSocket Error: " + (ex != null ? ex.getMessage() : "Unknown error"));
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket Server started on " + getAddress());
    }
}
