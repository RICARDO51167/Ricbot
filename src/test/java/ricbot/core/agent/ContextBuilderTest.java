package ricbot.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.ContextBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ContextBuilderTest {

    @Test
    void buildMessages_dropsOrphanToolHistory(@TempDir Path workspace) {
        ContextBuilder builder = new ContextBuilder(workspace, "UTC", List.of());

        List<Map<String, Object>> history = List.of(
                Map.of("role", "tool", "tool_call_id", "missing_call", "content", "orphan"),
                Map.of("role", "user", "content", "hello"),
                Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of("id", "call_1", "type", "function", "function", Map.of("name", "list_dir")))
                ),
                Map.of("role", "tool", "tool_call_id", "call_1", "content", "[]")
        );

        List<Map<String, Object>> messages = builder.buildMessages(
                history,
                "next",
                null,
                "cli",
                "direct",
                "summary",
                "user"
        );

        assertEquals("system", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("hello", messages.get(1).get("content"));
        assertFalse(messages.stream().anyMatch(m -> "missing_call".equals(String.valueOf(m.get("tool_call_id")))));
        assertEquals("user", messages.get(messages.size() - 1).get("role"));
        assertEquals("next", messages.get(messages.size() - 1).get("content"));
    }

    @Test
    void buildMessages_inlinesLocalImageAsDataUrl(@TempDir Path workspace) throws Exception {
        ContextBuilder builder = new ContextBuilder(workspace, "UTC", List.of());
        Path image = workspace.resolve("tiny.png");
        Files.write(image, new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x00
        });

        List<Map<String, Object>> messages = builder.buildMessages(
                List.of(),
                "look",
                List.of(image.toString()),
                "cli",
                "direct",
                null,
                "user"
        );

        Object content = messages.get(1).get("content");
        assertInstanceOf(List.class, content);
        List<?> blocks = (List<?>) content;
        assertEquals(2, blocks.size());

        Map<?, ?> imageBlock = (Map<?, ?>) blocks.get(1);
        assertEquals("image_url", imageBlock.get("type"));
        Map<?, ?> payload = (Map<?, ?>) imageBlock.get("image_url");
        assertTrue(String.valueOf(payload.get("url")).startsWith("data:image/png;base64,"));
    }
}
