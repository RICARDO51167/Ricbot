package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentHookFactoryTest {

    @Test
    void create_combinesStreamingProgressAndExternalHooks() throws Exception {
        MessageBus bus = new MessageBus();
        List<String> invocations = new ArrayList<>();
        AgentHookFactory factory = new AgentHookFactory(bus, (channel, chatId, messageId) ->
                invocations.add("ctx:" + channel + ":" + chatId + ":" + messageId)
        );
        InboundMessage msg = new InboundMessage("cli", "user", "direct", "hello");
        msg.setMetadata(new HashMap<>(Map.of(
                "_wants_stream", true,
                "message_id", "m-1"
        )));

        AgentHook globalHook = new AgentHook() {
            @Override
            public void beforeExecuteTools(AgentHookContext context) {
                invocations.add("global");
            }
        };
        AgentHook requestHook = new AgentHook() {
            @Override
            public void beforeExecuteTools(AgentHookContext context) {
                invocations.add("request");
            }
        };

        AgentHook hook = factory.create(msg, List.of(globalHook), List.of(requestHook));
        assertTrue(hook.wantsStreaming());

        hook.onStream(new AgentHookContext(), "hello");
        OutboundMessage delta = bus.pollOutboundNow();
        assertNotNull(delta);
        assertEquals("hello", delta.getContent());
        assertEquals(Boolean.TRUE, delta.getMetadata().get("_stream_delta"));

        AgentHookContext context = new AgentHookContext()
                .setResponse(new LLMResponse().setContent("thinking"))
                .setToolCalls(List.of(new ToolCallRequest("call_1", "list_dir", Map.of("path", "."))));
        hook.beforeExecuteTools(context);

        OutboundMessage progress = bus.pollOutboundNow();
        assertNotNull(progress);
        assertEquals(Boolean.TRUE, progress.getMetadata().get("_progress"));
        assertEquals(Boolean.TRUE, progress.getMetadata().get("_tool_hint"));
        assertEquals(List.of("ctx:cli:direct:m-1", "global", "request"), invocations);

        hook.onStreamEnd(new AgentHookContext(), false);
        OutboundMessage end = bus.pollOutboundNow();
        assertNotNull(end);
        assertEquals(Boolean.TRUE, end.getMetadata().get("_stream_end"));
    }
}
