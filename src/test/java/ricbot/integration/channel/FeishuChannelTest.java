package ricbot.integration.channel;

import org.junit.jupiter.api.Test;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeishuChannelTest {

    @Test
    void handleWebhookEvent_publishesTextMessage() throws Exception {
        FeishuChannel.FeishuConfig config = new FeishuChannel.FeishuConfig();
        config.setAllowFrom(List.of("*"));
        MessageBus bus = new MessageBus();
        FeishuChannel channel = new FeishuChannel(config, bus);

        channel.handleWebhookEvent(Map.of(
                "header", Map.of("event_id", "evt-1", "event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "user-1")),
                        "message", Map.of(
                                "message_id", "msg-1",
                                "chat_id", "chat-1",
                                "message_type", "text",
                                "content", "{\"text\":\"hello\"}"
                        )
                )
        ));

        InboundMessage inbound = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(inbound);
        assertEquals("feishu", inbound.getChannel());
        assertEquals("user-1", inbound.getSenderId());
        assertEquals("chat-1", inbound.getChatId());
        assertEquals("hello", inbound.getContent());
        assertEquals("msg-1", inbound.getMetadata().get("feishu_message_id"));
    }

    @Test
    void handleWebhookEvent_respectsAllowFrom() throws Exception {
        FeishuChannel.FeishuConfig config = new FeishuChannel.FeishuConfig();
        config.setAllowFrom(List.of("user-allowed"));
        MessageBus bus = new MessageBus();
        FeishuChannel channel = new FeishuChannel(config, bus);

        channel.handleWebhookEvent(Map.of(
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "user-blocked")),
                        "message", Map.of("chat_id", "chat-1", "content", "{\"text\":\"blocked\"}")
                )
        ));

        assertNull(bus.consumeInbound(100, TimeUnit.MILLISECONDS));
    }
}
