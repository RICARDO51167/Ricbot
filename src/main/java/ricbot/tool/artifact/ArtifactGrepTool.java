package ricbot.tool.artifact;

import ricbot.domain.agent.artifact.ArtifactStore;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.BuiltinParameter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ArtifactGrepTool extends ricbot.tool.api.BuiltinTool {
    private final ArtifactStore store;
    public ArtifactGrepTool(ArtifactStore store) { this.store = store; }
    public String getName() { return "artifact_grep"; }
    public String getDescription() { return "在当前 Run 的单个 artifact 中执行正则搜索。"; }
    public ToolEffectPolicy effectPolicy() { return ToolEffectPolicy.readOnly(Duration.ofSeconds(30)); }
    public List<BuiltinParameter> getParams() { return List.of(
            BuiltinParameter.of("uri", "string", "artifact:// URI", true).minLength(1),
            BuiltinParameter.of("pattern", "string", "Java 正则表达式", true).minLength(1),
            BuiltinParameter.of("max_results", "integer", "最多匹配行数", false)
                    .defaultValue(100).minimum(1).maximum(500)); }
    public Object execute(Map<String, Object> params) throws Exception {
        int maximum = params.get("max_results") instanceof Number number ? Math.min(500, number.intValue()) : 100;
        return store.grep(String.valueOf(params.get("uri")), String.valueOf(params.get("pattern")), maximum);
    }
}
