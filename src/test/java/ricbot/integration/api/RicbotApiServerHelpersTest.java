package ricbot.integration.api;

import org.junit.jupiter.api.Test;
import ricbot.integration.api.RicbotApiServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

public class RicbotApiServerHelpersTest {

    @Test
    void responseText_handlesOutboundAndNull() {
        assertEquals("", RicbotApiServer.responseText(null));
        assertEquals("plain", RicbotApiServer.responseText("plain"));
    }

    @Test
    void extractTextContent_joinsTextPartsAndIgnoresNonText() throws Exception {
        var method = RicbotApiServer.class.getDeclaredMethod("extractTextContent", Object.class);
        method.setAccessible(true);

        String text = (String) method.invoke(null, List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "input_image", "image_url", "ignored"),
                Map.of("type", "text", "text", "world")
        ));

        assertEquals("hello world", text);
    }

    @Test
    void runWithTimeout_timesOutLongRunningTask() {
        assertThrows(TimeoutException.class, () ->
                RicbotApiServer.runWithTimeout(() -> {
                    Thread.sleep(200);
                    return "late";
                }, 20)
        );
    }

    @Test
    void streamChunk_buildsOpenAiCompatiblePayload() {
        Map<String, Object> chunk = RicbotApiServer.streamChunk("chatcmpl_test", "gpt-4o-mini", "hi", null);
        assertEquals("chat.completion.chunk", chunk.get("object"));
        assertEquals("gpt-4o-mini", chunk.get("model"));
        List<?> choices = (List<?>) chunk.get("choices");
        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        Map<?, ?> delta = (Map<?, ?>) choice.get("delta");
        assertEquals("hi", delta.get("content"));
        assertNull(choice.get("finish_reason"));
    }

    @Test
    void streamRoleChunk_setsAssistantRole() {
        Map<String, Object> chunk = RicbotApiServer.streamRoleChunk("chatcmpl_test", "gpt-4o-mini");
        List<?> choices = (List<?>) chunk.get("choices");
        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        Map<?, ?> delta = (Map<?, ?>) choice.get("delta");
        assertEquals("assistant", delta.get("role"));
    }
}
