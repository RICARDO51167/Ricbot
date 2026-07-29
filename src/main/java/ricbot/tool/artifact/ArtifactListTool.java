package ricbot.tool.artifact;

import ricbot.domain.agent.artifact.ArtifactStore;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolEffectPolicy;

import java.time.Duration;
import java.util.Map;

public final class ArtifactListTool extends Tool {
    private final ArtifactStore store;
    public ArtifactListTool(ArtifactStore store) { this.store = store; }
    public String getName() { return "artifact_list"; }
    public String getDescription() { return "列出当前 Run 可访问的 Offload 产物及其 artifact URI。"; }
    public ToolEffectPolicy effectPolicy() { return ToolEffectPolicy.readOnly(Duration.ofSeconds(30)); }
    public Object execute(Map<String, Object> params) throws Exception { return store.list(); }
}
