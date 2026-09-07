package ricbot.domain.agent;

import ricbot.domain.agent.dto.ContextQualityReport;
import ricbot.domain.agent.dto.ContextSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 提示词上下文包，用于管理和渲染不同部分的上下文信息
public final class PromptContextBundle {
    private static final int DEFAULT_SECTION_ITEM_LIMIT = 8;
    private static final int DEFAULT_SECTION_CHAR_LIMIT = 1_600;
    private static final int BASE_TOTAL_CHAR_LIMIT = 7_000;
    private static final int MIN_TOTAL_CHAR_LIMIT = 4_000;
    private static final int MAX_TOTAL_CHAR_LIMIT = 24_000;
    private static final int BASE_CONTEXT_WINDOW_TOKENS = 32_000;
    private static final String TRUNCATED_MARKER = "... [preview shortened; recover from the recorded ContextSource shown by /context]";

    // 定义上下文部分的固定顺序
    static final List<String> ORDER = List.of(
            "recent_history",   // 最近的历史记录
            "task_state",       // 任务状态
            "user_profile",     // 用户画像
            "memory_recall",    // 记忆召回
            "project_notes",    // 项目笔记
            "workspace_knowledge", // 工作区知识库
            "workspace_session", // active workspace session
            "trace_context", // Coding Harness trace source
            "tool_trace"        // 工具调用轨迹
    );

    private static final Map<String, SectionBudget> SECTION_BUDGETS = sectionBudgets();

    // 使用 LinkedHashMap 保持插入顺序，存储各个部分的内容列表
    private final Map<String, List<String>> sections = new LinkedHashMap<>();
    private final int totalCharLimit;
    private final Map<String, SectionBudget> sectionBudgets;
    private final Map<String, List<Double>> relevanceScores = new LinkedHashMap<>();
    private final Map<String, List<ContextSource>> sources = new LinkedHashMap<>();

    // 构造函数，初始化所有预定义的上下文部分为空列表
    public PromptContextBundle() {
        this(BASE_TOTAL_CHAR_LIMIT, SECTION_BUDGETS);
    }

    private PromptContextBundle(int totalCharLimit, Map<String, SectionBudget> sectionBudgets) {
        this.totalCharLimit = totalCharLimit;
        this.sectionBudgets = sectionBudgets != null ? sectionBudgets : SECTION_BUDGETS;
        for (String key : ORDER) {
            sections.put(key, new ArrayList<>());
        }
    }

    static PromptContextBundle forContextWindow(int contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            return new PromptContextBundle();
        }
        double scale = Math.sqrt((double) contextWindowTokens / BASE_CONTEXT_WINDOW_TOKENS);
        int total = clamp((int) Math.round(BASE_TOTAL_CHAR_LIMIT * scale), MIN_TOTAL_CHAR_LIMIT, MAX_TOTAL_CHAR_LIMIT);

