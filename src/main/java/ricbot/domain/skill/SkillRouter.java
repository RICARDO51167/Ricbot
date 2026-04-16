package ricbot.domain.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能路由器
 */
public class SkillRouter {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-\\.]+)\\s*\\}\\}");
    private static final Pattern YAML_LIST_ITEM = Pattern.compile("^\\s*-\\s*(.+?)\\s*$");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final SkillsLoader skillsLoader;
    private final int maxSelected;
    private final int maxChars;

    public SkillRouter(SkillsLoader skillsLoader, Integer maxSelected, Integer maxChars) {
        this.skillsLoader = skillsLoader;
        this.maxSelected = maxSelected != null ? Math.max(0, maxSelected) : 3;
        this.maxChars = maxChars != null ? Math.max(0, maxChars) : 12000;
    }

    public SelectionResult selectAndRender(SkillRoutingContext ctx) {
        List<SkillsLoader.SkillEntry> entries = skillsLoader.listSkillEntries();

        List<SkillsLoader.SkillDocument> always = new ArrayList<>();
        List<ScoredSkill> candidates = new ArrayList<>();
        List<SkillDecision> decisions = new ArrayList<>();

        for (SkillsLoader.SkillEntry entry : entries) {
            SkillsLoader.SkillDocument doc = skillsLoader.loadSkillDocument(entry);
            if (doc == null) {
                continue;
            }

            SkillMeta meta = SkillMeta.from(doc);
            if (meta.always) {
                always.add(doc);
                continue;
            }

            ScoreResult scored = score(meta, ctx);
            int score = scored.score();
            if (score > 0) {
                candidates.add(new ScoredSkill(doc, meta, score, scored.reasons()));
            }
        }

        candidates.sort(
                Comparator.<ScoredSkill>comparingInt(ScoredSkill::score).reversed()
                        .thenComparing(Comparator.comparingInt((ScoredSkill s) -> s.meta().priority).reversed())
                        .thenComparing(s -> s.doc().entry().name())
        );

        List<SkillsLoader.SkillDocument> selected = new ArrayList<>();
        for (ScoredSkill s : candidates) {
            if (selected.size() >= maxSelected) {
                break;
            }
            selected.add(s.doc());
        }

        Map<String, String> vars = buildVariables(ctx);
        RenderAllResult rendered = renderAll(always, selected, vars);

        Set<String> includedAlways = new HashSet<>(rendered.includedAlways());
        Set<String> includedSelected = new HashSet<>(rendered.includedSelected());
        for (SkillsLoader.SkillDocument d : always) {
            String name = d.entry().name();
            decisions.add(new SkillDecision(name, 0, List.of("always=true"), true, includedAlways.contains(name)));
        }
        for (ScoredSkill s : candidates) {
            String name = s.doc().entry().name();
            decisions.add(new SkillDecision(name, s.score(), s.reasons(), false, includedSelected.contains(name)));
        }

        return new SelectionResult(
                rendered.includedAlways(),
                rendered.includedSelected(),
                rendered.text(),
                decisions,
                rendered.missingVariables()
        );
    }

    private ScoreResult score(SkillMeta meta, SkillRoutingContext ctx) {
        int s = meta.priority;
        List<String> reasons = new ArrayList<>();
        if (meta.priority != 0) {
            reasons.add("priority=" + meta.priority);
        }

        String channel = safeLower(ctx.channel());
        if (!meta.channels.isEmpty() && meta.channels.contains(channel)) {
            s += meta.weights.channelWeight();
            reasons.add("channel match +" + meta.weights.channelWeight());
        }

        String message = ctx.message() != null ? ctx.message() : "";
        String msgLower = message.toLowerCase(Locale.ROOT);
        int kwHits = 0;
        int kwCap = Math.max(1, meta.weights.maxKeywordHits());
        for (String kw : meta.keywords) {
            if (kwHits >= kwCap) {
                break;
            }
            if (matchesKeyword(msgLower, kw)) {
                kwHits++;
                s += meta.weights.keywordWeight();
                reasons.add("keyword '" + kw + "' +" + meta.weights.keywordWeight());
            }
        }

        if (meta.name != null && !meta.name.isBlank() && msgLower.contains(meta.name.toLowerCase(Locale.ROOT))) {
            s += meta.weights.nameWeight();
            reasons.add("name match +" + meta.weights.nameWeight());
        }

        if (!meta.tools.isEmpty()) {
            Set<String> requested = extractRequestedTools(ctx);
            boolean toolMatched = false;
            for (String t : meta.tools) {
                String tt = t != null ? t.trim() : "";
                if (tt.isBlank()) {
                    continue;
                }
                String tLower = tt.toLowerCase(Locale.ROOT);
                if (requested.contains(tLower) || msgLower.contains(tLower)) {
                    toolMatched = true;
                    break;
                }
            }
            if (toolMatched) {
                s += meta.weights.toolWeight();
                reasons.add("tool hinted +" + meta.weights.toolWeight());
            }
        }

        return new ScoreResult(s, reasons);
    }

    private RenderAllResult renderAll(
            List<SkillsLoader.SkillDocument> always,
            List<SkillsLoader.SkillDocument> selected,
            Map<String, String> vars
    ) {
        int budget = maxChars > 0 ? maxChars : Integer.MAX_VALUE;
        int alwaysBudget = budget == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, budget / 2);

        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        Set<String> missing = new HashSet<>();
        List<String> includedAlways = new ArrayList<>();
        List<String> includedSelected = new ArrayList<>();

        appendSkillsWithBudget(sb, always, vars, seen, missing, includedAlways, alwaysBudget);
        int remaining = budget == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, budget - sb.length());
        appendSkillsWithBudget(sb, selected, vars, seen, missing, includedSelected, remaining);

        return new RenderAllResult(includedAlways, includedSelected, sb.toString().trim(), missing);
    }

    private void appendSkillsWithBudget(
            StringBuilder sb,
            List<SkillsLoader.SkillDocument> docs,
            Map<String, String> vars,
            Set<String> seen,
            Set<String> missingVars,
            List<String> includedNames,
            int budget
    ) {
        for (SkillsLoader.SkillDocument doc : docs) {
            String name = doc.entry().name();
            if (seen.contains(name)) {
                continue;
            }

            String body = doc.body() != null ? doc.body() : "";
            if (body.isBlank()) {
                continue;
            }

            RenderedTemplate rendered = renderTemplate(body, vars);
            missingVars.addAll(rendered.missing());
            String renderedBody = rendered.text();
            if (renderedBody.isBlank()) {
                continue;
            }

            StringBuilder chunk = new StringBuilder();
            if (sb.length() > 0) {
                chunk.append("\n\n");
            }
            chunk.append("## Skill: ").append(name).append("\n\n").append(renderedBody.trim());

            if (budget != Integer.MAX_VALUE && sb.length() + chunk.length() > budget) {
                break;
            }

            sb.append(chunk);
            seen.add(name);
            includedNames.add(name);
        }
    }

    private Map<String, String> buildVariables(SkillRoutingContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("workspace", ctx.workspace() != null ? ctx.workspace().toString() : "");
        out.put("channel", ctx.channel() != null ? ctx.channel() : "");
        out.put("chat_id", ctx.chatId() != null ? ctx.chatId() : "");
        out.put("message", ctx.message() != null ? ctx.message() : "");
        out.put("now", Instant.now().toString());

        if (ctx.toolNames() != null && !ctx.toolNames().isEmpty()) {
            out.put("tool_names", String.join(", ", ctx.toolNames()));
        } else {
            out.put("tool_names", "");
        }

        if (ctx.metadata() != null) {
            for (Map.Entry<String, Object> e : ctx.metadata().entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String k = "meta." + e.getKey();
                Object v = e.getValue();
                out.put(k, v != null ? String.valueOf(v) : "");
            }
        }

        if (ctx.variables() != null) {
            out.putAll(ctx.variables());
        }

        return out;
    }

    private static RenderedTemplate renderTemplate(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return new RenderedTemplate("", Set.of());
        }
        if (vars == null || vars.isEmpty()) {
            return new RenderedTemplate(template, Set.of());
        }

        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuffer sb = new StringBuffer();
        Set<String> missing = new HashSet<>();
        while (m.find()) {
            String key = m.group(1);
            String replacement = vars.get(key);
            if (replacement == null) {
                missing.add(key);
                replacement = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return new RenderedTemplate(sb.toString(), missing);
    }

    private static String safeLower(String s) {
        return s != null ? s.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static List<String> parseList(String raw) {
        if (raw == null) {
            return List.of();
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return List.of();
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            try {
                List<String> parsed = MAPPER.readValue(s, new TypeReference<>() {});
                List<String> out = new ArrayList<>();
                for (String v : parsed) {
                    if (v != null && !v.isBlank()) {
                        out.add(v.trim());
                    }
                }
                return out;
            } catch (Exception ignored) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.contains("\n")) {
            List<String> out = new ArrayList<>();
            for (String line : s.split("\\R")) {
                Matcher m = YAML_LIST_ITEM.matcher(line);
                if (m.matches()) {
                    String v = stripQuotes(m.group(1));
                    if (!v.isBlank()) {
                        out.add(v);
                    }
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }

        return splitCsvLike(s);
    }

    private static List<String> splitCsvLike(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || s.charAt(i - 1) != '\\')) {
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
                String v = stripQuotes(cur.toString());
                if (!v.isBlank()) {
                    out.add(v);
                }
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String v = stripQuotes(cur.toString());
        if (!v.isBlank()) {
            out.add(v);
        }
        return out;
    }

    private static String stripQuotes(String v) {
        if (v == null) {
            return "";
        }
        String t = v.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    private record SkillMeta(
            String name,
            boolean always,
            int priority,
            Set<String> channels,
            Set<String> tools,
            Set<String> keywords,
            Weights weights
    ) {
        static SkillMeta from(SkillsLoader.SkillDocument doc) {
            Map<String, String> fm = doc.frontmatter() != null ? doc.frontmatter() : Map.of();

            boolean always = "true".equalsIgnoreCase(fm.getOrDefault("always", "false"));
            int priority = parseInt(fm.get("priority"), 0);

            Set<String> channels = new HashSet<>();
            for (String c : parseList(fm.getOrDefault("channels", fm.getOrDefault("channel", "")))) {
                channels.add(c.toLowerCase(Locale.ROOT));
            }

            Set<String> tools = new HashSet<>();
            for (String t : parseList(fm.getOrDefault("tools", fm.getOrDefault("tool", "")))) {
                tools.add(t);
            }

            Set<String> keywords = new HashSet<>();
            for (String k : parseList(fm.getOrDefault("keywords", fm.getOrDefault("keyword", "")))) {
                keywords.add(k.toLowerCase(Locale.ROOT));
            }

            Weights weights = Weights.from(fm);
            return new SkillMeta(doc.entry().name(), always, priority, channels, tools, keywords, weights);
        }
    }

    private record ScoredSkill(SkillsLoader.SkillDocument doc, SkillMeta meta, int score, List<String> reasons) {
    }

    private static int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public record SelectionResult(
            List<String> alwaysSkills,
            List<String> selectedSkills,
            String renderedContext,
            List<SkillDecision> decisions,
            Set<String> missingVariables
    ) {
        public SelectionResult(List<String> alwaysSkills, List<String> selectedSkills, String renderedContext) {
            this(alwaysSkills, selectedSkills, renderedContext, List.of(), Set.of());
        }
    }

    public record SkillDecision(String name, int score, List<String> reasons, boolean always, boolean included) {
    }

    private record RenderAllResult(
            List<String> includedAlways,
            List<String> includedSelected,
            String text,
            Set<String> missingVariables
    ) {
    }

    private record RenderedTemplate(String text, Set<String> missing) {
    }

    private record ScoreResult(int score, List<String> reasons) {
    }

    private record Weights(int channelWeight, int toolWeight, int keywordWeight, int nameWeight, int maxKeywordHits) {
        static Weights from(Map<String, String> fm) {
            int channel = parseInt(fm.getOrDefault("w_channel", fm.getOrDefault("channel_weight", "50")), 50);
            int tool = parseInt(fm.getOrDefault("w_tool", fm.getOrDefault("tool_weight", "40")), 40);
            int keyword = parseInt(fm.getOrDefault("w_keyword", fm.getOrDefault("keyword_weight", "30")), 30);
            int name = parseInt(fm.getOrDefault("w_name", fm.getOrDefault("name_weight", "10")), 10);
            int maxHits = parseInt(fm.getOrDefault("max_keyword_hits", "3"), 3);
            return new Weights(channel, tool, keyword, name, maxHits);
        }
    }

    private static boolean matchesKeyword(String msgLower, String kwRaw) {
        if (kwRaw == null) {
            return false;
        }
        String kw = kwRaw.trim().toLowerCase(Locale.ROOT);
        if (kw.isBlank()) {
            return false;
        }
        boolean ascii = true;
        for (int i = 0; i < kw.length(); i++) {
            if (kw.charAt(i) > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (!ascii) {
            return msgLower.contains(kw);
        }
        String escaped = Pattern.quote(kw);
        Pattern p = Pattern.compile("\\b" + escaped + "\\b");
        return p.matcher(msgLower).find();
    }

    private static Set<String> extractRequestedTools(SkillRoutingContext ctx) {
        Set<String> out = new HashSet<>();
        if (ctx == null) {
            return out;
        }
        if (ctx.metadata() != null) {
            Object v = ctx.metadata().get("requested_tools");
            if (v instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        out.add(String.valueOf(item).trim().toLowerCase(Locale.ROOT));
                    }
                }
            } else if (v instanceof String s && !s.isBlank()) {
                for (String p : splitCsvLike(s)) {
                    out.add(p.toLowerCase(Locale.ROOT));
                }
            }
            Object one = ctx.metadata().get("tool");
            if (one instanceof String s && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
