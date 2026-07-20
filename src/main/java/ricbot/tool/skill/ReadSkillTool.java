package ricbot.tool.skill;

import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.Locale;

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
        return "读取指定技能的 SKILL.md 内容。可用 section/offset/max_chars 分段读取，使用技能前应先调用本工具读取说明。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("name", "string", "技能名称，例如 github、weather、tmux", true),
                ToolParam.of("section", "string", "可选。只读取指定 Markdown 标题段，例如 Usage、Permissions、Examples", false),
                ToolParam.of("offset", "integer", "可选。按字符偏移读取，默认 0", false),
                ToolParam.of("max_chars", "integer", "可选。最大返回字符数，默认 12000", false)
        );
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
        SkillsLoader.SkillContract contract = skillsLoader.contract(doc);
        String section = stringParam(params, "section");
        int offset = intParam(params, "offset", 0);
        int maxChars = intParam(params, "max_chars", 12_000);
        if (offset < 0) {
            return "错误：offset 不能小于 0";
        }
        if (maxChars <= 0) {
            return "错误：max_chars 必须大于 0";
        }
        String content = doc.raw();
        if (!section.isBlank()) {
            content = extractSection(doc.body(), section);
            if (content == null || content.isBlank()) {
                return "错误：技能 '" + doc.entry().name() + "' 中未找到 section '" + section + "'。";
            }
        }
        int totalChars = content.length();
        String sliced = slice(content, offset, maxChars);
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(doc.entry().name()).append("\n");
        sb.append("source: ").append(doc.entry().source()).append("\n");
        sb.append("path: ").append(doc.entry().path()).append("\n");
        sb.append("available: ").append(availability.available()).append("\n");
        if (!contract.version().isBlank()) {
            sb.append("version: ").append(contract.version()).append("\n");
        }
        if (!contract.risk().isBlank()) {
            sb.append("risk: ").append(contract.risk()).append("\n");
        }
        if (!contract.permissions().isEmpty()) {
            sb.append("permissions: ").append(String.join(", ", contract.permissions())).append("\n");
        }
        if (!contract.tools().isEmpty()) {
            sb.append("tools: ").append(String.join(", ", contract.tools())).append("\n");
        }
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
        if (!section.isBlank()) {
            sb.append("section: ").append(section).append("\n");
        }
        sb.append("offset: ").append(offset).append("\n");
        sb.append("returned_chars: ").append(sliced.length()).append("\n");
        sb.append("total_chars: ").append(totalChars).append("\n");
        sb.append("truncated: ").append(offset + sliced.length() < totalChars).append("\n\n");
        sb.append(sliced);
        return sb.toString();
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params != null ? params.get(key) : null;
        return value != null ? String.valueOf(value).trim() : "";
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params != null ? params.get(key) : null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String slice(String content, int offset, int maxChars) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (offset >= content.length()) {
            return "";
        }
        int end = Math.min(content.length(), offset + maxChars);
        return content.substring(offset, end);
    }

    private String extractSection(String markdown, String requestedSection) {
        if (markdown == null || markdown.isBlank() || requestedSection == null || requestedSection.isBlank()) {
            return null;
        }
        String target = normalizeHeading(requestedSection);
        String[] lines = markdown.split("\\r?\\n", -1);
        int start = -1;
        int level = 0;
        for (int i = 0; i < lines.length; i++) {
            Heading heading = parseHeading(lines[i]);
            if (heading == null) {
                continue;
            }
            if (start < 0 && normalizeHeading(heading.title()).equals(target)) {
                start = i;
                level = heading.level();
                continue;
            }
            if (start >= 0 && heading.level() <= level) {
                return joinLines(lines, start, i);
            }
        }
        return start >= 0 ? joinLines(lines, start, lines.length) : null;
    }

    private Heading parseHeading(String line) {
        if (line == null || !line.startsWith("#")) {
            return null;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6 || level >= line.length() || line.charAt(level) != ' ') {
            return null;
        }
        String title = line.substring(level + 1).trim();
        return title.isBlank() ? null : new Heading(level, title);
    }

    private String normalizeHeading(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String joinLines(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        return sb.toString().strip();
    }

    private record Heading(int level, String title) {
    }
}
