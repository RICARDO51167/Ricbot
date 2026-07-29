package ricbot.tool.api;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Meta-tool may narrow or activate pre-authorized groups; it can never grant itself authority. */
public final class ManageToolGroupsTool extends Tool {
    private final ToolRegistry registry;
    public ManageToolGroupsTool(ToolRegistry registry) { this.registry = registry; }
    public String getName() { return "manage_tool_groups"; }
    public String getDescription() { return "激活或关闭当前 Run 已授权的工具组；basic 始终启用，不能提权。"; }
    public ToolEffectPolicy effectPolicy() { return ToolEffectPolicy.readOnly(Duration.ofSeconds(10)); }
    public List<ToolParam> getParams() { return List.of(
            ToolParam.of("action", "string", "activate、deactivate 或 status", true),
            ToolParam.of("groups", "array", "工具组名称列表", false)); }
    public Object execute(Map<String, Object> params) {
        String action = String.valueOf(params.getOrDefault("action", "status"));
        Collection<ToolGroup> groups = params.get("groups") instanceof Collection<?> values
                ? values.stream().map(String::valueOf).map(ToolGroup::parse).toList() : List.of();
        if ("activate".equalsIgnoreCase(action)) registry.setExposure(registry.exposure().activate(groups));
        else if ("deactivate".equalsIgnoreCase(action)) registry.setExposure(registry.exposure().deactivate(groups));
        else if (!"status".equalsIgnoreCase(action)) return "Error: action must be activate, deactivate, or status";
        return Map.of("allowedGroups", registry.exposure().allowedGroups(),
                "activeGroups", registry.exposure().activeGroups(), "visibleTools", registry.visibleToolNames());
    }
}
