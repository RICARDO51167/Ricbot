package ricbot.app.cli;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * CLI 模型辅助工具类，提供模型查询、建议及格式化功能。
 */
public final class CliModelHelpers {

    private static final List<ModelMeta> MODEL_DB = List.of(
            new ModelMeta("gpt-4.1", "openai", 1_048_576, List.of("openai/gpt-4.1")),
            new ModelMeta("gpt-4.1-mini", "openai", 1_048_576, List.of("openai/gpt-4.1-mini")),
            new ModelMeta("gpt-4.1-nano", "openai", 1_048_576, List.of("openai/gpt-4.1-nano")),
            new ModelMeta("gpt-4o", "openai", 128_000, List.of("openai/gpt-4o")),
            new ModelMeta("gpt-4o-mini", "openai", 128_000, List.of("openai/gpt-4o-mini")),
            new ModelMeta("o3", "openai", 200_000, List.of("openai/o3")),
            new ModelMeta("o4-mini", "openai", 200_000, List.of("openai/o4-mini")),

            new ModelMeta("claude-3-5-sonnet", "anthropic", 200_000, List.of("anthropic/claude-3-5-sonnet")),
            new ModelMeta("claude-3-5-haiku", "anthropic", 200_000, List.of("anthropic/claude-3-5-haiku")),
            new ModelMeta("claude-3-opus", "anthropic", 200_000, List.of("anthropic/claude-3-opus")),
            new ModelMeta("claude-3-sonnet", "anthropic", 200_000, List.of("anthropic/claude-3-sonnet")),

            new ModelMeta("deepseek-chat", "deepseek", 128_000, List.of("deepseek/deepseek-chat")),
            new ModelMeta("deepseek-reasoner", "deepseek", 64_000, List.of("deepseek/deepseek-reasoner")),

            new ModelMeta("qwen-max", "dashscope", 32_000, List.of("dashscope/qwen-max")),
            new ModelMeta("qwen-plus", "dashscope", 128_000, List.of("dashscope/qwen-plus")),
            new ModelMeta("qwen-turbo", "dashscope", 1_000_000, List.of("dashscope/qwen-turbo")),
            new ModelMeta("qwq-32b", "dashscope", 128_000, List.of("dashscope/qwq-32b")),

            new ModelMeta("gemini-2.5-pro", "gemini", 1_048_576, List.of("gemini/gemini-2.5-pro")),
            new ModelMeta("gemini-2.5-flash", "gemini", 1_048_576, List.of("gemini/gemini-2.5-flash")),
            new ModelMeta("gemini-2.0-flash", "gemini", 1_048_576, List.of("gemini/gemini-2.0-flash")),

            new ModelMeta("kimi-k2.5", "moonshot", 128_000, List.of("moonshot/kimi-k2.5")),
            new ModelMeta("moonshot-v1-8k", "moonshot", 8_000, List.of("moonshot/moonshot-v1-8k")),
            new ModelMeta("moonshot-v1-32k", "moonshot", 32_000, List.of("moonshot/moonshot-v1-32k")),
            new ModelMeta("moonshot-v1-128k", "moonshot", 128_000, List.of("moonshot/moonshot-v1-128k")),

            new ModelMeta("mistral-large-latest", "mistral", 128_000, List.of("mistral/mistral-large-latest")),

            new ModelMeta("llama-3.3-70b-versatile", "groq", 128_000, List.of("groq/llama-3.3-70b-versatile")),
            new ModelMeta("llama-3.1-8b-instant", "groq", 128_000, List.of("groq/llama-3.1-8b-instant"))
    );

    private CliModelHelpers() {
    }

    public static Integer getModelContextLimit(String model, String provider) {
        ModelMeta meta = findBestMatch(model, provider);
        return meta != null ? meta.contextWindowTokens() : null;
    }

