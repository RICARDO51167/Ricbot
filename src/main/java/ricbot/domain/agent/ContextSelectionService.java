package ricbot.domain.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryRetriever;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.note.NoteEntry;
import ricbot.domain.note.NoteService;
import ricbot.domain.rag.WorkspaceRagService;
import ricbot.domain.subagent.SubAgentOrchestrator;
import ricbot.domain.subagent.SubAgentResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 上下文选择服务类，负责从会话历史、记忆存储和工具调用轨迹中选择相关的上下文信息。
 */
final class ContextSelectionService {
    private static final Logger log = LoggerFactory.getLogger(ContextSelectionService.class);

    // 用于分割文本为 token 的正则表达式模式，匹配非字母、非数字和非下划线的字符
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}_]+");
    
    // 记忆存储接口，用于检索历史记忆
    private final MemoryStore memoryStore;
    
    // 工具调用轨迹摘要器，用于生成工具调用的简要描述
    private final ToolTraceSummarizer toolTraceSummarizer;
    private final int contextWindowTokens;
    private final NoteService noteService;
    private final WorkspaceRagService ragService;
    private final ExperienceStore experienceStore;

    /**
     * 构造函数，初始化记忆存储和工具轨迹摘要器。
     *
     * @param memoryStore         记忆存储实例
     * @param toolTraceSummarizer 工具轨迹摘要器实例
     */
    ContextSelectionService(MemoryStore memoryStore, ToolTraceSummarizer toolTraceSummarizer) {
        this(memoryStore, toolTraceSummarizer, 0);
    }

    ContextSelectionService(MemoryStore memoryStore, ToolTraceSummarizer toolTraceSummarizer, int contextWindowTokens) {
        this(memoryStore, toolTraceSummarizer, contextWindowTokens, null, null);
    }

    ContextSelectionService(
            MemoryStore memoryStore,
            ToolTraceSummarizer toolTraceSummarizer,
            int contextWindowTokens,
            NoteService noteService,
            WorkspaceRagService ragService
    ) {
        this(memoryStore, toolTraceSummarizer, contextWindowTokens, noteService, ragService, null);
    }

    ContextSelectionService(
            MemoryStore memoryStore,
            ToolTraceSummarizer toolTraceSummarizer,
            int contextWindowTokens,
            NoteService noteService,
            WorkspaceRagService ragService,
            ExperienceStore experienceStore
    ) {
        this.memoryStore = memoryStore;
        this.toolTraceSummarizer = toolTraceSummarizer;
        this.contextWindowTokens = contextWindowTokens;
        this.noteService = noteService;
        this.ragService = ragService;
        this.experienceStore = experienceStore;
    }

    /**
     * 选择与当前消息相关的上下文信息，包括会话历史、任务状态、记忆回忆等。
     *
     * @param preparedInputs       预处理后的输入数据，包含归档摘要、任务状态和工具轨迹
     * @param sessionMessages      会话消息列表
     * @param currentMessage       当前用户消息
     * @param historyWindowMessages 历史消息窗口大小
     * @return 选择结果，包含筛选后的历史消息和上下文 bundle
     */
    SelectionResult select(
            SessionPreparedInputs preparedInputs,
            List<Map<String, Object>> sessionMessages,
            String currentMessage,
            int historyWindowMessages
    ) {
        // 获取任务状态
        TaskState taskState = preparedInputs.taskState();
        
        // 选择相关的历史消息
        List<Map<String, Object>> history = selectHistory(sessionMessages, currentMessage, taskState, historyWindowMessages);
        
        // 创建上下文 bundle 用于收集各类上下文信息
        PromptContextBundle bundle = PromptContextBundle.forContextWindow(contextWindowTokens);

        // 如果存在归档摘要且不为空，则添加到上下文中
        if (preparedInputs.archivedSummary() != null && !preparedInputs.archivedSummary().isBlank()) {
            bundle.addItem("memory_recall", preparedInputs.archivedSummary());
        }
        
        // 如果任务状态存在，则添加任务相关信息到上下文中
        if (taskState != null) {
            bundle.addItem("task_state", "goal: " + blankSafe(taskState.goal()));
            if (!taskState.currentStep().isBlank()) {
                bundle.addItem("task_state", "current_step: " + taskState.currentStep());
            }
            if (!taskState.status().isBlank()) {
                bundle.addItem("task_state", "status: " + taskState.status());
            }
            if (!taskState.blockedReason().isBlank()) {
                bundle.addItem("task_state", "blocked_reason: " + taskState.blockedReason());
            }
            if (!taskState.nextAction().isBlank()) {
                bundle.addItem("task_state", "next_action: " + taskState.nextAction());
            }
            for (TaskState.TaskStep step : taskState.steps()) {
                bundle.addItem("task_state", "step " + step.id() + ": " + step.title() + " [" + step.status() + "]");
            }
        }

        // 从记忆存储中回忆相关记忆条目
        List<MemoryRetriever.ScoredMemory> recall = memoryStore.recallScoredMemories(currentMessage, taskState != null ? taskState.goal() : "", 8);
        for (MemoryRetriever.ScoredMemory scoredMemory : recall) {
            MemoryEntry entry = scoredMemory.entry();
            String rendered = "[" + entry.getMemoryType().name().toLowerCase(Locale.ROOT) + "] " + entry.renderLine().substring(2);
            // 根据是否是用户个人资料，分别添加到不同的上下文类别中
            if (entry.isUserProfile()) {
                bundle.addItem("user_profile", rendered, scoredMemory.relevanceScore(),
                        ContextSource.of("memory", entry.getId(), "", entry.getSummary(), scoredMemory.relevanceScore()));
            } else {
                bundle.addItem("memory_recall", rendered, scoredMemory.relevanceScore(),
                        ContextSource.of("memory", entry.getId(), "", entry.getSummary(), scoredMemory.relevanceScore()));
            }
        }

        // 回忆归档的历史记录并添加到上下文中
        for (String archived : memoryStore.recallArchivedHistory(currentMessage, 3)) {
            bundle.addItem("recent_history", archived);
        }

        addProjectNotes(bundle, currentMessage, taskState);
        addWorkspaceKnowledge(bundle, currentMessage, taskState);
        addVerifiedExperience(bundle, currentMessage, taskState, preparedInputs.sessionId(), preparedInputs.toolTrace());
        addTeamContext(bundle, preparedInputs.teamContext());
        addSubAgentSummaries(bundle, preparedInputs.sessionId(), preparedInputs.subAgentResults());

        // 渲染最近的工具调用轨迹并添加到上下文中
        for (String trace : toolTraceSummarizer.renderRecent(preparedInputs.toolTrace(), 4)) {
            bundle.addItem("tool_trace", trace);
        }

        // 返回选择结果，包含筛选后的历史消息和上下文 bundle
        return new SelectionResult(history, bundle);
    }

    private void addTeamContext(PromptContextBundle bundle, Map<String, Object> teamContext) {
        if (teamContext == null || teamContext.isEmpty()) {
            return;
        }
        Map<?, ?> session = teamContext.get("session") instanceof Map<?, ?> map ? map : Map.of();
        String sessionId = string(session.get("id"));
        String goal = string(session.get("goal"));
        String state = string(session.get("state"));
        String whiteboardPath = string(teamContext.get("whiteboardPath"));
        String verificationPath = string(teamContext.get("verificationPath"));
        String whiteboardSummary = string(teamContext.get("whiteboardSummary")).replace("\n", " ");
        List<String> verifierResults = stringList(teamContext.get("verifierResults"));
        List<String> verificationReports = stringList(teamContext.get("verificationReports"));
        List<String> revisionRequests = stringList(teamContext.get("revisionRequests"));
        List<String> parts = new ArrayList<>();
        if (!sessionId.isBlank()) {
            parts.add("session=" + sessionId);
        }
        if (!state.isBlank()) {
            parts.add("state=" + state);
        }
        if (!goal.isBlank()) {
            parts.add("goal=" + goal);
        }
        if (!verifierResults.isEmpty()) {
            parts.add("verifier=" + String.join("; ", verifierResults));
        }
        if (!verificationReports.isEmpty()) {
            parts.add("verificationReport=" + abbreviate(String.join("; ", verificationReports), 360));
        }
        if (!revisionRequests.isEmpty()) {
            parts.add("revision=" + String.join("; ", revisionRequests));
        }
        if (!whiteboardSummary.isBlank()) {
            parts.add("whiteboard=" + abbreviate(whiteboardSummary, 360));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("team_session", sessionId);
        metadata.put("team_state", state);
        bundle.addItem(
                "team_context",
                String.join(" | ", parts),
                0.8d,
                ContextSource.of("team", sessionId, whiteboardPath, "team whiteboard", 0.8d, metadata)
        );
        if (!verificationReports.isEmpty()) {
            bundle.addItem(
                    "team_context",
                    "verification " + abbreviate(String.join("; ", verificationReports), 420),
                    0.78d,
                    ContextSource.of("team_verification", sessionId + ":verification", verificationPath, "team verification report", 0.78d, metadata)
            );
        }
        for (Map<String, Object> event : eventRows(teamContext.get("recentEvents")).stream().limit(2).toList()) {
            String rendered = "event " + string(event.get("type"))
                    + " role=" + string(event.get("role"))
                    + " task=" + string(event.get("taskId"))
                    + " message=" + abbreviate(string(event.get("message")), 180);
            bundle.addItem("team_context", rendered, 0.6d,
                    ContextSource.of("team_event", string(event.get("id")), whiteboardPath, string(event.get("type")), 0.6d, metadata));
        }
    }

    private void addSubAgentSummaries(
            PromptContextBundle bundle,
            String sessionId,
            List<SubAgentResult> subAgentResults
    ) {
        List<SubAgentResult> results = subAgentResults != null ? subAgentResults : List.of();
        for (SubAgentResult result : results.stream()
                .sorted(Comparator.comparing(SubAgentResult::createdAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(3)
                .toList()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("subagent_role", result.role().name());
            metadata.put("confidence", Math.round(result.confidence() * 1000.0d) / 1000.0d);
            metadata.put("sessionId", sessionId != null ? sessionId : "");
            bundle.addItem(
                    "subagent_summaries",
                    SubAgentOrchestrator.renderCompact(result),
                    result.confidence(),
                    ContextSource.of(
                            "subagent",
                            result.taskId(),
                            "subagent:" + result.taskId(),
                            result.role().name() + " summary",
                            result.confidence(),
                            metadata
                    )
            );
        }
    }

    private void addProjectNotes(PromptContextBundle bundle, String currentMessage, TaskState taskState) {
        if (noteService == null) {
            return;
        }
        String query = currentMessage + "\n" + (taskState != null ? taskState.goal() : "");
        try {
            for (NoteService.SearchResult result : noteService.search(query, 4)) {
                NoteEntry entry = result.entry();
                String rendered = entry.path()
                        + " [" + entry.category() + "/" + entry.type() + "] "
                        + entry.title()
                        + (result.snippet() != null && !result.snippet().isBlank()
                        ? " — " + result.snippet().replace("\n", " ")
                        : "");
                double relevance = normalizeRelevance(result.score(), 5.0d);
                bundle.addItem("project_notes", rendered, relevance,
                        ContextSource.of("note", entry.id(), entry.path(), entry.title(), relevance));
            }
        } catch (Exception ignored) {
        }
    }

    private void addVerifiedExperience(
            PromptContextBundle bundle,
            String currentMessage,
            TaskState taskState,
            String sessionId,
            List<Map<String, Object>> toolTrace
    ) {
        if (experienceStore == null) {
            return;
        }
        String query = experienceQuery(currentMessage, taskState);
        List<String> relatedFiles = relatedFiles(currentMessage, toolTrace);
        try {
            for (ExperienceStore.ScoredExperience result : experienceStore.searchVerified(query, relatedFiles, 3)) {
                ExperienceEntry entry = result.entry();
                String rendered = entry.type()
                        + " | " + entry.title()
                        + " | when: " + entry.whenToApply()
                        + " | content: " + entry.content()
                        + (!entry.suggestedTests().isEmpty()
                        ? " | suggestedTests: " + String.join("; ", entry.suggestedTests())
                        : "")
                        + " | confidence: " + String.format(Locale.ROOT, "%.2f", entry.confidence())
                        + (!entry.sourceRef().isBlank() ? " | sourceRef: " + entry.sourceRef() : "");
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("status", entry.status().name());
                metadata.put("experience_type", entry.type().name());
                metadata.put("sourceRef", entry.sourceRef());
                metadata.put("confidence", Math.round(entry.confidence() * 1000.0d) / 1000.0d);
                metadata.put("effectiveConfidence", Math.round(result.effectiveConfidence() * 1000.0d) / 1000.0d);
                metadata.put("successCount", entry.successCount());
                metadata.put("failureCount", entry.failureCount());
                metadata.put("lastUsedAt", entry.lastUsedAt());
                metadata.put("reason", result.reason());
                bundle.addItem("verified_experience", rendered, result.score(),
                        ContextSource.of(
                                "experience",
                                entry.id(),
                                "experience/verified.jsonl:" + entry.id(),
                                entry.title(),
                                result.score(),
                                metadata
                        ));
                try {
                    experienceStore.recordUsage(
                            entry.id(),
                            sessionId,
                            query,
                            taskState != null ? taskState.goal() : "",
                            result.score(),
                            result.reason()
                    );
                } catch (Exception e) {
                    log.warn("skip recording verified experience usage: {}", entry.id(), e);
                }
            }
        } catch (Exception e) {
            log.warn("skip verified experience context due to read/search failure", e);
        }
    }

    private void addWorkspaceKnowledge(PromptContextBundle bundle, String currentMessage, TaskState taskState) {
        if (ragService == null) {
            return;
        }
        String query = currentMessage + "\n" + (taskState != null ? taskState.goal() : "");
        try {
            for (WorkspaceRagService.SearchResult result : ragService.searchProjectKnowledge(query, 5)) {
                WorkspaceRagService.FileChunk chunk = result.chunk();
                String rendered = chunk.path()
                        + ":" + chunk.startLine() + "-" + chunk.endLine()
                        + " [" + chunk.kind() + "]"
                        + (result.snippet() != null && !result.snippet().isBlank()
                        ? " — " + result.snippet().replace("\n", " ")
                        : "");
                double relevance = normalizeRelevance(result.score(), 8.0d);
                bundle.addItem("workspace_knowledge", rendered, relevance,
                        ContextSource.of("rag", chunk.id(), chunk.path(), chunk.path() + ":" + chunk.startLine(), relevance));
            }
        } catch (Exception ignored) {
        }
    }

    private double normalizeRelevance(double score, double maxExpected) {
        if (maxExpected <= 0d) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, score / maxExpected));
    }

    private String experienceQuery(String currentMessage, TaskState taskState) {
        StringBuilder sb = new StringBuilder();
        if (currentMessage != null) {
            sb.append(currentMessage).append("\n");
        }
        if (taskState != null) {
            sb.append(taskState.goal()).append("\n")
                    .append(taskState.currentStep()).append("\n")
                    .append(taskState.blockedReason()).append("\n")
                    .append(taskState.nextAction()).append("\n");
            for (TaskState.TaskStep step : taskState.steps()) {
                sb.append(step.title()).append("\n").append(step.evidence()).append("\n");
            }
        }
        return sb.toString();
    }

    private List<String> relatedFiles(String currentMessage, List<Map<String, Object>> toolTrace) {
        List<String> out = new ArrayList<>();
        addPathLike(out, currentMessage);
        for (Map<String, Object> trace : toolTrace != null ? toolTrace : List.<Map<String, Object>>of()) {
            addPathLike(out, String.valueOf(trace.getOrDefault("arguments_summary", "")));
            addPathLike(out, String.valueOf(trace.getOrDefault("result_summary", "")));
        }
        return out.stream().distinct().limit(12).toList();
    }

    private void addPathLike(List<String> out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String cleaned = text.replace("{", " ").replace("}", " ").replace(",", " ");
        for (String token : cleaned.split("\\s+")) {
            String value = token.replace("\"", "").replace("'", "").trim();
            if (value.contains("/") || value.endsWith(".java") || value.endsWith(".md") || value.endsWith(".json")
                    || value.endsWith(".yml") || value.endsWith(".yaml") || value.endsWith(".txt")) {
                out.add(value);
            }
        }
    }

    private List<Map<String, Object>> eventRows(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null) {
                            row.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    private List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "...";
    }

    private String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    /**
     * 选择相关的会话历史消息。
     * 策略：保留最近的若干条消息（suffix），并根据当前消息和任务目标的相关性从较早的消息中筛选补充。
     *
     * @param sessionMessages      会话消息列表
     * @param currentMessage       当前用户消息
     * @param taskState            任务状态
     * @param historyWindowMessages 历史消息窗口大小
     * @return 筛选后的历史消息列表
     */
    private List<Map<String, Object>> selectHistory(
            List<Map<String, Object>> sessionMessages,
            String currentMessage,
            TaskState taskState,
            int historyWindowMessages
    ) {
        // 如果会话消息为空或窗口大小小于等于0，返回空列表
        List<Map<String, Object>> history = sessionMessages != null ? sessionMessages : List.of();
        if (history.isEmpty() || historyWindowMessages <= 0) {
            return List.of();
        }

        // 计算需要保留的最近消息数量，至少6条，最多为窗口大小的一半或总历史大小
        int suffixTarget = Math.min(Math.max(6, historyWindowMessages / 2), history.size());
        
        // 找到合法的最近消息起始索引，确保工具调用的完整性
        int suffixStart = findLegalSuffixStart(history, suffixTarget);
        
        // 提取最近的消息片段
        List<Map<String, Object>> suffix = new ArrayList<>(history.subList(suffixStart, history.size()));
        
        // 如果最近消息已满足窗口大小或没有更早的消息，直接返回最近消息
        if (suffix.size() >= historyWindowMessages || suffixStart == 0) {
            return suffix;
        }

        // 获取较早的消息片段
        List<Map<String, Object>> older = history.subList(0, suffixStart);
        
        // 对当前消息和任务目标进行分词，用于相关性评分
        Set<String> queryTokens = tokenize(currentMessage + " " + (taskState != null ? taskState.goal() : ""));
        
        // 存储评分后的消息
        List<ScoredMessage> scored = new ArrayList<>();
        
        // 遍历较早的消息，计算每条消息的相关性得分
        for (int i = 0; i < older.size(); i++) {
            Map<String, Object> msg = older.get(i);
            if (msg == null) {
                continue;
            }
            
            // 获取消息角色和内容
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            
            // 计算重叠得分、近期加分和角色权重。角色权重只作为轻微 tie-breaker，避免旧用户消息因角色过度入选。
            double score = overlapScore(queryTokens, tokenize(content));
            score += recencyBonus(i, older.size());
            score += roleWeight(role);
            
            // 如果得分低于阈值，跳过该消息
            if (score <= 0.25d) {
                continue;
            }
            
            // 将得分消息加入列表
            scored.add(new ScoredMessage(i, score, msg));
        }

        // 计算还需要从较早消息中选择多少条
        int remaining = Math.max(0, historyWindowMessages - suffix.size());
        
        // 按得分降序排序，选择前 remaining 条，再按原始索引升序排序以保持时间顺序
        List<Map<String, Object>> selectedOlder = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredMessage::score).reversed())
                .limit(remaining)
                .sorted(Comparator.comparingInt(ScoredMessage::index))
                .map(ScoredMessage::message)
                .collect(Collectors.toCollection(ArrayList::new));
        
        // 将选中的较早消息与最近消息合并
        selectedOlder.addAll(suffix);
        
        return selectedOlder;
    }

    /**
     * 找到合法的最近消息起始索引，确保工具调用及其响应的完整性。
     * 如果最近的消息中包含未完成的工具调用（即有 tool_calls 但没有对应的 tool 响应），则调整起始位置。
     *
     * @param messages   消息列表
     * @param keepRecent 需要保留的最近消息数量
     * @return 合法的起始索引
     */
    private int findLegalSuffixStart(List<Map<String, Object>> messages, int keepRecent) {
        // 计算基础切割点
        int baseCut = Math.max(0, messages.size() - keepRecent);
        
        // 提取最近的消息片段
        List<Map<String, Object>> suffix = new ArrayList<>(messages.subList(baseCut, messages.size()));
        
        // 记录需要向前移动的合法起始偏移量
        int legalStart = 0;
        
        // 记录已声明的工具调用 ID
        Set<String> declared = new HashSet<>();
        
        // 遍历最近的消息，检查工具调用的完整性
        for (int i = 0; i < suffix.size(); i++) {
            Map<String, Object> msg = suffix.get(i);
            if (msg == null) {
                continue;
            }
            
            String role = String.valueOf(msg.get("role"));
            
            // 如果是助手消息，记录其发出的工具调用 ID
            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc && tc.get("id") != null) {
                            declared.add(String.valueOf(tc.get("id")));
                        }
                    }
                }
            } 
            // 如果是工具响应消息，检查是否有对应的工具调用声明
            else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                // 如果工具响应 ID 未在之前声明，说明截断位置不合法，需要调整
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    legalStart = i + 1;
                    declared.clear();
                }
            }
        }
        
        // 返回调整后的起始索引
        return baseCut + legalStart;
    }

    /**
     * 将文本分词，提取有效的 token 集合。
     *
     * @param text 输入文本
     * @return token 集合
     */
    private Set<String> tokenize(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : TOKEN_SPLIT.split(normalized)) {
            // 只保留长度大于等于2的 token
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        addCjkNgrams(normalized, out);
        return out;
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

    /**
     * 计算查询 token 与内容 token 的重叠得分。
     *
     * @param queryTokens   查询 token 集合
     * @param contentTokens 内容 token 集合
     * @return 重叠得分，范围为 [0, 1]
     */
    private double overlapScore(Set<String> queryTokens, Set<String> contentTokens) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0d;
        }
        
        // 计算命中次数
        long hits = contentTokens.stream().filter(queryTokens::contains).count();
        
        // 同时考虑 query 覆盖率和内容覆盖率，降低长文本只碰巧命中少量词时的得分。
        double queryCoverage = (double) hits / Math.max(1d, queryTokens.size());
        double contentCoverage = (double) hits / Math.max(1d, Math.min(contentTokens.size(), queryTokens.size() * 2));
        return (queryCoverage * 0.75d) + (contentCoverage * 0.25d);
    }

    /**
     * 计算近期加分，越近的消息得分越高。
     *
     * @param index 消息在列表中的索引
     * @param total 列表总大小
     * @return 近期加分
     */
    private double recencyBonus(int index, int total) {
        if (total <= 0) {
            return 0d;
        }
        // 线性加分，最大为 0.5
        return 0.35d * ((double) (index + 1) / (double) total);
    }

    /**
     * 根据消息角色返回权重。
     *
     * @param role 消息角色
     * @return 角色权重
     */
    private double roleWeight(String role) {
        if ("user".equals(role)) {
            return 0.2d;
        }
        if ("assistant".equals(role)) {
            return 0.12d;
        }
        if ("tool".equals(role)) {
            return 0.04d;
        }
        return 0d;
    }

    /**
     * 安全处理字符串，如果为空或 null 则返回默认值 "(none)"。
     *
     * @param value 输入字符串
     * @return 处理后的字符串
     */
    private String blankSafe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    /**
     * 会话预处理输入记录。
     *
     * @param archivedSummary 归档摘要
     * @param taskState       任务状态
     * @param toolTrace       工具调用轨迹
     */
    record SessionPreparedInputs(
            String sessionId,
            String archivedSummary,
            TaskState taskState,
            List<Map<String, Object>> toolTrace,
            List<SubAgentResult> subAgentResults,
            Map<String, Object> teamContext
    ) {
        SessionPreparedInputs {
            toolTrace = toolTrace != null ? List.copyOf(toolTrace) : List.of();
            subAgentResults = subAgentResults != null ? List.copyOf(subAgentResults) : List.of();
            teamContext = teamContext != null ? Map.copyOf(teamContext) : Map.of();
        }

        SessionPreparedInputs(String sessionId, String archivedSummary, TaskState taskState, List<Map<String, Object>> toolTrace) {
            this(sessionId, archivedSummary, taskState, toolTrace, List.of(), Map.of());
        }

        SessionPreparedInputs(
                String sessionId,
                String archivedSummary,
                TaskState taskState,
                List<Map<String, Object>> toolTrace,
                List<SubAgentResult> subAgentResults
        ) {
            this(sessionId, archivedSummary, taskState, toolTrace, subAgentResults, Map.of());
        }

        SessionPreparedInputs(String archivedSummary, TaskState taskState, List<Map<String, Object>> toolTrace) {
            this("", archivedSummary, taskState, toolTrace, List.of(), Map.of());
        }
    }

    /**
     * 选择结果记录。
     *
     * @param history 筛选后的历史消息
     * @param bundle  上下文 bundle
     */
    record SelectionResult(List<Map<String, Object>> history, PromptContextBundle bundle) {
    }

    /**
     * 评分消息记录，用于内部排序。
     *
     * @param index   原始索引
     * @param score   得分
     * @param message 消息内容
     */
    private record ScoredMessage(int index, double score, Map<String, Object> message) {
    }
}
