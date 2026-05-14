package ricbot.domain.experience;

import java.util.List;

public final class ExperienceRenderer {
    public String renderList(List<ExperienceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "No candidate experience.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("candidate experience (").append(entries.size()).append(")\n");
        for (ExperienceEntry entry : entries) {
            sb.append("- ")
                    .append(entry.id())
                    .append(" [").append(entry.type()).append("] ")
                    .append(entry.title())
                    .append(" confidence=").append(String.format(java.util.Locale.ROOT, "%.2f", entry.confidence()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderDetail(ExperienceEntry entry) {
        if (entry == null) {
            return "Experience not found.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Experience ").append(entry.id()).append("\n");
        sb.append("status: ").append(entry.status()).append("\n");
        sb.append("type: ").append(entry.type()).append("\n");
        sb.append("title: ").append(entry.title()).append("\n");
        sb.append("confidence: ").append(String.format(java.util.Locale.ROOT, "%.2f", entry.confidence())).append("\n");
        sb.append("source: ").append(entry.source()).append(" ").append(entry.sourceRef()).append("\n");
        sb.append("whenToApply: ").append(blank(entry.whenToApply())).append("\n");
        sb.append("content: ").append(blank(entry.content())).append("\n");
        sb.append("evidence: ").append(blank(entry.evidence())).append("\n");
        sb.append("relatedFiles: ").append(join(entry.relatedFiles())).append("\n");
        sb.append("suggestedTests: ").append(join(entry.suggestedTests())).append("\n");
        sb.append("successCount: ").append(entry.successCount()).append("\n");
        sb.append("failureCount: ").append(entry.failureCount()).append("\n");
        sb.append("createdAt: ").append(entry.createdAt()).append("\n");
        sb.append("updatedAt: ").append(entry.updatedAt());
        return sb.toString();
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private String blank(String value) {
        return value != null && !value.isBlank() ? value : "(empty)";
    }
}
