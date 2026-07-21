package ricbot.domain.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MemoryRetriever {
    public List<ScoredMemory> score(List<MemoryEntry> entries, String query, String taskGoal) {
        List<MemoryEntry> source = entries != null ? entries : List.of();
        String combined = (query != null ? query : "") + "\n" + (taskGoal != null ? taskGoal : "");
        Set<String> queryTokens = tokenize(combined);
        List<ScoredMemory> out = new ArrayList<>();
        for (MemoryEntry entry : source) {
            if (entry == null || !entry.isRecallable()) {
                continue;
            }
            double lexical = queryTokens.isEmpty() ? 0.15d : weightedRecallScore(queryTokens, entry);
            double relevance = lexical;
            double importance = (entry.getImportance() * 0.7d) + (entry.getConfidence() * 0.3d);
            double recency = recencyScore(entry);
            double access = accessScore(entry);
            double typeBoost = memoryTypeBoost(entry);
            double score = (relevance * 4.0d)
                    + (importance * 2.0d)
                    + (recency * 1.2d)
                    + (access * 0.9d)
                    + typeBoost;
            out.add(new ScoredMemory(entry, score, relevance, recency, importance, access));
        }
        out.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return out;
    }

    private double weightedRecallScore(Set<String> queryTokens, MemoryEntry entry) {
        double summary = overlapScore(queryTokens, tokenize(entry.getSummary())) * 0.45d;
        double details = overlapScore(queryTokens, tokenize(entry.getDetails())) * 0.25d;
        double tags = overlapScore(queryTokens, tokenize(String.join(" ", entry.getTags()))) * 0.18d;
        double aliases = overlapScore(queryTokens, tokenize(String.join(" ", entry.getAliases()))) * 0.12d;
        return clamp(summary + details + tags + aliases, 0d, 1d);
    }

    private double recencyScore(MemoryEntry entry) {
        Instant reference = parseInstant(entry.getLastUsedAt());
        if (reference == null) {
            reference = parseInstant(entry.getUpdatedAt());
        }
        if (reference == null) {
            reference = parseInstant(entry.getCreatedAt());
        }
        if (reference == null) {
            return 0.25d;
        }
        long ageDays = Math.max(0, Duration.between(reference, Instant.now()).toDays());
        return 1.0d / (1.0d + (ageDays / 14.0d));
    }

    private double accessScore(MemoryEntry entry) {
        if (entry.getAccessCount() <= 0) {
            return 0d;
        }
        return clamp(Math.log1p(entry.getAccessCount()) / Math.log(12), 0d, 1d);
    }

    private double memoryTypeBoost(MemoryEntry entry) {
        return switch (entry.getMemoryType()) {
            case SEMANTIC -> 0.35d;
            case EPISODIC -> 0.22d;
            case WORKING -> 0.12d;
            case PERCEPTUAL -> 0.08d;
        };
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        addCjkNgrams(normalized, out);
        return out;
    }

    private double overlapScore(Set<String> queryTokens, Set<String> contentTokens) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0d;
        }
        long hits = contentTokens.stream().filter(queryTokens::contains).count();
        double queryCoverage = (double) hits / Math.max(1d, queryTokens.size());
        double contentCoverage = (double) hits / Math.max(1d, Math.min(contentTokens.size(), queryTokens.size() * 2));
        return (queryCoverage * 0.75d) + (contentCoverage * 0.25d);
    }

    private void addCjkNgrams(String text, Set<String> out) {
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk.append(ch);
            } else {
                addNgrams(cjk, out);
                cjk.setLength(0);
            }
        }
        addNgrams(cjk, out);
    }

    private void addNgrams(StringBuilder cjk, Set<String> out) {
        int len = cjk.length();
        for (int n : List.of(2, 3)) {
            if (len < n) {
                continue;
            }
            for (int i = 0; i <= len - n; i++) {
                out.add(cjk.substring(i, i + n));
            }
        }
    }

    private boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ScoredMemory(
            MemoryEntry entry,
            double score,
            double relevanceScore,
            double recencyScore,
            double importanceScore,
            double accessScore
    ) {
    }
}
