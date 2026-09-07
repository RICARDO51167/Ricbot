package ricbot.tool.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolDispatcherTest {
    @Test
    void rejectsUnknownInvalidAndIrreversiblyUnsafeCallsBeforeExecution() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool(ExecutionMode.LOCAL, ToolRiskEvidence.allow(), List.of()));
        ToolDispatcher dispatcher = new ToolDispatcher(catalog, null);
        ToolExecutionContext context = ToolExecutionContext.normal();

        assertEquals("TOOL_NOT_FOUND", assertThrows(ToolDispatcher.ToolDispatchException.class,
                () -> dispatcher.prepare(new ToolInvocation("1", "missing", Map.of()), context)).code());
        assertEquals("INVALID_ARGUMENTS", assertThrows(ToolDispatcher.ToolDispatchException.class,
                () -> dispatcher.prepare(new ToolInvocation("2", "probe", Map.of()), context)).code());

        ToolCatalog unsafe = new ToolCatalog();
        unsafe.register(tool(ExecutionMode.LOCAL,
                new ToolRiskEvidence(ToolRiskEvidence.Decision.DENY, null, "path escape", true), List.of()));
        assertEquals("SAFETY_DENY", assertThrows(ToolDispatcher.ToolDispatchException.class,
                () -> new ToolDispatcher(unsafe, null).prepare(
                        new ToolInvocation("3", "probe", Map.of("value", "x")), context)).code());
    }

    @Test
    void externalModeCreatesBoundPendingActionWithoutCallingLocalExecute() throws Exception {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool(ExecutionMode.EXTERNAL, ToolRiskEvidence.allow(), List.of("resource:z", "resource:a")));
        ToolDispatcher dispatcher = new ToolDispatcher(catalog, null);
        ToolInvocation invocation = new ToolInvocation("call", "probe", Map.of("value", "42"));
        ToolDispatcher.Prepared prepared = dispatcher.prepare(invocation, ToolExecutionContext.normal());

        assertEquals(List.of("resource:a", "resource:z"), prepared.resourceKeys());
        ToolResult result = dispatcher.execute(prepared, ToolExecutionContext.normal(), ToolChunkSink.discard());
        assertInstanceOf(ToolResult.ExternalPending.class, result);
        ToolResult.ExternalPending pending = (ToolResult.ExternalPending) result;
        assertFalse(pending.actionId().isBlank());
        assertEquals(prepared.invocationDigest(), pending.invocationDigest());
    }

    @Test
    void validatesTypesRangesAndNestedSchemasBeforeAuthorization() {
        java.util.concurrent.atomic.AtomicInteger riskCalls = new java.util.concurrent.atomic.AtomicInteger();
        Tool nested = new Tool() {
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(1, "test.nested", "nested", "nested tool",
                        Map.of("type", "object", "required", List.of("count", "children"),
                                "properties", Map.of(
                                        "count", Map.of("type", "integer", "minimum", 1, "maximum", 3),
                                        "children", Map.of("type", "array", "minItems", 1,
                                                "items", Map.of("type", "object", "required", List.of("name"),
                                                        "properties", Map.of("name", Map.of("type", "string", "minLength", 1)),
                                                        "additionalProperties", false))),
                                "additionalProperties", false), ToolGroup.BASIC, ToolSource.BUILTIN,
                        ToolEffectPolicy.readOnly(Duration.ofSeconds(1)), ExecutionMode.LOCAL,
                        ToolResultPolicy.GENERIC);
            }
            public ToolRiskEvidence assessRisk(ToolInvocation invocation, ToolExecutionContext context) {
                riskCalls.incrementAndGet(); return ToolRiskEvidence.allow();
            }
            public List<String> resourceKeys(ToolInvocation invocation, ToolExecutionContext context) { return List.of(); }
            public ToolResult execute(ToolInvocation invocation, ToolExecutionContext context, ToolChunkSink chunks) {
                fail("invalid call must never execute"); return ToolResult.Success.of("unreachable");
            }
        };
        ToolCatalog catalog = new ToolCatalog(); catalog.register(nested);
        ToolDispatcher dispatcher = new ToolDispatcher(catalog, null);

        ToolDispatcher.ToolDispatchException type = assertThrows(ToolDispatcher.ToolDispatchException.class,
                () -> dispatcher.prepare(new ToolInvocation("1", "nested",
                        Map.of("count", "two", "children", List.of(Map.of("name", "ok")))),
                        ToolExecutionContext.normal()));
        assertEquals("INVALID_ARGUMENTS", type.code());
        assertTrue(type.getMessage().contains("$.count"));
        ToolDispatcher.ToolDispatchException nestedFailure = assertThrows(ToolDispatcher.ToolDispatchException.class,
                () -> dispatcher.prepare(new ToolInvocation("2", "nested",
                        Map.of("count", 4, "children", List.of(Map.of("name", "")))),
                        ToolExecutionContext.normal()));
        assertTrue(nestedFailure.getMessage().contains("$.children[0].name"));
        assertEquals(0, riskCalls.get(), "validation must precede risk and authorization");
    }

    @Test void cancelledContextNeverExecutesPreparedTool() {
        ToolCatalog catalog = new ToolCatalog(); catalog.register(tool(
                ExecutionMode.LOCAL, ToolRiskEvidence.allow(), List.of()));
        ToolDispatcher dispatcher = new ToolDispatcher(catalog, null);
        ToolExecutionContext base = ToolExecutionContext.normal();
        ToolDispatcher.Prepared prepared = dispatcher.prepare(
                new ToolInvocation("1", "probe", Map.of("value", "ok")), base);
        base.cancelled().set(true);
        assertEquals("CANCELLED", assertThrows(ToolDispatcher.ToolDispatchException.class,
                () -> dispatcher.execute(prepared, base, ToolChunkSink.discard())).code());
    }

    private static Tool tool(ExecutionMode mode, ToolRiskEvidence risk, List<String> keys) {
        return new Tool() {
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(1, "test.probe", "probe", "probe tool",
                        Map.of("type", "object", "required", List.of("value"),
                                "properties", Map.of("value", Map.of("type", "string")),
                                "additionalProperties", false), ToolGroup.BASIC, ToolSource.BUILTIN,
                        ToolEffectPolicy.readOnly(Duration.ofSeconds(1)), mode, ToolResultPolicy.GENERIC);
            }
            public ToolRiskEvidence assessRisk(ToolInvocation invocation, ToolExecutionContext context) { return risk; }
            public List<String> resourceKeys(ToolInvocation invocation, ToolExecutionContext context) { return keys; }
            public ToolResult execute(ToolInvocation invocation, ToolExecutionContext context, ToolChunkSink chunks) {
                if (mode == ExecutionMode.EXTERNAL) fail("external tools must not execute locally");
                return ToolResult.Success.of("ok");
            }
        };
    }
}
