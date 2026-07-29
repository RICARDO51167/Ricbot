package ricbot.domain.agent.middleware;

import org.junit.jupiter.api.Test;
import ricbot.integration.llm.api.LLMResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentMiddlewareChainTest {
    @Test void composesAsOnionAndRejectsUnknownStateVersion() throws Exception {
        List<String> order = new ArrayList<>();
        AgentMiddleware first = middleware("first", order);
        AgentMiddleware second = middleware("second", order);
        AgentMiddlewareChain chain = new AgentMiddlewareChain(List.of(first, second));
        AgentMiddleware.MiddlewareContext runtime = new AgentMiddleware.MiddlewareContext(
                "r", "s", "t", Map.of());
        chain.model(new AgentMiddleware.ModelCallContext(runtime, List.of(), List.of(), "m", false), context -> {
            order.add("provider"); return new LLMResponse("ok");
        });
        assertEquals(List.of("first-before", "second-before", "provider", "second-after", "first-after"), order);
        chain.validateStateVersions(Map.of("versions", Map.of("first", 1, "second", 1)));
        assertThrows(IllegalStateException.class,
                () -> chain.validateStateVersions(Map.of("versions", Map.of("first", 2, "second", 1))));
    }
    private static AgentMiddleware middleware(String id, List<String> order) {
        return new AgentMiddleware() {
            public String id() { return id; }
            public LLMResponse aroundModelCall(ModelCallContext context, ModelChain next) throws Exception {
                order.add(id + "-before");
                LLMResponse value = next.proceed(context);
                order.add(id + "-after");
                return value;
            }
        };
    }
}
