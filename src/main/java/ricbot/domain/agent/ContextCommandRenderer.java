package ricbot.domain.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ContextCommandRenderer {
    private static final List<String> DISPLAY_SECTIONS = List.of(
            "recent_history",
            "task_state",
            "memory_recall",
            "project_notes",
            "workspace_knowledge",
            "workspace_session",
            "team_context",
            "subagent_summaries",
            "trace_context",
            "tool_trace"
    );

    private ContextCommandRenderer() {
    }

    static String render(Map<?, ?> trace, boolean detail, boolean sourcesOnly) {
        if (trace == null || trace.isEmpty()) {
            return "暂无 context trace。请先完成一轮普通对话后再查看 /context。";
        }
        Map<?, ?> quality = map(trace.get("context_quality"));
        Map<?, ?> budget = map(trace.get("prompt_context_budget"));
        StringBuilder sb = new StringBuilder();
        sb.append("ricbot context\n");
        appendMetric(sb, "total_tokens", firstNonNull(quality.get("totalTokens"), budget.get("estimated_rendered_tokens")));
        appendMetric(sb, "budget_usage_rate", quality.get("budgetUsageRate"));
        appendMetric(sb, "avg_relevance_score", quality.get("avgRelevanceScore"));
        appendMetric(sb, "duplicate_ratio", quality.get("duplicateRatio"));
        appendMetric(sb, "stale_context_ratio", quality.get("staleContextRatio"));
        appendMetric(sb, "tool_noise_ratio", quality.get("toolResultNoiseRatio"));
        appendMetric(sb, "compression_applied", quality.get("compressionApplied"));

        if (!sourcesOnly) {
            sb.append("\nsections\n");
            for (SectionUsage section : sectionUsages(budget)) {
                if (!DISPLAY_SECTIONS.contains(section.name())) {
                    continue;
                }
                sb.append("- ").append(section.name())
                        .append(": tokens=").append(section.tokens())
                        .append(", items=").append(section.emitted()).append("/").append(section.candidates());
                if (section.truncated()) {
                    sb.append(", truncated=true");
                }
                if (detail) {
                    sb.append(", chars=").append(section.chars())
                            .append(", max_items=").append(section.maxItems())
                            .append(", max_chars=").append(section.maxChars())
                            .append(", avg_relevance=").append(section.avgRelevance());
                }
                sb.append("\n");
            }
        }

        Map<?, ?> sources = map(budget.get("sources"));
        if (sourcesOnly || detail) {
            sb.append("\ntop sources\n");
            boolean any = false;
            for (String section : DISPLAY_SECTIONS) {
                List<Map<?, ?>> rows = sourceRows(sources.get(section));
                if (rows.isEmpty()) {
                    continue;
                }
                any = true;
                sb.append(section).append("\n");
                for (Map<?, ?> row : rows.stream().limit(detail ? 8 : 5).toList()) {
                    sb.append("- ").append(string(row.get("type")));
                    String id = string(row.get("id"));
                    String path = string(row.get("path"));
                    String label = string(row.get("label"));
                    if (!id.isBlank()) {
                        sb.append(" id=").append(id);
                    }
                    if (!path.isBlank()) {
                        sb.append(" path=").append(path);
                    }
                    if (!label.isBlank()) {
                        sb.append(" label=").append(label);
                    }
                    String subagentRole = string(row.get("subagent_role"));
                    String status = string(row.get("status"));
                    String sourceRef = string(row.get("sourceRef"));
                    String confidence = string(row.get("confidence"));
                    String reason = string(row.get("reason"));
                    if (!subagentRole.isBlank()) {
                        sb.append(" subagent_role=").append(subagentRole);
                    }
                    if (!status.isBlank()) {
                        sb.append(" status=").append(status);
                    }
                    if (!sourceRef.isBlank()) {
                        sb.append(" sourceRef=").append(sourceRef);
                    }
                    if (!confidence.isBlank()) {
                        sb.append(" confidence=").append(confidence);
                    }
                    if (!reason.isBlank()) {
                        sb.append(" reason=").append(reason);
                    }
                    Object score = row.get("score");
                    if (score != null) {
                        sb.append(" score=").append(score);
                    }
                    sb.append("\n");
                }
            }
            if (!any) {
                sb.append("- none\n");
            }
        }

        return sb.toString().trim();
    }

    private static void appendMetric(StringBuilder sb, String key, Object value) {
        sb.append(key).append(": ").append(value != null ? value : 0).append("\n");
    }

    private static List<SectionUsage> sectionUsages(Map<?, ?> budget) {
        List<SectionUsage> out = new ArrayList<>();
        Object raw = budget.get("sections");
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            int chars = intValue(map.get("estimated_chars"));
            int tokens = intValue(firstNonNull(map.get("estimated_tokens"), (int) Math.ceil(chars / 4.0d)));
            out.add(new SectionUsage(
                    string(map.get("name")),
                    intValue(map.get("candidates")),
                    intValue(map.get("emitted")),
                    intValue(map.get("max_items")),
                    intValue(map.get("max_chars")),
                    chars,
                    tokens,
                    boolValue(map.get("truncated")),
                    doubleValue(map.get("avg_relevance"))
            ));
        }
        return out;
    }

    private static List<Map<?, ?>> sourceRows(Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(map);
                }
            }
        }
        return out;
    }

    private static Map<?, ?> map(Object raw) {
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static int intValue(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static boolean boolValue(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private static double doubleValue(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw != null) {
            try {
                return Double.parseDouble(String.valueOf(raw));
            } catch (Exception ignored) {
            }
        }
        return 0d;
    }

    private record SectionUsage(
            String name,
            int candidates,
            int emitted,
            int maxItems,
            int maxChars,
            int chars,
            int tokens,
            boolean truncated,
            double avgRelevance
    ) {
    }
}
