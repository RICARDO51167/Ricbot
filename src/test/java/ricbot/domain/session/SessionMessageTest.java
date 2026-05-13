package ricbot.domain.session;

import org.junit.jupiter.api.Test;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMessageTest {

    @Test
    void fromMap_normalizesRoleAndTimestampButKeepsStorageShape() {
        SessionMessage message = SessionMessage.fromMap(Map.of(
                "role", "unknown",
                "content", "hello"
        ));

        Map<String, Object> data = message.toMap();
        assertEquals("user", data.get(Session.KEY_ROLE));
        assertEquals("hello", data.get(Session.KEY_CONTENT));
        assertTrue(data.containsKey(Session.KEY_TIMESTAMP));
    }

    @Test
    void assistant_exposesToolCallIds() {
        SessionMessage message = SessionMessage.assistant("", List.of(
                Map.of("id", "call-1", "type", "function"),
                Map.of("id", "call-2", "type", "function")
        ));

        assertTrue(message.hasToolCalls());
        assertEquals(List.of("call-1", "call-2"), message.toolCallIds());
    }
}
