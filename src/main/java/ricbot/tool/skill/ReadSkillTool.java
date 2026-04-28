package ricbot.tool.skill;

import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.util.List;
import java.util.Map;

public class ReadSkillTool extends Tool {
    private final SkillsLoader skillsLoader;

    public ReadSkillTool(SkillsLoader skillsLoader) {
        this.skillsLoader = skillsLoader;
    }

    @Override
    public String getName() {
        return "read_skill";
    }

    @Override
    public String getDescription() {
        return "读取指定技能的完整 SKILL.md 内容。使用技能前应先调用本工具读取说明。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(ToolParam.of("name", "string", "技能名称，例如 github、weather、tmux", true));
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String name = params != null && params.get("name") != null ? String.valueOf(params.get("name")).trim() : "";
        if (name.isBlank()) {
            return "错误：缺少必填参数 'name'";
        }
        if (skillsLoader == null) {
            return "错误：技能加载器不可用。";
        }
        SkillsLoader.SkillDocument doc = skillsLoader.loadSkillDocument(name);
        if (doc == null) {
            return "错误：未找到技能 '" + name + "'。";
        }
        if (skillsLoader.isDisabled(doc.entry().name())) {
            return "错误：技能 '" + doc.entry().name() + "' 已被禁用。";
        }
        SkillsLoader.SkillAvailability availability = skillsLoader.availability(doc);
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(doc.entry().name()).append("\n");
        sb.append("source: ").append(doc.entry().source()).append("\n");
        sb.append("path: ").append(doc.entry().path()).append("\n");
        sb.append("available: ").append(availability.available()).append("\n");
        if (!availability.missingBins().isEmpty()) {
            sb.append("missing_bins: ").append(String.join(", ", availability.missingBins())).append("\n");
        }
        if (!availability.missingEnv().isEmpty()) {
            sb.append("missing_env: ").append(String.join(", ", availability.missingEnv())).append("\n");
        }
        sb.append("\n");
        if (!availability.available()) {
            sb.append("注意：该技能依赖未满足。先处理 missing_* 后再执行其中步骤。\n\n");
        }
        sb.append(doc.raw());
        return sb.toString();
    }
}
