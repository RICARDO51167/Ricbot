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

class DingTalkChannelTest {

    @Test
    void handleWebhookEvent_publishesTextMessage() throws Exception {
        DingTalkChannel.DingTalkConfig config = new DingTalkChannel.DingTalkConfig();
        config.setAllowFrom(List.of("*"));
        MessageBus bus = new MessageBus();
        DingTalkChannel channel = new DingTalkChannel(config, bus);

        channel.handleWebhookEvent(Map.of(
                "msgId", "msg-1",
                "msgtype", "text",
                "senderStaffId", "user-1",
                "senderNick", "Alice",
                "conversationId", "chat-1",
                "text", Map.of("content", "hello")
        ));

        InboundMessage inbound = bus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(inbound);
        assertEquals("dingtalk", inbound.getChannel());
        assertEquals("user-1", inbound.getSenderId());
        assertEquals("chat-1", inbound.getChatId());
        assertEquals("hello", inbound.getContent());
        assertEquals("msg-1", inbound.getMetadata().get("dingtalk_msg_id"));
        assertEquals("Alice", inbound.getMetadata().get("sender_nick"));
    }

    @Test
    void handleWebhookEvent_respectsAllowFrom() throws Exception {
        DingTalkChannel.DingTalkConfig config = new DingTalkChannel.DingTalkConfig();
        config.setAllowFrom(List.of("user-allowed"));
        MessageBus bus = new MessageBus();
        DingTalkChannel channel = new DingTalkChannel(config, bus);

        channel.handleWebhookEvent(Map.of(
                "senderStaffId", "user-blocked",
                "conversationId", "chat-1",
                "text", Map.of("content", "blocked")
        ));

        assertNull(bus.consumeInbound(100, TimeUnit.MILLISECONDS));
    }
}