    public static List<String> getModelSuggestions(String partial, String provider, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        String normalizedPartial = normalizeModelKey(partial);
        String preferredProvider = normalizeProvider(provider);
        List<Suggestion> ranked = new ArrayList<>();

        for (ModelMeta meta : MODEL_DB) {
            int score = scoreModel(meta, normalizedPartial, preferredProvider);
            if (score == Integer.MAX_VALUE) {
                continue;
            }
            ranked.add(new Suggestion(meta.name(), score));
        }

        ranked.sort(Comparator
                .comparingInt(Suggestion::score)
                .thenComparing(Suggestion::model, String.CASE_INSENSITIVE_ORDER));

        Set<String> result = new LinkedHashSet<>();
        for (Suggestion item : ranked) {
            result.add(item.model());
            if (result.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    public static String formatTokenCount(int tokens) {
        return NumberFormat.getNumberInstance(Locale.US).format(tokens);
    }

    private static ModelMeta findBestMatch(String model, String provider) {
        String normalizedModel = normalizeModelKey(model);
        if (normalizedModel.isBlank()) {
            return null;
        }
        String normalizedProvider = normalizeProvider(provider);

        ModelMeta best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ModelMeta meta : MODEL_DB) {
            int score = matchScore(meta, normalizedModel, normalizedProvider);
            if (score < bestScore) {
                bestScore = score;
                best = meta;
            }
        }
        return bestScore == Integer.MAX_VALUE ? null : best;
    }

    private static int matchScore(ModelMeta meta, String normalizedModel, String normalizedProvider) {
        boolean providerMatched = normalizedProvider.isBlank() || normalizedProvider.equals(meta.provider());

        if (normalizedModel.equals(normalizeModelKey(meta.name()))) {
            return providerMatched ? 0 : 100;
        }
        for (String alias : meta.aliases()) {
            if (normalizedModel.equals(normalizeModelKey(alias))) {
                return providerMatched ? 1 : 101;
            }
        }
        if (normalizedModel.contains("/") && normalizedModel.endsWith("/" + normalizeModelKey(meta.name()))) {
            return providerMatched ? 2 : 102;
        }
        return Integer.MAX_VALUE;
    }

    private static int scoreModel(ModelMeta meta, String normalizedPartial, String preferredProvider) {
        int providerPenalty = preferredProvider.isBlank() || preferredProvider.equals(meta.provider()) ? 0 : 20;
        if (normalizedPartial.isBlank()) {
            return providerPenalty + 30;
        }

        String name = normalizeModelKey(meta.name());
        if (name.startsWith(normalizedPartial)) {
            return providerPenalty;
        }
        if (name.contains(normalizedPartial)) {
            return providerPenalty + 5;
        }
        for (String alias : meta.aliases()) {
            String normalizedAlias = normalizeModelKey(alias);
            if (normalizedAlias.startsWith(normalizedPartial)) {
                return providerPenalty + 8;
            }
            if (normalizedAlias.contains(normalizedPartial)) {
                return providerPenalty + 10;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String normalizeModelKey(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        int slash = normalized.indexOf('/');
        if (slash > 0 && slash + 1 < normalized.length()) {
            String maybeProvider = normalized.substring(0, slash);
            String maybeModel = normalized.substring(slash + 1);
            if (!maybeProvider.isBlank() && !maybeModel.isBlank()) {
                return maybeModel;
            }
        }
        return normalized;
    }

    private static String normalizeProvider(String provider) {
        if (provider == null) {
            return "";
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        if (p.isBlank()) {
            return "";
        }
        return switch (p) {
            case "claude" -> "anthropic";
            case "gpt" -> "openai";
            case "qwen" -> "dashscope";
            default -> p;
        };
    }

    private record ModelMeta(String name, String provider, int contextWindowTokens, List<String> aliases) {
        private ModelMeta {
            Objects.requireNonNull(name, "name");
            provider = normalizeProvider(provider);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    private record Suggestion(String model, int score) {
    }
}
