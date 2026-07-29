package ricbot.domain.message;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InboundMessagesTest {

    @Test
    void of_copiesCollectionsAndOptionalFields() {
        InboundMessage msg = InboundMessages.of(
                "cli",
                "user",
                "direct",
                "hello",
                List.of("a.png"),
                Map.of("k", "v"),
                "session-1"
        );

        assertEquals("cli", msg.getChannel());
        assertEquals("user", msg.getSenderId());
        assertEquals("direct", msg.getChatId());
        assertEquals("hello", msg.getContent());
        assertEquals(List.of("a.png"), msg.getMedia());
        assertEquals(Map.of("k", "v"), msg.getMetadata());
        assertEquals("session-1", msg.getSessionKeyOverride());
    }

    @Test
    void of_defaultsToMutableEmptyCollections() {
        InboundMessage msg = InboundMessages.of("cli", "user", "direct", "hello");

        assertNotNull(msg.getMedia());
        assertTrue(msg.getMedia().isEmpty());
        assertNotNull(msg.getMetadata());
        assertTrue(msg.getMetadata().isEmpty());

        msg.getMedia().add("later");
        msg.getMetadata().put("k", "v");
        assertEquals(List.of("later"), msg.getMedia());
        assertEquals(Map.of("k", "v"), msg.getMetadata());
    }
}
