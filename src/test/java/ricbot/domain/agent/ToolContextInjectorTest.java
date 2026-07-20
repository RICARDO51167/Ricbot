package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolContextInjectorTest {

    @Test
    void apply_supportsThreeArgTwoArgAndNoopTools() {
        ToolRegistry registry = new ToolRegistry();
        ThreeArgTool threeArg = new ThreeArgTool();
        TwoArgTool twoArg = new TwoArgTool();
        PlainTool plain = new PlainTool();

        registry.register(threeArg);
        registry.register(twoArg);
        registry.register(plain);

        ToolContextInjector injector = new ToolContextInjector(registry);
        injector.apply("cli", "direct", "m-1");

        assertEquals("cli", threeArg.channel);
        assertEquals("direct", threeArg.chatId);
        assertEquals("m-1", threeArg.messageId);

        assertEquals("cli", twoArg.channel);
        assertEquals("direct", twoArg.chatId);
        assertNull(plain.channel);
    }

    @Test
    void apply_ignoresToolSetterFailures() {
        ToolRegistry registry = new ToolRegistry();
        FailingTool failing = new FailingTool();
        TwoArgTool healthy = new TwoArgTool();
        registry.register(failing);
        registry.register(healthy);

        ToolContextInjector injector = new ToolContextInjector(registry);
        injector.apply("cli", "direct", "m-2");

        assertEquals("cli", healthy.channel);
        assertEquals("direct", healthy.chatId);
    }

    private static class PlainTool extends BaseTool {
        String channel;

        @Override
        public String getName() {
            return "plain";
        }
    }

    private static class TwoArgTool extends BaseTool {
        String channel;
        String chatId;

        @Override
        public String getName() {
            return "two";
        }

        public void setContext(String channel, String chatId) {
            this.channel = channel;
            this.chatId = chatId;
        }
    }

    private static class ThreeArgTool extends BaseTool {
        String channel;
        String chatId;
        String messageId;

        @Override
        public String getName() {
            return "three";
        }

        public void setContext(String channel, String chatId, String messageId) {
            this.channel = channel;
            this.chatId = chatId;
            this.messageId = messageId;
        }
    }

    private static class FailingTool extends BaseTool {
        @Override
        public String getName() {
            return "failing";
        }

        public void setContext(String channel, String chatId) {
            throw new IllegalStateException("boom");
        }
    }

    private abstract static class BaseTool extends Tool {
        @Override
        public String getDescription() {
            return "test";
        }
    }
}
