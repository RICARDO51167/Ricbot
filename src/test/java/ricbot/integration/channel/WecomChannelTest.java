package ricbot.integration.channel;

import org.junit.jupiter.api.Test;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WecomChannelTest {

    @Test
    void start_registersListenerAndDeduplicatesInboundMessages() throws Exception {
        WecomChannel.WecomConfig config = new WecomChannel.WecomConfig();
        config.setBotId("bot-1");
        config.setSecret("secret-1");

        MessageBus bus = new MessageBus();
        WecomChannel channel = new WecomChannel(config, bus);
        RecordingWecomClient client = new RecordingWecomClient();
        channel.setClient(client);

        channel.start();

        assertNotNull(client.listener);

        Map<String, Object> frame = new HashMap<>();
        frame.put("msgid", "msg-1");
        frame.put("from_userid", "user-1");
        frame.put("content", "hello");

        client.listener.onTextMessage(frame);

        InboundMessage inbound = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(inbound);
        assertEquals("wecom", inbound.getChannel());
        assertEquals("user-1", inbound.getSenderId());
        assertEquals("user-1", inbound.getChatId());
        assertEquals("hello", inbound.getContent());
        assertEquals("text", inbound.getMetadata().get("wecom_msg_type"));

        client.listener.onTextMessage(frame);
        assertNull(bus.consumeInbound(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void enterChat_sendsWelcomeMessage() throws Exception {
        WecomChannel.WecomConfig config = new WecomChannel.WecomConfig();
        config.setBotId("bot-1");
        config.setSecret("secret-1");
        config.setWelcomeMessage("welcome");

        MessageBus bus = new MessageBus();
        WecomChannel channel = new WecomChannel(config, bus);
        RecordingWecomClient client = new RecordingWecomClient();
        channel.setClient(client);

        channel.start();
        client.listener.onEnterChat(Map.of("from_userid", "user-9"));

        assertEquals(List.of("user-9|welcome"), client.sentTexts);
    }

    private static final class RecordingWecomClient implements WecomChannel.WecomClient {
        private WecomChannel.WecomListener listener;
        private final List<String> sentTexts = new ArrayList<>();

        @Override
        public void connect(WecomChannel.WecomListener listener) {
            this.listener = listener;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public void sendText(String chatId, String content, Object frameHeaders) {
            sentTexts.add(chatId + "|" + content);
        }

        @Override
        public void sendMedia(String chatId, String mediaType, String filePath, Object frameHeaders) {
        }
    }
}
