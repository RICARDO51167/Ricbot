package ricbot.tool.artifact;

import ricbot.domain.agent.artifact.ArtifactStore;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.BuiltinParameter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ArtifactReadTool extends ricbot.tool.api.BuiltinTool {
    private final ArtifactStore store;
    private final int maximum;
    public ArtifactReadTool(ArtifactStore store, int maximum) { this.store = store; this.maximum = Math.max(1, maximum); }
    public String getName() { return "artifact_read"; }
    public String getDescription() { return "按字符偏移读取当前 Run 的 artifact；不接受文件路径。"; }
    public ToolEffectPolicy effectPolicy() { return ToolEffectPolicy.readOnly(Duration.ofSeconds(30)); }
    public List<BuiltinParameter> getParams() { return List.of(
            BuiltinParameter.of("uri", "string", "artifact:// URI", true).minLength(1),
            BuiltinParameter.of("offset", "integer", "从 0 开始的字符偏移", false)
                    .defaultValue(0).minimum(0),
            BuiltinParameter.of("limit", "integer", "最大字符数", false).minimum(1)); }
    public Object execute(Map<String, Object> params) throws Exception {
        int offset = params.get("offset") instanceof Number number ? number.intValue() : 0;
        int limit = params.get("limit") instanceof Number number ? Math.min(maximum, number.intValue()) : maximum;
        return store.read(String.valueOf(params.get("uri")), offset, limit);
    }
}
