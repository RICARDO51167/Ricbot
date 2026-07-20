package ricbot.infra.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextParsingUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Pattern YAML_LIST_ITEM = Pattern.compile("^\\s*-\\s*(.+?)\\s*$");

    private TextParsingUtils() {
    }

    public static String normalizeWhitespace(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace('\u00A0', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .trim();
    }

    public static String normalizeQuoted(String raw) {
        String s = normalizeWhitespace(raw);
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.length() >= 2 && s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`') {
            s = s.substring(1, s.length() - 1).trim();
        }
        return normalizeWhitespace(s);
    }

    public static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            Set<String> out = new LinkedHashSet<>();
            for (Object item : list) {
                String normalized = normalizeQuoted(item != null ? String.valueOf(item) : null);
                if (!normalized.isBlank()) {
                    out.add(normalized);
                }
            }
            return List.copyOf(out);
        }
        return parseStringList(String.valueOf(raw));
    }

    public static List<String> parseStringList(String raw) {
        String s = normalizeWhitespace(raw);
        if (s.isEmpty()) {
            return List.of();
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            try {
                List<String> parsed = MAPPER.readValue(s, new TypeReference<>() {});
                Set<String> out = new LinkedHashSet<>();
                for (String value : parsed) {
                    String normalized = normalizeQuoted(value);
                    if (!normalized.isBlank()) {
                        out.add(normalized);
                    }
                }
                return List.copyOf(out);
            } catch (Exception ignored) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.contains("\n")) {
            List<String> yaml = parseYamlList(s);
            if (!yaml.isEmpty()) {
                return yaml;
            }
        }
        return splitCsvLike(s);
    }

    private static List<String> parseYamlList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            Matcher matcher = YAML_LIST_ITEM.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String value = normalizeQuoted(matcher.group(1));
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> splitCsvLike(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || raw.charAt(i - 1) != '\\')) {
                if (!inQuotes) {
                    inQuotes = true;
                    quote = c;
                    continue;
                }
                if (quote == c) {
                    inQuotes = false;
                    continue;
                }
            }
            if (!inQuotes && c == ',') {
                appendNormalized(out, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        appendNormalized(out, current.toString());
        return List.copyOf(out);
    }

    private static void appendNormalized(List<String> out, String raw) {
        String normalized = normalizeQuoted(raw);
        if (!normalized.isBlank() && !out.contains(normalized)) {
            out.add(normalized);
        }
    }
}
