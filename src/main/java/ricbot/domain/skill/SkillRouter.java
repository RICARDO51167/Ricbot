package ricbot.domain.skill;

import ricbot.infra.common.TextParsingUtils;

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
 * 技能路由器，负责根据上下文选择并渲染相关的技能文档。
 */
public class SkillRouter {

    // 匹配模板变量的正则表达式，格式为 {{ variable_name }}
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-\\.]+)\\s*\\}\\}");

    // 技能加载器，用于获取技能条目和文档
    private final SkillsLoader skillsLoader;
    // 最大选择的技能数量
    private final int maxSelected;
    // 渲染结果的最大字符数
    private final int maxChars;

    /**
     * 构造函数
     *
     * @param skillsLoader 技能加载器
     * @param maxSelected  最大选择技能数，如果为null则默认为3
     * @param maxChars     最大渲染字符数，如果为null则默认为12000
     */
    public SkillRouter(SkillsLoader skillsLoader, Integer maxSelected, Integer maxChars) {
        this.skillsLoader = skillsLoader;
        // 确保 maxSelected 非负，默认值为 3
        this.maxSelected = maxSelected != null ? Math.max(0, maxSelected) : 3;
        // 确保 maxChars 非负，默认值为 12000
        this.maxChars = maxChars != null ? Math.max(0, maxChars) : 12000;
    }

    /**
     * 根据上下文选择并渲染技能
     *
     * @param ctx 技能路由上下文
     * @return 选择结果，包含始终加载的技能名、选中的技能名以及渲染后的文本
     */
    public SelectionResult selectAndRender(SkillRoutingContext ctx) {
        // 获取所有技能条目
        List<SkillsLoader.SkillEntry> entries = skillsLoader.listSkillEntries();

        // 存储始终需要加载的技能文档
        List<SkillsLoader.SkillDocument> always = new ArrayList<>();
        // 存储候选技能及其评分
        List<ScoredSkill> candidates = new ArrayList<>();
        List<SkillDecision> decisions = new ArrayList<>();

        // 遍历所有技能条目
        for (SkillsLoader.SkillEntry entry : entries) {
            // 加载技能文档
            SkillsLoader.SkillDocument doc = skillsLoader.loadSkillDocument(entry);
            if (doc == null) {
                continue; // 如果文档为空，跳过
            }

            // 解析技能元数据
            SkillMeta meta = SkillMeta.from(doc);
            // 如果标记为 always，直接加入 always 列表
            if (meta.always) {
                always.add(doc);
                continue;
            }

            ScoreResult scored = score(meta, ctx);
            int score = scored.score();
            // 只有评分大于0的技能才作为候选
            if (score > 0) {
                candidates.add(new ScoredSkill(doc, meta, score, scored.reasons()));
            }
        }

        // 对候选技能进行排序：
        // 1. 按评分降序
        // 2. 按优先级降序
        // 3. 按技能名称升序
        candidates.sort(
                Comparator.<ScoredSkill>comparingInt(ScoredSkill::score).reversed()
                        .thenComparing(Comparator.comparingInt((ScoredSkill s) -> s.meta().priority).reversed())
                        .thenComparing(s -> s.doc().entry().name())
        );

        // 选择前 maxSelected 个技能
        List<SkillsLoader.SkillDocument> selected = new ArrayList<>();
        for (ScoredSkill s : candidates) {
            if (selected.size() >= maxSelected) {
                break; // 达到最大选择数量，停止
            }
            selected.add(s.doc());
        }

        // 构建模板变量映射
        Map<String, String> vars = buildVariables(ctx);
        // 渲染所有选中的技能文档
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

    /**
     * 计算技能的评分
     *
     * @param meta 技能元数据
     * @param ctx  路由上下文
     * @return 评分值
     */
    private ScoreResult score(SkillMeta meta, SkillRoutingContext ctx) {
        int s = meta.priority;
        List<String> reasons = new ArrayList<>();
        if (meta.priority != 0) {
            reasons.add("priority=" + meta.priority);
        }

        // 检查渠道匹配
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

        // 检查技能名称是否在消息中出现
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

    /**
     * 渲染所有技能文档
     *
     * @param always   始终加载的技能文档列表
     * @param selected 选中的技能文档列表
     * @param vars     模板变量映射
     * @return 渲染后的字符串
     */
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

    /**
     * 将单个技能文档追加到 StringBuilder 中
     *
     * @param sb   目标 StringBuilder
     * @param doc  技能文档
     * @param vars 模板变量
     * @param seen 已处理技能名称集合
     */
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

    /**
     * 构建模板变量映射
     *
     * @param ctx 路由上下文
     * @return 变量映射
     */
    private Map<String, String> buildVariables(SkillRoutingContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        // 添加基本上下文变量
        out.put("workspace", ctx.workspace() != null ? ctx.workspace().toString() : "");
        out.put("channel", ctx.channel() != null ? ctx.channel() : "");
        out.put("chat_id", ctx.chatId() != null ? ctx.chatId() : "");
        out.put("message", ctx.message() != null ? ctx.message() : "");
        out.put("now", Instant.now().toString());

        // 添加工具名称变量
        if (ctx.toolNames() != null && !ctx.toolNames().isEmpty()) {
            out.put("tool_names", String.join(", ", ctx.toolNames()));
        } else {
            out.put("tool_names", "");
        }

        // 添加元数据变量，前缀为 meta.
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

        // 添加自定义变量
        if (ctx.variables() != null) {
            out.putAll(ctx.variables());
        }

        return out;
    }

    /**
     * 渲染模板字符串，替换 {{ key }} 格式的变量
     *
     * @param template 模板字符串
     * @param vars     变量映射
     * @return 渲染后的字符串
     */
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
            String key = m.group(1); // 获取变量名
            String replacement = vars.get(key); // 查找变量值
            if (replacement == null) {
                missing.add(key);
                replacement = "";
            }
            // 安全地替换，防止特殊字符干扰
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb); // 添加尾部剩余部分
        return new RenderedTemplate(sb.toString(), missing);
    }

    /**
     * 安全地将字符串转换为小写
     *
     * @param s 输入字符串
     * @return 小写字符串，如果输入为null则返回空字符串
     */
    private static String safeLower(String s) {
        return s != null ? s.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 解析列表字符串，支持逗号或空格分隔，可选的方括号包裹
     *
     * @param raw 原始字符串
     * @return 解析后的字符串列表
     */
    private static List<String> parseList(String raw) {
        return TextParsingUtils.parseStringList(raw);
    }

    /**
     * 技能元数据记录
     *
     * @param name     技能名称
     * @param always   是否始终加载
     * @param priority 优先级
     * @param channels 适用渠道集合
     * @param tools    相关工具集合
     * @param keywords 关键词集合
     */
    private record SkillMeta(
            String name,
            boolean always,
            int priority,
            Set<String> channels,
            Set<String> tools,
            Set<String> keywords,
            Weights weights
    ) {
        /**
         * 从技能文档解析元数据
         *
         * @param doc 技能文档
         * @return 技能元数据对象
         */
        static SkillMeta from(SkillsLoader.SkillDocument doc) {
            Map<String, String> fm = doc.frontmatter() != null ? doc.frontmatter() : Map.of();

            // 解析 always 字段
            boolean always = "true".equalsIgnoreCase(fm.getOrDefault("always", "false"));
            // 解析 priority 字段
            int priority = parseInt(fm.get("priority"), 0);

            // 解析 channels 字段
            Set<String> channels = new HashSet<>();
            for (String c : parseList(fm.getOrDefault("channels", fm.getOrDefault("channel", "")))) {
                channels.add(c.toLowerCase(Locale.ROOT));
            }

            // 解析 tools 字段
            Set<String> tools = new HashSet<>();
            for (String t : parseList(fm.getOrDefault("tools", fm.getOrDefault("tool", "")))) {
                tools.add(t);
            }

            // 解析 keywords 字段
            Set<String> keywords = new HashSet<>();
            for (String k : parseList(fm.getOrDefault("keywords", fm.getOrDefault("keyword", "")))) {
                keywords.add(k.toLowerCase(Locale.ROOT));
            }

            Weights weights = Weights.from(fm);
            return new SkillMeta(doc.entry().name(), always, priority, channels, tools, keywords, weights);
        }
    }

    /**
     * 带评分的技能记录
     *
     * @param doc   技能文档
     * @param meta  技能元数据
     * @param score 评分
     */
    private record ScoredSkill(SkillsLoader.SkillDocument doc, SkillMeta meta, int score, List<String> reasons) {
    }

    /**
     * 安全地将字符串解析为整数
     *
     * @param raw 原始字符串
     * @param def 默认值
     * @return 解析后的整数，如果失败则返回默认值
     */
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

    /**
     * 选择结果记录
     *
     * @param alwaysSkills   始终加载的技能名称列表
     * @param selectedSkills 选中的技能名称列表
     * @param renderedContext 渲染后的上下文文本
     */
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
                for (String p : TextParsingUtils.parseStringList(s)) {
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
