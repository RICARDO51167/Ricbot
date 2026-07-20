package ricbot.integration.channel;

import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket; // 导入 WebSocket 接口，代表一个客户端连接
import org.java_websocket.handshake.ClientHandshake; // 导入客户端握手信息类，用于获取握手时的详细信息
import org.java_websocket.server.WebSocketServer; // 导入 WebSocketServer 基类，提供服务端基础功能

import java.net.InetSocketAddress; // 导入网络地址套接字类，用于指定服务绑定的 IP 和端口
import java.util.HashMap; // 导入 HashMap，用于存储非线程安全的临时数据（如握手头信息）
import java.util.Map; // 导入 Map 接口
import java.util.concurrent.ConcurrentHashMap; // 导入 ConcurrentHashMap，用于线程安全地存储连接映射

/**
 * 基于 Java-WebSocket 库的 WebSocket 服务端实现。
 */
@Slf4j
public class JavaWebSocketServer extends WebSocketServer implements WebSocketChannel.WsServer {

    private WebSocketChannel.WsServerListener listener; // 声明服务端事件监听器，用于回调处理连接事件
    private final Map<WebSocket, WebSocketChannel.WsConnection> connMap = new ConcurrentHashMap<>(); // 声明线程安全的连接映射表，key 为底层 WebSocket 对象，value 为封装后的业务连接对象

    public JavaWebSocketServer(InetSocketAddress address) {
        super(address); // 调用父类构造函数，初始化服务端绑定的网络地址
    }

    @Override
    public void start(String host, int port, String path, WebSocketChannel.WsServerListener listener) throws Exception {
        this.listener = listener; // 保存传入的事件监听器实例
        this.start(); // 启动 WebSocket 服务端，开始监听连接
    }

    @Override
    public void stop() throws InterruptedException {
        super.stop(5000); // 停止服务端，设置超时时间为 5000 毫秒，确保资源优雅释放
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor(); // 从握手信息中获取请求的资源路径（URI）
        Map<String, String> headers = new HashMap<>(); // 创建 HashMap 用于存储 HTTP 请求头
        handshake.iterateHttpFields().forEachRemaining(key -> headers.put(key, handshake.getFieldValue(key))); // 遍历握手中的所有 HTTP 字段，并放入 headers 地图中

        // 创建匿名内部类实现 WsConnection 接口，封装底层 WebSocket 连接
        WebSocketChannel.WsConnection connection = new WebSocketChannel.WsConnection() {
            private final String id = java.util.UUID.randomUUID().toString(); // 生成唯一的连接 ID
            @Override public String id() { return id; } // 返回连接唯一标识
            @Override public String clientId() { return null; } // 返回客户端 ID，当前未实现，返回 null
            @Override public void sendText(String text) throws Exception { conn.send(text); } // 代理发送文本消息到底层 WebSocket 连接
            @Override public void close(int code, String reason) throws Exception { conn.close(code, reason); } // 代理关闭连接到底层 WebSocket 连接
        };

        connMap.put(conn, connection); // 将底层 WebSocket 对象与封装后的业务连接对象存入映射表
        if (listener != null) {
            listener.onOpen(connection, path, headers); // 如果监听器存在，触发连接打开事件，传递连接对象、路径和请求头
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        WebSocketChannel.WsConnection connection = connMap.remove(conn); // 从映射表中移除并获取对应的业务连接对象
        if (connection != null && listener != null) {
            listener.onClose(connection, code, reason); // 如果连接对象和监听器都存在，触发连接关闭事件
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        WebSocketChannel.WsConnection connection = connMap.get(conn); // 根据底层 WebSocket 对象获取对应的业务连接对象
        if (connection != null && listener != null) {
            listener.onMessage(connection, message); // 如果连接对象和监听器都存在，触发消息接收事件
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("WebSocket 错误: {}", ex != null ? ex.getMessage() : "未知错误", ex);
    }

    @Override
    public void onStart() {
        log.info("WebSocket 服务已启动: {}", getAddress());
    }
}
