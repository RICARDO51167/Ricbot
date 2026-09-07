package ricbot.tool.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Descriptor-first registry. It never executes tools. */
public class ToolCatalog {
    protected final Map<String, Tool> tools = new ConcurrentHashMap<>();
    protected volatile ToolExposure exposure = ToolExposure.all();

    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        ToolDescriptor descriptor = tool.descriptor();
        Tool existing = tools.putIfAbsent(descriptor.name(), tool);
        if (existing != null) throw new IllegalArgumentException("duplicate tool name: " + descriptor.name());
        boolean duplicateId = tools.values().stream().filter(value -> value != tool)
                .anyMatch(value -> value.descriptor().id().equals(descriptor.id()));
        if (duplicateId) { tools.remove(descriptor.name(), tool); throw new IllegalArgumentException("duplicate descriptor id: " + descriptor.id()); }
    }
    public void unregister(String name) { tools.remove(name); }
    public Tool get(String name) { return tools.get(name); }
    public ToolExposure exposure() { return exposure; }
    public void setExposure(ToolExposure exposure) { this.exposure = exposure != null ? exposure : ToolExposure.all(); }
    public boolean visible(String name) {
        Tool tool = tools.get(name);
        return tool != null && exposure.activeGroups().contains(tool.descriptor().group());
    }
    public List<String> toolNames() { return tools.keySet().stream().sorted().toList(); }
    public List<String> visibleToolNames() { return toolNames().stream().filter(this::visible).toList(); }
    public List<Map<String, Object>> definitions() {
        return definitions(exposure.activeGroups());
    }
    public List<Map<String, Object>> definitions(java.util.Set<ToolGroup> activeGroups) {
        java.util.Set<ToolGroup> visibleGroups = activeGroups != null ? java.util.Set.copyOf(activeGroups)
                : exposure.activeGroups();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : toolNames()) {
            Tool tool = tools.get(name);
            if (visibleGroups.contains(tool.descriptor().group())) out.add(tool.descriptor().providerSchema());
        }
        out.sort(Comparator.comparing(value -> String.valueOf(((Map<?, ?>) value.get("function")).get("name"))));
        return List.copyOf(out);
    }
}
