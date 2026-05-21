package ricbot.domain.experience;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExperienceSkillPromoter {
    private final Path workspace;
    private final ExperienceStore store;
    private final Path generatedSkillsDir;

    public ExperienceSkillPromoter(Path workspace, ExperienceStore store) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.store = store;
        this.generatedSkillsDir = this.workspace.resolve("skills").resolve("generated").normalize();
    }

    public PromotionResult promote(String experienceId) {
        return promote(experienceId, false);
    }

    public PromotionResult promote(String experienceId, boolean force) {
        String id = experienceId != null ? experienceId.trim() : "";
        if (id.isBlank()) {
            throw new IllegalArgumentException("missing experience id");
        }
        ExperienceEntry entry = store.find(id);
        if (entry == null) {
            throw new IllegalArgumentException("experience not found: " + id);
        }
        if (entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalStateException("only VERIFIED experience can be promoted to skill: " + id + " status=" + entry.status());
        }
        String skillName = safeName(entry);
        Path skillPath = generatedSkillsDir.resolve(skillName + ".md").normalize();
        if (!skillPath.startsWith(generatedSkillsDir)) {
            throw new IllegalStateException("generated skill path escaped workspace skills directory");
        }
        if (Files.exists(skillPath) && !force) {
            return new PromotionResult(entry.id(), skillName, skillPath, false, true);
        }
        try {
            Files.createDirectories(generatedSkillsDir);
            Files.writeString(skillPath, renderSkill(entry, skillName), StandardCharsets.UTF_8);
            return new PromotionResult(entry.id(), skillName, skillPath, true, false);
        } catch (IOException e) {
            throw new IllegalStateException("write generated skill failed: " + skillPath + ": " + e.getMessage(), e);
        }
    }

    private String renderSkill(ExperienceEntry entry, String skillName) {
        List<String> keywords = keywords(entry, skillName);
        List<String> tools = tools(entry);
        return "---\n"
                + "name: " + skillName + "\n"
                + "title: " + yamlScalar(title(entry)) + "\n"
                + "source: experience\n"
                + "source_experience_id: " + entry.id() + "\n"
                + "priority: 70\n"
                + "keywords:\n" + yamlList(keywords)
                + "tools:\n" + yamlList(tools)
                + "channels:\n"
                + "  - cli\n"
                + "generated: true\n"
                + "verified: true\n"
                + "---\n\n"
                + "# 背景\n\n" + paragraph(entry.content(), "该技能由已验证经验生成。") + "\n\n"
                + "# 适用场景\n\n" + paragraph(entry.whenToApply(), "当任务与该经验标题、关键词或相关文件匹配时使用。") + "\n\n"
                + "# 操作步骤\n\n" + steps(entry) + "\n\n"
                + "# 注意事项\n\n" + notes(entry) + "\n\n"
                + "# 反例或风险\n\n" + risks(entry) + "\n\n"
                + "# 来源经验\n\n"
                + "- experienceId: " + entry.id() + "\n"
                + "- type: " + entry.type() + "\n"
                + "- confidence: " + String.format(Locale.ROOT, "%.2f", entry.confidence()) + "\n"
                + "- evidence: " + paragraph(entry.evidence(), "未记录 evidence。") + "\n";
    }

    private String steps(ExperienceEntry entry) {
        List<String> lines = new ArrayList<>();
        if (!entry.content().isBlank()) {
            lines.add(entry.content());
        }
        if (!entry.suggestedTests().isEmpty()) {
            lines.add("验证时优先运行: " + String.join("; ", entry.suggestedTests()));
        }
        if (!entry.relatedFiles().isEmpty()) {
            lines.add("优先检查相关文件: " + String.join(", ", entry.relatedFiles()));
        }
        if (lines.isEmpty()) {
            return "1. 先确认任务上下文是否匹配来源经验。\n2. 按来源经验中的做法执行，并保留验证证据。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String notes(ExperienceEntry entry) {
        List<String> notes = new ArrayList<>();
        if (!entry.whenToApply().isBlank()) {
            notes.add("仅在适用场景匹配时使用: " + entry.whenToApply());
        }
        if (!entry.evidence().isBlank()) {
            notes.add("保留验证证据: " + entry.evidence());
        }
        return notes.isEmpty() ? "- 该经验字段不完整，使用前需要人工确认上下文。" : "- " + String.join("\n- ", notes);
    }

    private String risks(ExperienceEntry entry) {
        List<String> risks = new ArrayList<>();
        if (!entry.failureKind().isBlank()) {
            risks.add("failureKind: " + entry.failureKind());
        }
        if (entry.failureCount() > 0) {
            risks.add("历史失败次数: " + entry.failureCount());
        }
        return risks.isEmpty() ? "- 不要在 candidate/rejected 经验上套用该技能；使用后仍需验证。" : "- " + String.join("\n- ", risks);
    }

    private List<String> keywords(ExperienceEntry entry, String skillName) {
        List<String> out = new ArrayList<>();
        add(out, skillName);
        for (String token : tokenize(entry.title())) {
            add(out, token);
        }
        for (String token : tokenize(entry.whenToApply())) {
            add(out, token);
        }
        for (String file : entry.relatedFiles()) {
            add(out, file);
            Path name = Path.of(file).getFileName();
            if (name != null) {
                add(out, name.toString());
            }
        }
        add(out, entry.type().name().toLowerCase(Locale.ROOT));
        return out.isEmpty() ? List.of(skillName) : out.stream().limit(12).toList();
    }

    private List<String> tools(ExperienceEntry entry) {
        List<String> out = new ArrayList<>();
        if (!entry.relatedFiles().isEmpty()) {
            out.add("read_file");
            out.add("grep");
        }
        if (!entry.suggestedTests().isEmpty()) {
            out.add("exec");
        }
        return out.isEmpty() ? List.of("read_file") : List.copyOf(out);
    }

    private void add(List<String> out, String value) {
        String cleaned = value != null ? value.trim() : "";
        if (!cleaned.isBlank() && !out.contains(cleaned)) {
            out.add(cleaned);
        }
    }

    private List<String> tokenize(String value) {
        List<String> out = new ArrayList<>();
        String normalized = value != null ? value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsAlphabetic}\\p{IsDigit}._/-]+", " ") : "";
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !out.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    private String safeName(ExperienceEntry entry) {
        String base = !entry.title().isBlank() ? entry.title() : entry.id();
        String slug = base.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\p{IsAlphabetic}\\p{IsDigit}]+", "-")
                .replaceAll("^-+|-+$", "")
                .replaceAll("-{2,}", "-");
        if (slug.isBlank()) {
            slug = entry.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "experience-" + entry.id() : slug;
    }

    private String yamlList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values != null ? values : List.<String>of()) {
            if (value != null && !value.isBlank()) {
                sb.append("  - ").append(yamlScalar(value)).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : "  - general\n";
    }

    private String yamlScalar(String value) {
        String cleaned = value != null ? value.trim() : "";
        if (cleaned.isBlank()) {
            return "\"\"";
        }
        return "\"" + cleaned.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String title(ExperienceEntry entry) {
        return !entry.title().isBlank() ? entry.title() : "Generated skill from " + entry.id();
    }

    private String paragraph(String value, String fallback) {
        String cleaned = value != null ? value.trim() : "";
        return cleaned.isBlank() ? fallback : cleaned;
    }

    public record PromotionResult(
            String sourceExperienceId,
            String skillName,
            Path skillPath,
            boolean created,
            boolean alreadyExists
    ) {
    }
}
