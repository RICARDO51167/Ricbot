package ricbot.tool.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test void catalogRejectsDuplicateNamesAndProducesStrictSchema() {
        ToolRegistry catalog = new ToolRegistry();
        catalog.register(tool("echo", ToolGroup.BASIC));
        assertThrows(IllegalArgumentException.class, () -> catalog.register(tool("echo", ToolGroup.BASIC)));
        Map<?, ?> function = (Map<?, ?>) catalog.getDefinitions().get(0).get("function");
        Map<?, ?> parameters = (Map<?, ?>) function.get("parameters");
        assertEquals(false, parameters.get("additionalProperties"));
    }

    @Test void exposureIsFailClosedAndBasicCannotBeDisabled() {
        ToolRegistry catalog = new ToolRegistry();
        catalog.register(tool("echo", ToolGroup.BASIC));
        catalog.register(tool("write", ToolGroup.CODING));
        catalog.setExposure(new ToolExposure(Set.of(ToolGroup.BASIC, ToolGroup.CODING), Set.of(ToolGroup.BASIC)));
        assertEquals(List.of("echo"), catalog.visibleToolNames());
        assertThrows(ToolDispatcher.ToolDispatchException.class, () -> new ToolDispatcher(catalog, null)
                .prepare(new ToolInvocation("1", "write", Map.of("value", "x")), context()));
    }

    @Test void dispatcherValidatesAuthorizesAndExecutes() throws Exception {
        ToolRegistry catalog = new ToolRegistry();
        catalog.register(tool("echo", ToolGroup.BASIC));
        ToolDispatcher dispatcher = new ToolDispatcher(catalog, null);
        ToolDispatcher.Prepared prepared = dispatcher.prepare(
                new ToolInvocation("1", "echo", Map.of("value", "hello")), context());
        ToolResult result = dispatcher.execute(prepared, context(), ToolChunkSink.discard());
        assertEquals("hello", ((ToolResult.Success) result).value());
        assertThrows(ToolDispatcher.ToolDispatchException.class, () -> dispatcher.prepare(
                new ToolInvocation("2", "echo", Map.of("unknown", true)), context()));
    }

    @Test void manageGroupsReturnsMutationWithoutChangingCatalog() throws Exception {
        ToolRegistry catalog = new ToolRegistry();
        catalog.setExposure(new ToolExposure(Set.of(ToolGroup.BASIC, ToolGroup.CODING), Set.of(ToolGroup.BASIC)));
        ManageToolGroupsTool manage = new ManageToolGroupsTool(catalog);
        ToolResult.Success result = (ToolResult.Success) manage.execute(
                new ToolInvocation("1", "manage_tool_groups", Map.of("active_groups", List.of("basic", "coding"))),
                context(), ToolChunkSink.discard());
        assertEquals(Set.of(ToolGroup.BASIC), catalog.exposure().activeGroups());
        assertInstanceOf(ToolStateMutation.SetToolExposure.class, result.mutations().get(0));
    }

    private static Tool tool(String name, ToolGroup group) {
        ToolEffectPolicy policy = ToolEffectPolicy.readOnly(Duration.ofSeconds(10));
        return new Tool() {
            public ToolDescriptor descriptor() { return new ToolDescriptor(1, "test." + name, name, "test",
                    Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")),
                            "required", List.of("value"), "additionalProperties", false),
                    group, ToolSource.BUILTIN, policy, ExecutionMode.LOCAL, ToolResultPolicy.GENERIC); }
            public ToolResult execute(ToolInvocation invocation, ToolExecutionContext context, ToolChunkSink sink) {
                return ToolResult.Success.of(invocation.arguments().get("value"));
            }
        };
    }
    private static ToolExecutionContext context() {
        return new ToolExecutionContext("run", "session", "", "activation", "workspace", null,
                "DEVELOPER", null, Map.of(), Map.of(), null);
    }
}
