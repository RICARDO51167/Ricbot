package ricbot.domain.experience;

import ricbot.domain.note.NoteEntry;
import ricbot.domain.note.NoteService;

import java.util.List;
import java.util.Locale;

public class ExperiencePromoter {
    private final NoteService noteService;
    private final ExperienceStore store;

    public ExperiencePromoter(NoteService noteService, ExperienceStore store) {
        this.noteService = noteService;
        this.store = store;
    }

    public ExperienceEntry promote(String id) {
        ExperienceEntry entry = store.find(id);
        if (entry == null) {
            throw new IllegalArgumentException("experience not found: " + id);
        }
        if (entry.status() == ExperienceStatus.CANDIDATE) {
            throw new IllegalArgumentException("candidate experience must be verified before promote: " + id);
        }
        if (entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalArgumentException("only verified experience can be promoted: " + id);
        }
        if (!entry.promotedTo().isBlank()) {
            return entry;
        }

        String fileName = fileName(entry.type());
        String body = render(entry, store.effectiveConfidence(entry));
        NoteEntry note = noteService.appendProjectFile(
                fileName,
                title(entry.type()),
                "reference",
                body,
                List.of("experience", "promoted", entry.type().name().toLowerCase(Locale.ROOT))
        );
        return store.markPromoted(id, note.path(), "promoted to project playbook");
    }

    public static String fileName(ExperienceType type) {
        return switch (type != null ? type : ExperienceType.PROJECT_CONVENTION) {
            case TEST_POLICY -> "test_policy.md";
            case TOOL_POLICY -> "tool_policy.md";
            case SECURITY_RULE -> "security_policy.md";
            case FAILURE_LESSON -> "failure_lessons.md";
            case SUCCESS_PLAYBOOK -> "playbooks.md";
            case PROJECT_CONVENTION -> "conventions.md";
        };
    }

    private String title(ExperienceType type) {
        return switch (type != null ? type : ExperienceType.PROJECT_CONVENTION) {
            case TEST_POLICY -> "Project Test Policy";
            case TOOL_POLICY -> "Project Tool Policy";
            case SECURITY_RULE -> "Project Security Policy";
            case FAILURE_LESSON -> "Project Failure Lessons";
            case SUCCESS_PLAYBOOK -> "Project Playbooks";
            case PROJECT_CONVENTION -> "Project Conventions";
        };
    }

    private String render(ExperienceEntry entry, double effectiveConfidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- experience-id: ").append(entry.id()).append(" -->\n");
        sb.append("### ").append(entry.title()).append("\n\n");
        sb.append("- type: ").append(entry.type()).append("\n");
        sb.append("- whenToApply: ").append(blank(entry.whenToApply())).append("\n");
        sb.append("- confidence: ").append(format(entry.confidence())).append("\n");
        sb.append("- effectiveConfidence: ").append(format(effectiveConfidence)).append("\n");
        sb.append("- successCount: ").append(entry.successCount()).append("\n");
        sb.append("- failureCount: ").append(entry.failureCount()).append("\n");
        sb.append("- sourceRef: ").append(blank(entry.sourceRef())).append("\n");
        sb.append("\n");
        sb.append(entry.content()).append("\n");
        if (!entry.suggestedTests().isEmpty()) {
            sb.append("\nSuggested tests:\n");
            for (String test : entry.suggestedTests()) {
                sb.append("- ").append(test).append("\n");
            }
        }
        if (!entry.evidence().isBlank()) {
            sb.append("\nEvidence:\n").append(entry.evidence()).append("\n");
        }
        return sb.toString().trim();
    }

    private String blank(String value) {
        return value != null && !value.isBlank() ? value : "none";
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
