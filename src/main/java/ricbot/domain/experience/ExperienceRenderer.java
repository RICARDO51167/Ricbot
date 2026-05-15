package ricbot.domain.experience;

import java.util.List;

public final class ExperienceRenderer {
    private final ExperienceStore store;

    public ExperienceRenderer() {
        this(null);
    }

    public ExperienceRenderer(ExperienceStore store) {
        this.store = store;
    }

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
                    .append(" effectiveConfidence=").append(format(effectiveConfidence(entry)))
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
        sb.append("confidence: ").append(format(entry.confidence())).append("\n");
        sb.append("effectiveConfidence: ").append(format(effectiveConfidence(entry))).append("\n");
        sb.append("source: ").append(entry.source()).append(" ").append(entry.sourceRef()).append("\n");
        sb.append("failureKind: ").append(blank(entry.failureKind())).append("\n");
        sb.append("whenToApply: ").append(blank(entry.whenToApply())).append("\n");
        sb.append("content: ").append(blank(entry.content())).append("\n");
        sb.append("evidence: ").append(blank(entry.evidence())).append("\n");
        sb.append("relatedFiles: ").append(join(entry.relatedFiles())).append("\n");
        sb.append("suggestedTests: ").append(join(entry.suggestedTests())).append("\n");
        sb.append("successCount: ").append(entry.successCount()).append("\n");
        sb.append("failureCount: ").append(entry.failureCount()).append("\n");
        sb.append("lastUsedAt: ").append(blank(entry.lastUsedAt())).append("\n");
        sb.append("promotedAt: ").append(blank(entry.promotedAt())).append("\n");
        sb.append("promotedTo: ").append(blank(entry.promotedTo())).append("\n");
        sb.append("demotedAt: ").append(blank(entry.demotedAt())).append("\n");
        sb.append("governanceNote: ").append(blank(entry.governanceNote())).append("\n");
        sb.append("createdAt: ").append(entry.createdAt()).append("\n");
        sb.append("updatedAt: ").append(entry.updatedAt());
        return sb.toString();
    }

    public String renderUsage(List<ExperienceUsageTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "No usage trace.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("experience usage (").append(traces.size()).append(")\n");
        for (ExperienceUsageTrace trace : traces) {
            sb.append("- ")
                    .append(trace.usedAt())
                    .append(" id=").append(trace.experienceId())
                    .append(" outcome=").append(trace.outcome())
                    .append(" relevance=").append(format(trace.relevanceScore()));
            if (!trace.sessionId().isBlank()) {
                sb.append(" session=").append(trace.sessionId());
            }
            if (!trace.evidence().isBlank()) {
                sb.append(" evidence=").append(trace.evidence());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public String renderStale(List<ExperienceStore.ScoredExperience> stale) {
        if (stale == null || stale.isEmpty()) {
            return "No stale verified experience.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("stale verified experience (").append(stale.size()).append(")\n");
        for (ExperienceStore.ScoredExperience item : stale) {
            ExperienceEntry entry = item.entry();
            sb.append("- ")
                    .append(entry.id())
                    .append(" [").append(entry.type()).append("] ")
                    .append(entry.title())
                    .append(" effectiveConfidence=").append(format(item.effectiveConfidence()))
                    .append(" successCount=").append(entry.successCount())
                    .append(" failureCount=").append(entry.failureCount())
                    .append(" lastUsedAt=").append(blank(entry.lastUsedAt()))
                    .append(" reason=").append(item.reason())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderStats(ExperienceStore.GovernanceStats stats) {
        if (stats == null) {
            return "No experience stats.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("experience stats\n");
        sb.append("candidates: ").append(stats.candidates()).append("\n");
        sb.append("verified: ").append(stats.verified()).append("\n");
        sb.append("rejected: ").append(stats.rejected()).append("\n");
        sb.append("archived: ").append(stats.archived()).append("\n");
        sb.append("promoted: ").append(stats.promoted()).append("\n");
        sb.append("\ntop used\n");
        if (stats.topUsed().isEmpty()) {
            sb.append("- none\n");
        } else {
            for (ExperienceStore.UsageCount item : stats.topUsed()) {
                sb.append("- ").append(item.entry().id())
                        .append(" count=").append(item.count())
                        .append(" title=").append(item.entry().title())
                        .append("\n");
            }
        }
        sb.append("\nneeds review\n");
        if (stats.needsReview().isEmpty()) {
            sb.append("- none\n");
        } else {
            appendReviewItems(sb, stats.needsReview());
        }
        return sb.toString().trim();
    }

    public String renderReview(List<ExperienceStore.ReviewItem> items) {
        if (items == null || items.isEmpty()) {
            return "No verified experience needs review.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("experience review\n");
        appendReviewItems(sb, items);
        return sb.toString().trim();
    }

    private void appendReviewItems(StringBuilder sb, List<ExperienceStore.ReviewItem> items) {
        for (ExperienceStore.ReviewItem item : items) {
            ExperienceEntry entry = item.entry();
            sb.append("- ").append(entry.id())
                    .append(" reason=").append(item.reason())
                    .append(" effectiveConfidence=").append(format(item.effectiveConfidence()))
                    .append(" successCount=").append(entry.successCount())
                    .append(" failureCount=").append(entry.failureCount())
                    .append(" promotedTo=").append(blank(entry.promotedTo()))
                    .append(" title=").append(entry.title())
                    .append("\n");
        }
    }

    private double effectiveConfidence(ExperienceEntry entry) {
        return store != null ? store.effectiveConfidence(entry) : entry.confidence();
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private String blank(String value) {
        return value != null && !value.isBlank() ? value : "(empty)";
    }
}
