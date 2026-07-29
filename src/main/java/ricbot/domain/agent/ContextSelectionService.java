package ricbot.domain.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.agent.dto.ContextSource;
import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryRetriever;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

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
public final class ContextSelectionService {
    private static final Logger log = LoggerFactory.getLogger(ContextSelectionService.class);

    // 用于分割文本为 token 的正则表达式模式，匹配非字母、非数字和非下划线的字符
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}_]+");
    
    // 记忆存储接口，用于检索历史记忆
    private final MemoryStore memoryStore;
    
    // 工具调用轨迹摘要器，用于生成工具调用的简要描述
    private final ToolTraceSummarizer toolTraceSummarizer;
    private final int contextWindowTokens;
    private final TraceStore traceStore;

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
        this(memoryStore, toolTraceSummarizer, contextWindowTokens, null);
    }

    public ContextSelectionService(
            MemoryStore memoryStore,
            ToolTraceSummarizer toolTraceSummarizer,
            int contextWindowTokens,
            TraceStore traceStore
    ) {
        this.memoryStore = memoryStore;
        this.toolTraceSummarizer = toolTraceSummarizer;
        this.contextWindowTokens = contextWindowTokens;
        this.traceStore = traceStore;
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
    public SelectionResult select(
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

        addWorkspaceSessionContext(bundle, preparedInputs.workspaceContext());
        addTeamContext(bundle, preparedInputs.teamContext());

        // 渲染最近的工具调用轨迹并添加到上下文中
        for (String trace : toolTraceSummarizer.renderRecent(preparedInputs.toolTrace(), 4)) {
            bundle.addItem("tool_trace", trace);
        }

        recordContextBuilt(bundle, preparedInputs.sessionId(), currentMessage);

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
        List<String> workerResults = stringList(teamContext.get("workerResults"));
        List<String> workerReports = stringList(teamContext.get("workerReports"));
        List<String> implementationSteps = stringList(teamContext.get("implementationSteps"));
        List<String> blockedImplementationSteps = stringList(teamContext.get("blockedImplementationSteps"));
        List<String> stepAuditSummary = stringList(teamContext.get("stepAuditSummary"));
        Map<?, ?> stepProgress = teamContext.get("implementationStepProgress") instanceof Map<?, ?> progress ? progress : Map.of();
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
        if (!workerResults.isEmpty()) {
            parts.add("worker=" + String.join("; ", workerResults));
        }
        if (!workerReports.isEmpty()) {
            parts.add("workerReport=" + abbreviate(String.join("; ", workerReports), 360));
        }
        if (!implementationSteps.isEmpty()) {
            parts.add("implementationSteps=" + abbreviate(String.join("; ", implementationSteps), 360));
        }
        if (!blockedImplementationSteps.isEmpty()) {
            parts.add("blockedSteps=" + abbreviate(String.join("; ", blockedImplementationSteps), 240));
        }
        if (!stepAuditSummary.isEmpty()) {
            parts.add("stepAudit=" + abbreviate(String.join("; ", stepAuditSummary), 300));
        }
        if (!stepProgress.isEmpty()) {
            parts.add("stepProgress=total:" + string(stepProgress.get("total"))
                    + " draft:" + string(stepProgress.get("draft"))
                    + " ready:" + string(stepProgress.get("ready"))
                    + " applied:" + string(stepProgress.get("applied"))
                    + " blocked:" + string(stepProgress.get("blocked"))
                    + " next:" + abbreviate(string(stepProgress.get("nextStep")), 180));
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
        if (!workerReports.isEmpty()) {
            bundle.addItem(
                    "team_context",
                    "worker " + abbreviate(String.join("; ", workerReports), 420),
                    0.78d,
                    ContextSource.of("team_worker", sessionId + ":worker", string(teamContext.get("workerPath")),
                            "team worker report", 0.78d, metadata)
            );
        }
        if (!implementationSteps.isEmpty()) {
            bundle.addItem(
                    "team_context",
                    "implementation steps " + abbreviate(String.join("; ", implementationSteps), 420),
                    0.77d,
                    ContextSource.of("team_implementation_steps", sessionId + ":implementation_steps", string(teamContext.get("implementationStepsPath")),
                            "team implementation steps", 0.77d, metadata)
            );
        }
        if (!stepAuditSummary.isEmpty()) {
            bundle.addItem(
                    "team_context",
                    "step audit " + abbreviate(String.join("; ", stepAuditSummary), 420),
                    0.76d,
                    ContextSource.of("team_step_audit", sessionId + ":step_audit", string(teamContext.get("stepAuditPath")),
                            "team step audit", 0.76d, metadata)
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

    private void addWorkspaceSessionContext(PromptContextBundle bundle, Map<String, Object> workspaceContext) {
        if (workspaceContext == null || workspaceContext.isEmpty()) {
            return;
        }
        String id = string(workspaceContext.get("id"));
        String type = string(workspaceContext.get("type"));
        String status = string(workspaceContext.get("status"));
        String goal = string(workspaceContext.get("goal"));
        String path = string(workspaceContext.get("path"));
        String source = string(workspaceContext.get("source"));
        String rendered = "workspace=" + id
                + " type=" + type
                + " status=" + status
                + (!goal.isBlank() ? " goal=" + goal : "")
                + (!path.isBlank() ? " path=" + path : "");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workspace_type", type);
        metadata.put("status", status);
        metadata.put("workspacePath", path);
        bundle.addItem(
                "workspace_session",
                rendered,
                0.82d,
                ContextSource.of("workspace", id, source, "active workspace session", 0.82d, metadata)
        );
    }

    private void recordContextBuilt(PromptContextBundle bundle, String sessionId, String currentMessage) {
        if (traceStore == null || bundle == null) {
            return;
        }
        try {
            String traceId = traceStore.traceIdForSession(sessionId);
            TraceEvent event = traceStore.append(new TraceEvent(
                    traceId,
                    null,
                    "",
                    sessionId,
                    "",
                    "",
                    "",
                    TraceEventType.CONTEXT_BUILT,
                    "context",
                    "context bundle built",
                    Map.of(
                            "query", currentMessage != null ? abbreviate(currentMessage, 240) : "",
                            "sections", bundle.budgetTrace().get("sections"),
                            "estimatedTokens", bundle.budgetTrace().get("estimated_rendered_tokens")
                    ),
                    null,
                    null
            ));
            if (event != null) {
                bundle.addItem(
                        "trace_context",
                        "trace=" + event.traceId() + " event=" + event.type(),
                        0.55d,
                        ContextSource.of(
                                "trace",
                                event.traceId(),
                                traceStore.tracePath(event.traceId()),
                                "coding harness trace",
                                0.55d,
                                Map.of("eventId", event.eventId(), "eventType", event.type().name())
                        )
                );
            }
        } catch (Exception e) {
            log.warn("skip context trace write", e);
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

        // Reserve half the budget for a legal recent suffix and half for relevant older turns.
        int suffixTarget = Math.min(Math.max(2, historyWindowMessages / 2),
                Math.min(historyWindowMessages, history.size()));
        
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
    public record SessionPreparedInputs(
            String sessionId,
            String archivedSummary,
            TaskState taskState,
            List<Map<String, Object>> toolTrace,
            Map<String, Object> teamContext,
            Map<String, Object> workspaceContext
    ) {
        public SessionPreparedInputs {
            toolTrace = toolTrace != null ? List.copyOf(toolTrace) : List.of();
            teamContext = teamContext != null ? Map.copyOf(teamContext) : Map.of();
            workspaceContext = workspaceContext != null ? Map.copyOf(workspaceContext) : Map.of();
        }

        public SessionPreparedInputs(String sessionId, String archivedSummary, TaskState taskState, List<Map<String, Object>> toolTrace) {
            this(sessionId, archivedSummary, taskState, toolTrace, Map.of(), Map.of());
        }

        SessionPreparedInputs(
                String sessionId,
                String archivedSummary,
                TaskState taskState,
                List<Map<String, Object>> toolTrace,
                Map<String, Object> teamContext
        ) {
            this(sessionId, archivedSummary, taskState, toolTrace, teamContext, Map.of());
        }

        public SessionPreparedInputs(String archivedSummary, TaskState taskState, List<Map<String, Object>> toolTrace) {
            this("", archivedSummary, taskState, toolTrace, Map.of(), Map.of());
        }
    }

    /**
     * 选择结果记录。
     *
     * @param history 筛选后的历史消息
     * @param bundle  上下文 bundle
     */
    public record SelectionResult(List<Map<String, Object>> history, PromptContextBundle bundle) {
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
