package ricbot.integration.channel;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class QQChannelTest {

    @Test
    void localMedia_isRestrictedToWhitelistedRoots(@TempDir Path workspace) throws Exception {
        QQChannel.QQConfig config = new QQChannel.QQConfig();
        Path mediaDir = workspace.resolve("media");
        config.setMediaDir(mediaDir.toString());

        QQChannel channel = new QQChannel(config, new MessageBus());

        Files.createDirectories(mediaDir);
        Path allowed = mediaDir.resolve("allowed.png");
        Files.writeString(allowed, "ok");

        Path outside = workspace.resolveSibling("outside.png");
        Files.writeString(outside, "nope");

        assertEquals(allowed.toAbsolutePath().normalize(), channel.resolveLocalMediaPath("allowed.png"));
        assertTrue(channel.isAllowedLocalMediaPath(allowed));
        assertFalse(channel.isAllowedLocalMediaPath(outside));
        assertFalse(channel.validateRemoteMediaUrl("http://127.0.0.1/private").ok());
        assertTrue(channel.validateRemoteMediaUrl("https://8.8.8.8/image.png").ok());
    }

    @Test
    void onMessage_downloadsAttachmentsAndPublishesInboundMessage(@TempDir Path workspace) throws Exception {
        QQChannel.QQConfig config = new QQChannel.QQConfig();
        config.setAckMessage("");
        config.setMediaDir(workspace.resolve("media").toString());

        MessageBus bus = new MessageBus();
        QQChannel channel = new QQChannel(config, bus);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/attachment.png", exchange -> {
            byte[] body = "png-bytes".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/attachment.png";
            channel.onMessage(new TestInboundMessage(
                    "msg-1",
                    "hello",
                    true,
                    "group-1",
                    "user-1",
                    List.of(new QQChannel.QQAttachment(url, "sample.png", "image/png"))
            ));

            InboundMessage inbound = bus.consumeInbound(1, TimeUnit.SECONDS);
            assertNotNull(inbound);
            assertEquals("qq", inbound.getChannel());
            assertEquals("user-1", inbound.getSenderId());
            assertEquals("group-1", inbound.getChatId());
            assertTrue(inbound.getContent().contains("hello"));
            assertTrue(inbound.getContent().contains("收到文件"));
            assertEquals(1, inbound.getMedia().size());
            assertTrue(Files.isRegularFile(Path.of(inbound.getMedia().get(0))));

            Object rawAttachments = inbound.getMetadata().get("attachments");
            assertInstanceOf(List.class, rawAttachments);
            List<?> attachments = (List<?>) rawAttachments;
            assertEquals(1, attachments.size());
            assertInstanceOf(Map.class, attachments.get(0));
            Map<?, ?> attachmentMeta = (Map<?, ?>) attachments.get(0);
            assertEquals("sample.png", attachmentMeta.get("filename"));
            assertNotNull(attachmentMeta.get("saved_path"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void send_uploadsAllowedLocalMediaAndSkipsOutsidePaths(@TempDir Path workspace) throws Exception {
        QQChannel.QQConfig config = new QQChannel.QQConfig();
        Path mediaDir = workspace.resolve("media");
        config.setMediaDir(mediaDir.toString());

        MessageBus bus = new MessageBus();
        QQChannel channel = new QQChannel(config, bus);
        RecordingQQBotClient client = new RecordingQQBotClient();
        channel.setClient(client);

        Files.createDirectories(mediaDir);
        Path allowed = mediaDir.resolve("allowed.png");
        Files.writeString(allowed, "ok");

        Path outside = workspace.resolveSibling("outside.png");
        Files.writeString(outside, "nope");

        OutboundMessage allowedMessage = new OutboundMessage("qq", "chat-1", "");
        allowedMessage.setMedia(List.of("allowed.png"));
        channel.send(allowedMessage);

        OutboundMessage rejectedMessage = new OutboundMessage("qq", "chat-1", "");
        rejectedMessage.setMedia(List.of(outside.toString()));
        channel.send(rejectedMessage);

        assertEquals(1, client.uploadCount);
        assertEquals(1, client.mediaSendCount);
        assertEquals(QQChannel.QQ_FILE_TYPE_IMAGE, client.lastFileType);
    }

    private record TestInboundMessage(
            String id,
            String content,
            boolean isGroup,
            String chatId,
            String userId,
            List<QQChannel.QQAttachment> attachments
    ) implements QQChannel.QQInboundMessage {
    }

    private static final class RecordingQQBotClient implements QQChannel.QQBotClient {
        private int uploadCount;
        private int mediaSendCount;
        private int lastFileType;

        @Override
        public void start(String appId, String secret, QQChannel.QQEventListener listener) {
        }

        @Override
        public void close() {
        }

        @Override
        public Object uploadFile(String chatId, boolean isGroup, int fileType, String base64Data, String fileName) {
            uploadCount++;
            lastFileType = fileType;
            return "payload-" + uploadCount;
        }

        @Override
        public void sendText(String chatId, boolean isGroup, String msgId, String content, String format) {
        }

        @Override
        public void sendMediaText(String chatId, boolean isGroup, String msgId, Object mediaPayload) {
            mediaSendCount++;
        }
    }
}