        Map<String, SectionBudget> budgets = new LinkedHashMap<>();
        for (Map.Entry<String, SectionBudget> entry : SECTION_BUDGETS.entrySet()) {
            SectionBudget base = entry.getValue();
            int maxItems = clamp((int) Math.round(base.maxItems() * scale), Math.min(2, base.maxItems()), Math.max(base.maxItems(), 24));
            int maxChars = clamp((int) Math.round(base.maxChars() * scale), 500, 8_000);
            budgets.put(entry.getKey(), new SectionBudget(maxItems, maxChars));
        }
        return new PromptContextBundle(total, budgets);
    }

    // 向指定部分添加一项内容
    void addItem(String section, String item) {
        addItem(section, item, null);
    }

    void addItem(String section, String item, Double relevanceScore) {
        addItem(section, item, relevanceScore, null);
    }

    void addItem(String section, String item, Double relevanceScore, ContextSource source) {
        // 如果部分名或内容为空，则直接返回
        if (section == null || item == null || item.isBlank()) {
            return;
        }
        String normalized = item.trim();
        List<String> target = sections.computeIfAbsent(section, ignored -> new ArrayList<>());
        if (!target.contains(normalized)) {
            target.add(normalized);
            if (relevanceScore != null) {
                relevanceScores.computeIfAbsent(section, ignored -> new ArrayList<>()).add(clampDouble(relevanceScore, 0d, 1d));
            }
            if (source != null) {
                sources.computeIfAbsent(section, ignored -> new ArrayList<>()).add(source);
            }
        }
    }

    // 判断所有部分是否都为空
    boolean isEmpty() {
        return sections.values().stream().allMatch(List::isEmpty);
    }

    // 渲染上下文为 Markdown 格式的字符串
    String render() {
        StringBuilder sb = new StringBuilder();
        // 按照预定义的顺序遍历各个部分
        for (String key : ORDER) {
            List<String> items = sections.get(key);
            // 如果该部分没有内容，则跳过
            if (items == null || items.isEmpty()) {
                continue;
            }
            // 如果 StringBuilder 不为空，添加两个换行符以分隔不同部分
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            // 添加部分标题
            sb.append("## ").append(key).append("\n");
            SectionBudget budget = sectionBudgets.getOrDefault(
                    key,
                    new SectionBudget(DEFAULT_SECTION_ITEM_LIMIT, DEFAULT_SECTION_CHAR_LIMIT)
            );
            int sectionChars = 0;
            int emitted = 0;
            for (String item : items) {
                if (emitted >= budget.maxItems()) {
                    appendBudgetNotice(sb, items.size() - emitted);
                    break;
                }
                if (sb.length() >= totalCharLimit) {
                    appendBudgetNotice(sb, items.size() - emitted);
                    break;
                }

                String rendered = "- " + item + "\n";
                int remainingSection = budget.maxChars() - sectionChars;
                int remainingTotal = totalCharLimit - sb.length();
                int allowed = Math.min(remainingSection, remainingTotal);
                if (allowed <= 0) {
                    appendBudgetNotice(sb, items.size() - emitted);
                    break;
                }

                if (rendered.length() > allowed) {
                    sb.append(truncateLine(rendered, allowed));
                    appendBudgetNotice(sb, items.size() - emitted);
                    break;
                }

                sb.append(rendered);
                sectionChars += rendered.length();
                emitted++;
            }
        }
        // 返回修剪后的字符串，去除首尾空白
        return sb.toString().trim();
    }

    Map<String, Object> budgetTrace() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_char_limit", totalCharLimit);
        List<Map<String, Object>> sectionReports = new ArrayList<>();
        int estimatedTotalChars = 0;
        for (String key : ORDER) {
            List<String> items = sections.getOrDefault(key, List.of());
            SectionBudget budget = sectionBudgets.getOrDefault(
                    key,
                    new SectionBudget(DEFAULT_SECTION_ITEM_LIMIT, DEFAULT_SECTION_CHAR_LIMIT)
            );
            int emitted = 0;
            int emittedChars = 0;
            boolean truncated = false;
            for (String item : items) {
                if (emitted >= budget.maxItems() || estimatedTotalChars >= totalCharLimit) {
                    truncated = true;
                    break;
                }
                int renderedChars = ("- " + item + "\n").length();
                int remainingSection = budget.maxChars() - emittedChars;
                int remainingTotal = totalCharLimit - estimatedTotalChars;
                int allowed = Math.min(remainingSection, remainingTotal);
                if (allowed <= 0) {
                    truncated = true;
                    break;
                }
                emitted++;
                int used = Math.min(renderedChars, allowed);
                emittedChars += used;
                estimatedTotalChars += used;
                if (renderedChars > allowed) {
                    truncated = true;
                    break;
                }
            }

            Map<String, Object> section = new LinkedHashMap<>();
            section.put("name", key);
            section.put("candidates", items.size());
            section.put("emitted", emitted);
            section.put("omitted", Math.max(0, items.size() - emitted));
            section.put("max_items", budget.maxItems());
            section.put("max_chars", budget.maxChars());
            section.put("estimated_chars", emittedChars);
            section.put("estimated_tokens", estimateTokens(emittedChars));
            section.put("truncated", truncated || emitted < items.size());
            section.put("avg_relevance", averageRelevance(key));
            sectionReports.add(section);
        }
        out.put("estimated_rendered_chars", estimatedTotalChars);
        out.put("estimated_rendered_tokens", estimateTokens(estimatedTotalChars));
        out.put("sections", sectionReports);
        out.put("sources", sourceTrace());
        return out;
    }

    Map<String, List<Map<String, Object>>> sourceTrace() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String key : ORDER) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ContextSource source : sources.getOrDefault(key, List.of())) {
                rows.add(source.toMap());
            }
            out.put(key, rows);
        }
        return out;
    }

    ContextQualityReport qualityReport() {
        Map<String, Object> trace = budgetTrace();
        int estimatedChars = intValue(trace.get("estimated_rendered_chars"));
        int totalTokens = Math.max(0, (int) Math.ceil(estimatedChars / 4.0d));
        double budgetUsageRate = totalCharLimit > 0 ? (double) estimatedChars / (double) totalCharLimit : 0d;

        int candidates = 0;
        int omitted = 0;
        boolean compressionApplied = false;
        Object sectionsObj = trace.get("sections");
        if (sectionsObj instanceof List<?> reports) {
            for (Object item : reports) {
                if (!(item instanceof Map<?, ?> report)) {
                    continue;
                }
                candidates += intValue(report.get("candidates"));
                omitted += intValue(report.get("omitted"));
                compressionApplied = compressionApplied || boolValue(report.get("truncated"));
            }
        }

        int duplicateCandidates = 0;
        int totalItems = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<String> items : sections.values()) {
            for (String item : items) {
                totalItems++;
                String normalized = item.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (!seen.add(normalized)) {
                    duplicateCandidates++;
                }
            }
        }

        int staleItems = 0;
        for (String item : sections.getOrDefault("recent_history", List.of())) {
            String lower = item.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("archived") || lower.contains("session summary") || lower.contains("历史")) {
                staleItems++;
            }
        }

        int noisyToolItems = 0;
        for (String item : sections.getOrDefault("tool_trace", List.of())) {
            String lower = item.toLowerCase(java.util.Locale.ROOT);
            if (item.length() > 320 || lower.contains("error") || lower.contains("exception") || lower.contains("[truncated]")) {
                noisyToolItems++;
            }
        }

        boolean missingTaskState = sections.getOrDefault("task_state", List.of()).isEmpty();
        List<String> suggestions = new ArrayList<>();
        if (budgetUsageRate > 0.9d || omitted > 0) {
            suggestions.add("reduce low-value context or raise context window");
        }
        if (missingTaskState) {
            suggestions.add("persist task_state before long-running work");
        }
        if (totalItems > 0 && (double) duplicateCandidates / totalItems > 0.2d) {
            suggestions.add("dedupe repeated context candidates");
        }
        if (!sections.getOrDefault("tool_trace", List.of()).isEmpty()
                && (double) noisyToolItems / Math.max(1, sections.get("tool_trace").size()) > 0.5d) {
            suggestions.add("compress noisy tool results");
        }

        return new ContextQualityReport(
                totalTokens,
                clampDouble(budgetUsageRate, 0d, 1d),
                averageRelevance(),
                totalItems > 0 ? (double) duplicateCandidates / totalItems : 0d,
                candidates > 0 ? (double) staleItems / candidates : 0d,
                sections.getOrDefault("tool_trace", List.of()).isEmpty()
                        ? 0d
                        : (double) noisyToolItems / sections.get("tool_trace").size(),
                missingTaskState,
                compressionApplied,
                suggestions
        );
    }

    private double averageRelevance() {
        double total = 0d;
        int count = 0;
        for (List<Double> values : relevanceScores.values()) {
            for (Double value : values) {
                if (value == null) {
                    continue;
                }
                total += value;
                count++;
            }
        }
        return count > 0 ? total / count : 0d;
    }

    private double averageRelevance(String section) {
        double total = 0d;
        int count = 0;
        for (Double value : relevanceScores.getOrDefault(section, List.of())) {
            if (value == null) {
                continue;
            }
            total += value;
            count++;
        }
        return count > 0 ? Math.round((total / count) * 1000.0d) / 1000.0d : 0d;
    }

    private static int estimateTokens(int chars) {
        return Math.max(0, (int) Math.ceil(chars / 4.0d));
    }

    private static void appendBudgetNotice(StringBuilder sb, int remainingItems) {
        if (remainingItems > 0) {
            sb.append("- ").append(TRUNCATED_MARKER).append(" ")
                    .append(remainingItems)
                    .append(" more item(s)\n");
        }
    }

    private static String truncateLine(String value, int maxChars) {
        if (maxChars <= TRUNCATED_MARKER.length() + 4) {
            return "- " + TRUNCATED_MARKER + "\n";
        }
        String prefix = value.substring(0, Math.max(0, maxChars - TRUNCATED_MARKER.length() - 1)).stripTrailing();
        return prefix + " " + TRUNCATED_MARKER + "\n";
    }

    int totalCharLimit() {
        return totalCharLimit;
    }

    SectionBudget sectionBudget(String section) {
        return sectionBudgets.getOrDefault(section, new SectionBudget(DEFAULT_SECTION_ITEM_LIMIT, DEFAULT_SECTION_CHAR_LIMIT));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private static Map<String, SectionBudget> sectionBudgets() {
        Map<String, SectionBudget> out = new LinkedHashMap<>();
        out.put("recent_history", new SectionBudget(3, 900));
        out.put("task_state", new SectionBudget(12, 1_200));
        out.put("user_profile", new SectionBudget(8, 1_600));
        out.put("memory_recall", new SectionBudget(8, 2_000));
        out.put("project_notes", new SectionBudget(5, 1_600));
        out.put("workspace_knowledge", new SectionBudget(5, 2_400));
        out.put("tool_trace", new SectionBudget(4, 1_200));
        return out;
    }

    record SectionBudget(int maxItems, int maxChars) {
    }
}
