package ricbot.domain.experience;

import ricbot.domain.agent.TaskSummaryService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ExperienceExtractor {

    public List<ExperienceEntry> extract(TaskSummaryService.TaskSummary summary) {
        return extract(summary, "task_summary", summary != null ? summary.goal() : "");
    }

    public List<ExperienceEntry> extract(TaskSummaryService.TaskSummary summary, String source, String sourceRef) {
        if (summary == null) {
            return List.of();
        }
        List<ExperienceEntry> out = new ArrayList<>();
        out.addAll(testPolicy(summary, source, sourceRef));
        out.addAll(diffReviewExperience(summary, source, sourceRef));
        out.addAll(blockerLessons(summary, source, sourceRef));
        out.addAll(decisionExperience(summary, source, sourceRef));
        return dedupe(out);
    }

    public List<ExperienceEntry> extractFromTaskNote(String markdown, String sourceRef) {
        TaskSummaryService.TaskSummary summary = parseTaskNote(markdown);
        return extract(summary, "task_note", sourceRef);
    }

    private List<ExperienceEntry> testPolicy(TaskSummaryService.TaskSummary summary, String source, String sourceRef) {
        List<ExperienceEntry> out = new ArrayList<>();
        for (String test : summary.suggestedTests()) {
            out.add(ExperienceEntry.candidate(
                    ExperienceType.TEST_POLICY,
                    "Run targeted test: " + titleFragment(test),
                    "Run `" + test + "` when similar files or modules are changed.",
                    whenForFiles(summary.changedFiles()),
                    evidence(summary.goal(), test),
                    source,
                    sourceRef,
                    summary.changedFiles(),
                    List.of(test),
                    0.72d
            ));
        }
        return out;
    }

    private List<ExperienceEntry> diffReviewExperience(TaskSummaryService.TaskSummary summary, String source, String sourceRef) {
        List<ExperienceEntry> out = new ArrayList<>();
        for (String review : summary.diffReviews()) {
            String lower = lower(review);
            ExperienceType type = securitySensitive(lower) ? ExperienceType.SECURITY_RULE : ExperienceType.FAILURE_LESSON;
            String title = type == ExperienceType.SECURITY_RULE
                    ? "Review security-sensitive changes before reuse"
                    : "Check risky diff review before reuse";
            out.add(ExperienceEntry.candidate(
                    type,
                    title,
                    type == ExperienceType.SECURITY_RULE
                            ? "Security-sensitive diff reviews should keep approval, risk, validation, and permission checks intact."
                            : "When a DiffReview reports risk or suspicious changes, preserve the rollback and test plan with the task.",
                    whenForFiles(summary.changedFiles()),
                    review,
                    source,
                    sourceRef,
                    summary.changedFiles(),
                    summary.suggestedTests(),
                    type == ExperienceType.SECURITY_RULE ? 0.76d : 0.58d
            ));
        }
        return out;
    }

    private List<ExperienceEntry> blockerLessons(TaskSummaryService.TaskSummary summary, String source, String sourceRef) {
        List<ExperienceEntry> out = new ArrayList<>();
        for (String blocker : summary.blockers()) {
            out.add(ExperienceEntry.candidate(
                    ExperienceType.FAILURE_LESSON,
                    "Avoid blocker: " + titleFragment(blocker),
                    "This task hit a blocker: " + blocker,
                    "When a similar tool error, missing index, failing command, or blocked state appears.",
                    evidence(summary.goal(), blocker),
                    source,
                    sourceRef,
                    summary.changedFiles(),
                    summary.suggestedTests(),
                    0.64d
            ));
        }
        return out;
    }

    private List<ExperienceEntry> decisionExperience(TaskSummaryService.TaskSummary summary, String source, String sourceRef) {
        List<ExperienceEntry> out = new ArrayList<>();
        for (String decision : summary.keyDecisions()) {
            ExperienceType type = playbookLike(decision) ? ExperienceType.SUCCESS_PLAYBOOK : ExperienceType.PROJECT_CONVENTION;
            out.add(ExperienceEntry.candidate(
                    type,
                    (type == ExperienceType.SUCCESS_PLAYBOOK ? "Reuse playbook: " : "Project convention: ") + titleFragment(decision),
                    decision,
                    whenForFiles(summary.changedFiles()),
                    evidence(summary.goal(), decision),
                    source,
                    sourceRef,
                    summary.changedFiles(),
                    summary.suggestedTests(),
                    type == ExperienceType.SUCCESS_PLAYBOOK ? 0.66d : 0.60d
            ));
        }
        return out;
    }

    private TaskSummaryService.TaskSummary parseTaskNote(String markdown) {
        String goal = firstSection(markdown, "Goal");
        List<String> changedFiles = listSection(markdown, "Changed Files");
        List<String> keyDecisions = listSection(markdown, "Key Decisions");
        List<String> testCommands = listSection(markdown, "Test Commands");
        List<String> blockers = listSection(markdown, "Blockers");
        List<String> nextActions = listSection(markdown, "Next Actions");
        List<String> diffReviews = listSection(markdown, "Diff Reviews");
        diffReviews.addAll(linesContaining(markdown, "suspiciousChanges:"));
        List<String> suggestedTests = listSection(markdown, "Suggested Tests");
        List<String> rollbackHints = listSection(markdown, "Rollback Hints");
        return new TaskSummaryService.TaskSummary(
                goal,
                changedFiles,
                keyDecisions,
                testCommands,
                blockers,
                nextActions,
                listSection(markdown, "Approval Records"),
                diffReviews,
                suggestedTests,
                rollbackHints,
                ""
        );
    }

    private String firstSection(String markdown, String section) {
        for (String line : sectionBody(markdown, section)) {
            if (!line.isBlank() && !line.startsWith("- ")) {
                return line.trim();
            }
        }
        return "";
    }

    private List<String> listSection(String markdown, String section) {
        List<String> out = new ArrayList<>();
        for (String line : sectionBody(markdown, section)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                out.add(trimmed.substring(2).trim());
            }
        }
        return out;
    }

    private List<String> sectionBody(String markdown, String section) {
        List<String> out = new ArrayList<>();
        boolean active = false;
        for (String line : (markdown != null ? markdown : "").split("\\R")) {
            if (line.startsWith("## ")) {
                active = line.substring(3).trim().equalsIgnoreCase(section);
                continue;
            }
            if (active) {
                out.add(line);
            }
        }
        return out;
    }

    private List<String> linesContaining(String markdown, String needle) {
        List<String> out = new ArrayList<>();
        for (String line : (markdown != null ? markdown : "").split("\\R")) {
            if (line.contains(needle)) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private boolean securitySensitive(String lower) {
        return lower.contains("security")
                || lower.contains("approval")
                || lower.contains("risk=high")
                || lower.contains("risklevel: high")
                || lower.contains("permission")
                || lower.contains("networksecurity")
                || lower.contains("commandriskanalyzer")
                || lower.contains("approvalservice")
                || lower.contains("validation or risk checks removed");
    }

    private boolean playbookLike(String decision) {
        String lower = lower(decision);
        return lower.startsWith("use ")
                || lower.startsWith("choose ")
                || lower.startsWith("keep ")
                || lower.contains("playbook")
                || lower.contains("pattern")
                || lower.contains("approach")
                || lower.contains("success");
    }

    private String whenForFiles(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "When a similar task or module change appears.";
        }
        return "When changing " + String.join(", ", files) + ".";
    }

    private String evidence(String goal, String value) {
        if (goal == null || goal.isBlank()) {
            return value;
        }
        return "Task: " + goal + "\nEvidence: " + value;
    }

    private String titleFragment(String value) {
        String cleaned = value != null ? value.replace('`', ' ').replaceAll("\\s+", " ").trim() : "";
        if (cleaned.length() <= 72) {
            return cleaned;
        }
        return cleaned.substring(0, 72) + "...";
    }

    private List<ExperienceEntry> dedupe(List<ExperienceEntry> entries) {
        List<ExperienceEntry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ExperienceEntry entry : entries) {
            String key = entry.type() + "|" + lower(entry.title()) + "|" + lower(entry.whenToApply());
            if (seen.add(key)) {
                out.add(entry);
            }
        }
        return out;
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }
}
