package ricbot.infra.template;


import ricbot.infra.fs.DisplayPathUtils;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具提示格式化器
 */
public final class ToolHintFormatter {

    private static final Map<String, FormatSpec> TOOL_FORMATS = Map.of(
            "read_file", new FormatSpec(List.of("path", "file_path"), "read %s", true, false),
            "write_file", new FormatSpec(List.of("path", "file_path"), "write %s", true, false),
            "edit_file", new FormatSpec(List.of("file_path", "path"), "edit %s", true, false),
            "glob", new FormatSpec(List.of("pattern"), "glob \"%s\"", false, false),
            "grep", new FormatSpec(List.of("pattern"), "grep \"%s\"", false, false),
            "exec", new FormatSpec(List.of("command"), "$ %s", false, true),
            "list_dir", new FormatSpec(List.of("path"), "ls %s", true, false)
    );

    private static final Pattern PATH_IN_CMD_RE = Pattern.compile(
            "\"(?<double>(?:[A-Za-z]:[/\\\\]|~/|/)[^\"]+)\"" +
                    "|'(?<single>(?:[A-Za-z]:[/\\\\]|~/|/)[^']+)'" +
                    "|(?<bare>(?:[A-Za-z]:[/\\\\]|~/|(?<=\\s)/)[^\\s;&|<>\"']+)"
    );

    private ToolHintFormatter() {
    }

    public static String formatToolHints(List<ToolCallRequest> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }

        List<String> formatted = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            FormatSpec spec = TOOL_FORMATS.get(tc.getName());
            if (spec != null) {
                formatted.add(formatKnown(tc, spec));
            } else {
                formatted.add(formatFallback(tc));
            }
        }

        List<CountedHint> hints = new ArrayList<>();
        for (String hint : formatted) {
            if (!hints.isEmpty() && hints.get(hints.size() - 1).hint.equals(hint)) {
                hints.get(hints.size() - 1).count++;
            } else {
                hints.add(new CountedHint(hint, 1));
            }
        }

        List<String> out = new ArrayList<>();
        for (CountedHint h : hints) {
            out.add(h.count > 1 ? h.hint + " × " + h.count : h.hint);
        }
        return String.join(", ", out);
    }

    private static String formatKnown(ToolCallRequest tc, FormatSpec spec) {
        String value = extractArg(tc, spec.keyArgs);
        if (value == null) {
            return tc.getName();
        }

        if (spec.isPath) {
            value = DisplayPathUtils.abbreviatePath(value, 40);
        } else if (spec.isCommand) {
            value = abbreviateCommand(value, 60);
        } else if (value.length() > 40) {
            value = value.substring(0, 40) + "…";
        }

        return spec.template.formatted(value);
    }

    private static String formatFallback(ToolCallRequest tc) {
        String value = extractAnyString(tc.getArguments());
        if (value == null) {
            return tc.getName();
        }
        if (value.length() > 40) {
            value = value.substring(0, 40) + "…";
        }
        return tc.getName() + "(\"" + value + "\")";
    }

    private static String extractArg(ToolCallRequest tc, List<String> keys) {
        Map<String, Object> args = tc.getArguments();
        if (args == null) {
            return null;
        }
        for (String key : keys) {
            Object v = args.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return extractAnyString(args);
    }

    private static String extractAnyString(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        for (Object v : args.values()) {
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String abbreviateCommand(String cmd, int maxLen) {
        if (cmd == null) {
            return "";
        }
        Matcher m = PATH_IN_CMD_RE.matcher(cmd);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = firstNonNull(m.group("double"), m.group("single"), m.group("bare"));
            String repl = DisplayPathUtils.abbreviatePath(raw, 30);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        String out = sb.toString().trim();
        return out.length() <= maxLen ? out : out.substring(0, maxLen) + "…";
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null) return v;
        }
        return "";
    }

    private record FormatSpec(List<String> keyArgs, String template, boolean isPath, boolean isCommand) {}
    
    private static class CountedHint {
        String hint; int count;
        CountedHint(String hint, int count) { this.hint = hint; this.count = count; }
    }
}
