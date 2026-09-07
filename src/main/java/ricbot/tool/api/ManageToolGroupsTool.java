package ricbot.tool.api;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Meta-tool may narrow or activate pre-authorized groups; it can never grant itself authority. */
public final class ManageToolGroupsTool extends BuiltinTool {
    private final ToolRegistry registry;
    public ManageToolGroupsTool(ToolRegistry registry) { this.registry = registry; }
    public String getName() { return "manage_tool_groups"; }
    public String getDescription() { return "激活或关闭当前 Run 已授权的工具组；basic 始终启用，不能提权。"; }
    public ToolEffectPolicy effectPolicy() { return new ToolEffectPolicy(ToolEffectPolicy.Effect.STATE_MUTATION,
            ToolEffectPolicy.Concurrency.SERIAL_PER_RUN, Duration.ofSeconds(10),
            ToolEffectPolicy.Approval.NEVER, ToolEffectPolicy.Retry.IDEMPOTENT_3); }
    public List<BuiltinParameter> getParams() { return List.of(
            BuiltinParameter.of("active_groups", "array", "完整目标工具组列表；未列出的非 basic 组关闭", true)
                    .maxItems(ToolGroup.values().length)
                    .items(Map.of("type", "string", "enum",
                            java.util.Arrays.stream(ToolGroup.values()).map(Enum::name).toList()))); }
    public Object execute(Map<String, Object> params) {
        return "manage_tool_groups must execute through ToolDispatcher";
    }
    @Override public ToolResult execute(ToolInvocation invocation, ToolExecutionContext context, ToolChunkSink chunks) {
        if (!(invocation.arguments().get("active_groups") instanceof java.util.Collection<?> raw)) {
            return new ToolResult.Failure("INVALID_ARGUMENTS", "active_groups is required", false, List.of());
        }
        Set<ToolGroup> active = new LinkedHashSet<>();
        try { raw.stream().map(String::valueOf).map(ToolGroup::parse).forEach(active::add); }
        catch (Exception invalid) { return new ToolResult.Failure("INVALID_GROUP", invalid.getMessage(), false, List.of()); }
        active.add(ToolGroup.BASIC);
        if (!registry.exposure().allowedGroups().containsAll(active)) {
            return new ToolResult.Failure("GROUP_NOT_AUTHORIZED", "requested group is not authorized", false, List.of());
        }
        ToolExposure target = new ToolExposure(registry.exposure().allowedGroups(), active);
        return new ToolResult.Success(Map.of("allowedGroups", target.allowedGroups(), "activeGroups", target.activeGroups()),
                "Tool groups will change to " + target.activeGroups(),
                List.of(new ToolStateMutation.SetToolExposure(target.activeGroups())), Map.of());
    }
}
