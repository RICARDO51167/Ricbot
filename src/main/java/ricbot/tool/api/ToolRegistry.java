package ricbot.tool.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Application-owned catalog instance. Execution intentionally lives in {@link ToolDispatcher}. */
public class ToolRegistry extends ToolCatalog {
    public void register(Tool tool, ToolGroup ignoredGroup) {
        if (tool instanceof BuiltinTool builtin && builtin.descriptor().group() != ignoredGroup) {
            tool = new GroupedTool(tool, ignoredGroup);
        }
        register(tool);
    }
    public ToolGroup groupFor(String name) {
        Tool tool = get(name);
        return tool != null ? tool.descriptor().group() : ToolGroup.ADMIN;
    }
    public ToolRegistry copy() {
        ToolRegistry copy = new ToolRegistry();
        tools.values().forEach(copy::register);
        copy.setExposure(exposure);
        return copy;
    }
    public List<Map<String, Object>> getDefinitions() { return definitions(); }
    public List<Map<String, Object>> getDefinitions(java.util.Set<ToolGroup> activeGroups) {
        return definitions(activeGroups);
    }
    public ToolPolicy policyFor(String name) {
        Tool tool = get(name);
        if (tool == null) return new ToolPolicy(name, false, false, false, "missing");
        ToolEffectPolicy effect = tool.descriptor().effectPolicy();
        boolean exclusive = effect.concurrency() == ToolEffectPolicy.Concurrency.EXCLUSIVE_WORKSPACE;
        return new ToolPolicy(name, effect.readOnly(), exclusive, effect.concurrentSafe(), effect.effect().name());
    }
    public boolean canRunConcurrently(Collection<String> names) {
        return names == null || names.stream().map(this::policyFor).allMatch(ToolPolicy::concurrentSafe);
    }
    public record ToolPolicy(String name, boolean readOnly, boolean exclusive, boolean concurrentSafe, String risk) { }

    private record GroupedTool(Tool delegate, ToolGroup group) implements Tool {
        @Override public ToolDescriptor descriptor() {
            ToolDescriptor d = delegate.descriptor();
            return new ToolDescriptor(d.schemaVersion(), d.id(), d.name(), d.description(), d.parameters(), group,
                    d.source(), d.effectPolicy(), d.executionMode(), d.resultPolicy());
        }
        @Override public ToolRiskEvidence assessRisk(ToolInvocation i, ToolExecutionContext c) { return delegate.assessRisk(i, c); }
        @Override public List<String> resourceKeys(ToolInvocation i, ToolExecutionContext c) { return delegate.resourceKeys(i, c); }
        @Override public ToolResult execute(ToolInvocation i, ToolExecutionContext c, ToolChunkSink s) throws Exception { return delegate.execute(i, c, s); }
    }
}
