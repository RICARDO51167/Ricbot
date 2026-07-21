package ricbot.domain.security;

import ricbot.infra.security.NetworkSecurity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandRiskAnalyzer {
    private static final Pattern TOKEN_SPLIT = Pattern.compile("\\s+");
    private final Path workspace;

    public CommandRiskAnalyzer(Path workspace) {
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : null;
    }

    public RiskAssessment analyzeExec(String command, String workingDir) {
        String cmd = command != null ? command.trim() : "";
        String lower = cmd.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        Set<String> paths = extractPaths(cmd);

        if (cmd.isBlank()) {
            return RiskAssessment.of(CommandRiskLevel.BLOCKED, List.of("empty command"), cmd, "exec", List.copyOf(paths));
        }
        if (containsBlockedPattern(lower)) {
            reasons.add("blocked command pattern");
            return RiskAssessment.of(CommandRiskLevel.BLOCKED, reasons, cmd, "exec", List.copyOf(paths));
        }
        if (NetworkSecurity.containsInternalUrl(cmd) || containsMetadataAddress(lower)) {
            reasons.add("internal or metadata network target");
            return RiskAssessment.of(CommandRiskLevel.BLOCKED, reasons, cmd, "exec", List.copyOf(paths));
        }
        if (hasWorkspaceEscape(paths, workingDir)) {
            reasons.add("path outside workspace");
            return RiskAssessment.of(CommandRiskLevel.BLOCKED, reasons, cmd, "exec", List.copyOf(paths));
        }
        if (containsHighPattern(lower)) {
            reasons.add("dangerous command requires explicit approval");
            return RiskAssessment.of(CommandRiskLevel.HIGH, reasons, cmd, "exec", List.copyOf(paths));
        }
        if (containsMediumPattern(lower)) {
            reasons.add("command may modify workspace state");
            return RiskAssessment.of(CommandRiskLevel.MEDIUM, reasons, cmd, "exec", List.copyOf(paths));
        }
        if (containsLowPattern(lower)) {
            reasons.add("build or test command");
            return RiskAssessment.of(CommandRiskLevel.LOW, reasons, cmd, "exec", List.copyOf(paths));
        }
        if (containsSafePattern(lower)) {
            reasons.add("read-only command");
            return RiskAssessment.of(CommandRiskLevel.SAFE, reasons, cmd, "exec", List.copyOf(paths));
        }
        reasons.add("unknown shell command");
        return RiskAssessment.of(CommandRiskLevel.MEDIUM, reasons, cmd, "exec", List.copyOf(paths));
    }

    public RiskAssessment analyzeTool(String toolName, String path) {
        String tool = toolName != null ? toolName : "";
        List<String> paths = path == null || path.isBlank() ? List.of() : List.of(path);
        List<String> reasons = new ArrayList<>();
        if (hasWorkspaceEscape(new LinkedHashSet<>(paths), null)) {
            reasons.add("path outside workspace");
            return RiskAssessment.of(CommandRiskLevel.BLOCKED, reasons, "", tool, paths);
        }
        String lower = tool.toLowerCase(Locale.ROOT);
        if (lower.contains("delete") || lower.equals("rm")) {
            reasons.add("delete-like tool");
            return RiskAssessment.of(CommandRiskLevel.HIGH, reasons, "", tool, paths);
        }
        if ("write_file".equals(lower) || "edit_file".equals(lower)) {
            reasons.add("file modification tool");
            return RiskAssessment.of(CommandRiskLevel.MEDIUM, reasons, "", tool, paths);
        }
        reasons.add("read-only or unknown low-risk tool");
        return RiskAssessment.of(CommandRiskLevel.SAFE, reasons, "", tool, paths);
    }

    private boolean containsBlockedPattern(String lower) {
        return matches(lower,
                "(^|[;&|]\\s*)sudo\\b",
                "\\bdd\\b",
                "\\bmkfs\\b",
                "\\bshutdown\\b",
                "\\breboot\\b",
                "\\brm\\s+-rf\\s+/(?:\\s|$)",
                "\\brm\\s+-fr\\s+/(?:\\s|$)"
        );
    }

    private boolean containsMetadataAddress(String lower) {
        return lower.contains("169.254.169.254")
                || lower.contains("metadata.google.internal")
                || lower.contains("100.100.100.200");
    }

    private boolean containsHighPattern(String lower) {
        return matches(lower,
                "(^|[;&|]\\s*)rm\\b",
                "(^|[;&|]\\s*)chmod\\b",
                "(^|[;&|]\\s*)chown\\b",
                "(^|[;&|]\\s*)curl\\b",
                "(^|[;&|]\\s*)wget\\b",
                "(^|[;&|]\\s*)ssh\\b",
                "(^|[;&|]\\s*)scp\\b",
                "\\bgit\\s+reset\\s+--hard\\b"
        );
    }

    private boolean containsMediumPattern(String lower) {
        return matches(lower,
                "(^|[;&|]\\s*)mkdir\\b",
                "(^|[;&|]\\s*)touch\\b",
                "\\bgit\\s+checkout\\b",
                "\\bgit\\s+restore\\b",
                "\\bmv\\b",
                "\\bcp\\b",
                ">\\s*[^\\s]+"
        );
    }

    private boolean containsLowPattern(String lower) {
        return matches(lower,
                "\\bmvnw?\\s+(?:-q\\s+)?(?:test|package|verify)\\b",
                "\\bnpm\\s+test\\b",
                "\\bgradle\\s+test\\b",
                "\\bjava\\s+-version\\b"
        );
    }

    private boolean containsSafePattern(String lower) {
        String first = firstCommand(lower);
        if (Set.of("ls", "pwd", "cat", "head", "tail", "grep", "find").contains(first)) {
            return true;
        }
        return lower.matches(".*\\bgit\\s+(status|diff|log|show)\\b.*");
    }

    private String firstCommand(String lower) {
        String cleaned = lower.stripLeading();
        if (cleaned.isBlank()) {
            return "";
        }
        String firstSegment = cleaned.split("[;&|]", 2)[0].strip();
        String[] tokens = TOKEN_SPLIT.split(firstSegment);
        return tokens.length > 0 ? tokens[0] : "";
    }

    private boolean matches(String lower, String... patterns) {
        for (String pattern : patterns) {
            if (Pattern.compile(pattern).matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractPaths(String command) {
        Set<String> result = new LinkedHashSet<>();
        if (command == null || command.isBlank()) {
            return result;
        }
        Matcher matcher = Pattern.compile("(?:^|\\s)(/[^\\s\"'|><;]+|~[^\\s\"'|><;]+|\\.\\.?/[^\\s\"'|><;]+)").matcher(command);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private boolean hasWorkspaceEscape(Set<String> paths, String workingDir) {
        if (workspace == null || paths == null || paths.isEmpty()) {
            return false;
        }
        Path cwd = workingDir != null && !workingDir.isBlank()
                ? Path.of(workingDir).toAbsolutePath().normalize()
                : workspace;
        for (String raw : paths) {
            try {
                Path path = raw.startsWith("~")
                        ? Path.of(System.getProperty("user.home") + raw.substring(1)).toAbsolutePath().normalize()
                        : Path.of(raw);
                if (!path.isAbsolute()) {
                    path = cwd.resolve(path).toAbsolutePath().normalize();
                } else {
                    path = path.toAbsolutePath().normalize();
                }
                if (!path.equals(workspace) && !path.startsWith(workspace)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
