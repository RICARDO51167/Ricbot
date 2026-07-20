package ricbot.tool.filesystem;

import ricbot.domain.security.CommandRiskLevel;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DiffReviewService {
    private static final List<String> VALIDATION_KEYWORDS = List.of(
            "contains",
            "validate",
            "check",
            "assert",
            "permission",
            "approval",
            "risk"
    );

    private final Path workspace;

    public DiffReviewService() {
        this(null);
    }

    public DiffReviewService(Path workspace) {
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : null;
    }

    public DiffReview review(String path, String before, String after, CommandRiskLevel baseRiskLevel) {
        ChangeKind kind = isBlank(before) && !isBlank(after) ? ChangeKind.CREATE : ChangeKind.EDIT;
        return buildReview(path, before, after, kind, baseRiskLevel);
    }

    public DiffReview reviewWriteFile(
            String path,
            String before,
            String after,
            boolean existedBefore,
            CommandRiskLevel baseRiskLevel
    ) {
        return buildReview(path, before, after, existedBefore ? ChangeKind.OVERWRITE : ChangeKind.CREATE, baseRiskLevel);
    }

    public DiffReview reviewEditFile(String path, String before, String after, CommandRiskLevel baseRiskLevel) {
        return buildReview(path, before, after, ChangeKind.EDIT, baseRiskLevel);
    }

    public String renderMarkdown(DiffReview review) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nDiffReview\n");
        sb.append("summary: ").append(review.summary()).append("\n");
        sb.append("changedFiles: ").append(joinOrNone(review.changedFiles())).append("\n");
        sb.append("addedLines: ").append(review.addedLines()).append("\n");
        sb.append("deletedLines: ").append(review.deletedLines()).append("\n");
        sb.append("riskLevel: ").append(review.riskLevel()).append("\n");
        sb.append("affectedAreas: ").append(joinOrNone(review.affectedAreas())).append("\n");
        sb.append("hasSecuritySensitiveChanges: ").append(review.hasSecuritySensitiveChanges()).append("\n");
        sb.append("hasConfigChanges: ").append(review.hasConfigChanges()).append("\n");
        sb.append("hasTestDeletion: ").append(review.hasTestDeletion()).append("\n");
        sb.append("suspiciousChanges: ").append(joinOrNone(review.suspiciousChanges())).append("\n");
        sb.append("suggestedTests: ").append(joinOrNone(review.suggestedTests())).append("\n");
        sb.append("rollbackHint: ").append(review.rollbackHint());
        return sb.toString();
    }

    private DiffReview buildReview(
            String path,
            String before,
            String after,
            ChangeKind kind,
            CommandRiskLevel baseRiskLevel
    ) {
        String displayPath = displayPath(path);
        LineDiff diff = lineDiff(before, after);
        List<String> affectedAreas = affectedAreas(displayPath);
        boolean securitySensitive = hasSecuritySensitiveChanges(displayPath);
        boolean configChanges = hasConfigChanges(displayPath);
        boolean testFile = isTestFile(displayPath);
        boolean testDeletion = testFile && (!isBlank(before) && isBlank(after));
        int beforeAssertions = countAssertions(before);
        int afterAssertions = countAssertions(after);
        boolean reducedAssertions = testFile && beforeAssertions > afterAssertions;

        List<String> suspiciousChanges = suspiciousChanges(
                displayPath,
                before,
                after,
                diff,
                securitySensitive,
                configChanges,
                testDeletion,
                reducedAssertions
        );
        CommandRiskLevel riskLevel = riskLevel(baseRiskLevel, diff, securitySensitive, configChanges, suspiciousChanges);
        List<String> suggestedTests = suggestedTests(displayPath);
        String rollbackHint = rollbackHint(displayPath, kind);
        String summary = summary(kind, displayPath, diff);

        return new DiffReview(
                List.of(displayPath),
                diff.addedLines(),
                diff.deletedLines(),
                riskLevel,
                summary,
                suspiciousChanges,
                suggestedTests,
                rollbackHint,
                affectedAreas,
                securitySensitive,
                configChanges,
                testDeletion || reducedAssertions
        );
    }

    private List<String> suspiciousChanges(
            String path,
            String before,
            String after,
            LineDiff diff,
            boolean securitySensitive,
            boolean configChanges,
            boolean testDeletion,
            boolean reducedAssertions
    ) {
        List<String> out = new ArrayList<>();
        String normalized = pathKey(path);
        String lowerAfter = lower(after);

        if (securitySensitive) {
            out.add("security-sensitive code changed");
        }
        if (isCoreExecutionPath(normalized)) {
            out.add("core execution path changed");
        }
        if (configChanges) {
            out.add("build or runtime configuration changed");
        }
        if (testDeletion) {
            out.add("test file content removed");
        } else if (reducedAssertions) {
            out.add("test assertions reduced");
        }
        if (diff.largeDeletion()) {
            out.add("large deletion");
        }
        if (!isBlank(before) && isBlank(after)) {
            out.add("file content cleared");
        }
        if (removedValidationLines(diff.removedLines())) {
            out.add("validation or risk checks removed");
        }
        if (looksSensitivePath(normalized)) {
            out.add("sensitive-looking path");
        }
        if (lowerAfter.contains("password") || lowerAfter.contains("api_key") || lowerAfter.contains("secret")) {
            out.add("secret-like text");
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    private List<String> suggestedTests(String path) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String normalized = pathKey(path);
        if (isTestFile(normalized)) {
            out.add("./mvnw -q -Dtest='" + testClassName(path) + "' test");
        }
        if (normalized.contains("ricbot/domain/security/")) {
            out.add("./mvnw -q -Dtest='ricbot.domain.security.*Test' test");
        }
        if (normalized.contains("ricbot/domain/agent/")) {
            out.add("./mvnw -q -Dtest='ricbot.domain.agent.*Test' test");
        }
        if (normalized.contains("ricbot/domain/memory/")) {
            out.add("./mvnw -q -Dtest='ricbot.domain.memory.*Test' test");
        }
        if (normalized.contains("ricbot/domain/rag/")) {
            out.add("./mvnw -q -Dtest='ricbot.domain.rag.*Test' test");
        }
        if (normalized.contains("ricbot/domain/note/")) {
            out.add("./mvnw -q -Dtest='ricbot.domain.note.*Test' test");
        }
        if (normalized.contains("ricbot/tool/filesystem/")) {
            out.add("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test");
        }
        if (normalized.contains("ricbot/tool/process/")) {
            out.add("./mvnw -q -Dtest='ricbot.tool.process.*Test' test");
        }
        if (normalized.contains("ricbot/tool/api/")) {
            out.add("./mvnw -q -Dtest='ricbot.tool.api.*Test' test");
        }
        if (isBuildOrConfigPath(normalized)) {
            out.add("./mvnw -q test");
        }
        if (out.isEmpty()) {
            out.add("./mvnw -q -Dtest='ricbot.tool.*.*Test' test");
        }
        return List.copyOf(out);
    }

    private List<String> affectedAreas(String path) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String normalized = pathKey(path);
        if (normalized.contains("ricbot/domain/security/")) {
            out.add("domain.security");
        }
        if (normalized.contains("ricbot/domain/agent/")) {
            out.add("domain.agent");
        }
        if (normalized.contains("ricbot/domain/memory/")) {
            out.add("domain.memory");
        }
        if (normalized.contains("ricbot/domain/rag/")) {
            out.add("domain.rag");
        }
        if (normalized.contains("ricbot/domain/note/")) {
            out.add("domain.note");
        }
        if (normalized.contains("ricbot/tool/filesystem/")) {
            out.add("tool.filesystem");
        }
        if (normalized.contains("ricbot/tool/process/")) {
            out.add("tool.process");
        }
        if (normalized.contains("ricbot/tool/api/")) {
            out.add("tool.api");
        }
        if (normalized.contains("provider")) {
            out.add("llm.provider");
        }
        if (isBuildOrConfigPath(normalized)) {
            out.add("build.config");
        }
        if (normalized.contains(".github/workflows/")) {
            out.add("ci");
        }
        if (out.isEmpty()) {
            out.add("workspace");
        }
        return List.copyOf(out);
    }

    private CommandRiskLevel riskLevel(
            CommandRiskLevel baseRiskLevel,
            LineDiff diff,
            boolean securitySensitive,
            boolean configChanges,
            List<String> suspiciousChanges
    ) {
        CommandRiskLevel level = baseRiskLevel != null ? baseRiskLevel : CommandRiskLevel.MEDIUM;
        if (diff.largeDeletion() || securitySensitive || configChanges || suspiciousChanges.contains("validation or risk checks removed")) {
            return max(level, CommandRiskLevel.HIGH);
        }
        return level;
    }

    private String rollbackHint(String path, ChangeKind kind) {
        if (kind == ChangeKind.CREATE) {
            return "rm " + path;
        }
        if (path == null || path.isBlank() || "<unknown>".equals(path)) {
            return "restore the previous content from version control or backup";
        }
        return "git checkout -- " + path + " (if tracked); otherwise restore the previous content manually";
    }

    private String summary(ChangeKind kind, String path, LineDiff diff) {
        String action = switch (kind) {
            case CREATE -> "Created";
            case OVERWRITE -> "Overwrote";
            case EDIT -> "Edited";
        };
        return action + " " + path + " (+" + diff.addedLines() + "/-" + diff.deletedLines() + ")";
    }

    private boolean hasSecuritySensitiveChanges(String path) {
        String normalized = pathKey(path);
        return normalized.contains("ricbot/domain/security/")
                || normalized.endsWith("approvalservice.java")
                || normalized.endsWith("commandriskanalyzer.java")
                || normalized.endsWith("networksecurity.java");
    }

    private boolean hasConfigChanges(String path) {
        String normalized = pathKey(path);
        return isBuildOrConfigPath(normalized) || normalized.endsWith("config.java");
    }

    private boolean isCoreExecutionPath(String path) {
        return path.contains("provider")
                || path.endsWith("agentloop.java")
                || path.endsWith("toolregistry.java")
                || path.endsWith("config.java");
    }

    private boolean isBuildOrConfigPath(String path) {
        return path.endsWith("pom.xml")
                || path.endsWith("build.gradle")
                || path.endsWith("ricbot.config.json")
                || path.contains("/.github/workflows/")
                || path.startsWith(".github/workflows/");
    }

    private boolean looksSensitivePath(String path) {
        return path.endsWith(".env") || path.contains("secret") || path.contains("credential");
    }

    private boolean removedValidationLines(List<String> removedLines) {
        for (String line : removedLines) {
            String lower = lower(line);
            for (String keyword : VALIDATION_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countAssertions(String value) {
        int count = 0;
        for (String line : lines(value)) {
            if (lower(line).contains("assert")) {
                count++;
            }
        }
        return count;
    }

    private boolean isTestFile(String path) {
        String normalized = pathKey(path);
        return normalized.contains("/src/test/") || normalized.endsWith("test.java");
    }

    private String testClassName(String path) {
        String slashed = toSlash(path);
        String lower = slashed.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("src/test/java/");
        String classPath = start >= 0 ? slashed.substring(start + "src/test/java/".length()) : slashed;
        if (classPath.toLowerCase(Locale.ROOT).endsWith(".java")) {
            classPath = classPath.substring(0, classPath.length() - ".java".length());
        }
        return classPath.replace('/', '.');
    }

    private LineDiff lineDiff(String before, String after) {
        List<String> beforeLines = lines(before);
        List<String> afterLines = lines(after);
        Map<String, Integer> afterCounts = counts(afterLines);
        List<String> removed = new ArrayList<>();
        for (String line : beforeLines) {
            int count = afterCounts.getOrDefault(line, 0);
            if (count > 0) {
                afterCounts.put(line, count - 1);
            } else {
                removed.add(line);
            }
        }

        Map<String, Integer> beforeCounts = counts(beforeLines);
        int added = 0;
        for (String line : afterLines) {
            int count = beforeCounts.getOrDefault(line, 0);
            if (count > 0) {
                beforeCounts.put(line, count - 1);
            } else {
                added++;
            }
        }
        int deleted = removed.size();
        return new LineDiff(added, deleted, List.copyOf(removed), deleted >= 50 || (deleted >= 20 && deleted > added * 2));
    }

    private Map<String, Integer> counts(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : lines) {
            counts.put(line, counts.getOrDefault(line, 0) + 1);
        }
        return counts;
    }

    private List<String> lines(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String[] raw = value.split("\\R", -1);
        int length = raw.length;
        if (length > 0 && raw[length - 1].isEmpty()) {
            length--;
        }
        List<String> out = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            out.add(raw[i]);
        }
        return out;
    }

    private String displayPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "<unknown>";
        }
        try {
            Path raw = Path.of(rawPath);
            if (workspace != null) {
                Path absolute = raw.isAbsolute() ? raw.toAbsolutePath().normalize() : workspace.resolve(raw).normalize();
                if (absolute.startsWith(workspace)) {
                    return toSlash(workspace.relativize(absolute).toString());
                }
            }
            return toSlash(rawPath);
        } catch (InvalidPathException e) {
            return rawPath;
        }
    }

    private String pathKey(String value) {
        return toSlash(value).toLowerCase(Locale.ROOT);
    }

    private String toSlash(String value) {
        return value != null ? value.replace('\\', '/') : "";
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String joinOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private CommandRiskLevel max(CommandRiskLevel left, CommandRiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private enum ChangeKind {
        CREATE,
        OVERWRITE,
        EDIT
    }

    private record LineDiff(int addedLines, int deletedLines, List<String> removedLines, boolean largeDeletion) {
        private LineDiff {
            removedLines = removedLines != null ? List.copyOf(removedLines) : List.of();
        }
    }
}
