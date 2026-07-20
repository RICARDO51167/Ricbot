package ricbot.domain.agent;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryWritePolicy;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionMessage;
import ricbot.domain.session.SessionManager;
import ricbot.infra.common.HelperUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话持久化服务类，负责处理 Agent 交互过程中的会话状态保存、消息规范化及元数据更新。
 */
final class SessionPersistenceService {

    // 会话管理器，用于保存和加载会话状态
    private final SessionManager sessionManager;
    // 工具调用结果的最大字符数限制，超过此长度将被截断
    private final int maxToolResultChars;
    // 工具轨迹摘要器，用于生成工具调用的简要记录
    private final ToolTraceSummarizer toolTraceSummarizer = new ToolTraceSummarizer();
    private final MemoryStore memoryStore;
    private final MemoryWritePolicy memoryWritePolicy;

    /**
     * 构造函数
     *
     * @param sessionManager      会话管理器实例
     * @param maxToolResultChars  工具结果最大字符数限制
     */
    SessionPersistenceService(SessionManager sessionManager, int maxToolResultChars) {
        this(sessionManager, maxToolResultChars, null);
    }

    SessionPersistenceService(SessionManager sessionManager, int maxToolResultChars, MemoryStore memoryStore) {
        this(sessionManager, maxToolResultChars, memoryStore, new MemoryWritePolicy());
    }

    SessionPersistenceService(
            SessionManager sessionManager,
            int maxToolResultChars,
            MemoryStore memoryStore,
            MemoryWritePolicy memoryWritePolicy
    ) {
        this.sessionManager = sessionManager;
        this.maxToolResultChars = maxToolResultChars;
        this.memoryStore = memoryStore;
        this.memoryWritePolicy = memoryWritePolicy != null ? memoryWritePolicy : new MemoryWritePolicy();
    }

    /**
     * 持久化交互式轮次（用户发起的对话）
     *
     * @param request  Agent 请求上下文，包含会话、历史消息等信息
     * @param outcome  执行结果，包含运行结果和最终内容
     * @return PersistenceResult 包含更新后的会话和 outbound 消息
     */
    PersistenceResult persistInteractiveTurn(AgentRequestContext request, ExecutionOutcome outcome) {
        // 获取当前会话对象
        Session session = request.session();
        // 计算需要跳过保存的消息数量：1(当前用户消息) + 历史消息数 + (如果用户早期已持久化则+1)
        int saveSkip = 1 + request.history().size() + (request.userPersistedEarly() ? 1 : 0);
        // 保存本轮产生的新消息到会话中，跳过已存在的消息
        saveTurn(session, outcome.runResult().getMessages(), saveSkip);
        // 更新工具调用轨迹
        updateToolTrace(session, outcome.runResult());
        updateRunTrace(session, outcome.runResult());
        updateContextTrace(session, request.contextTrace());
        // 更新任务状态（完成或阻塞）
        updateTaskState(session, outcome);
        appendMemoryCandidates(request.message(), outcome);

        // 清理会话元数据中的临时运行时键
        markCheckpointCommitted(session);
        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RECOVERY_DECISIONS_KEY);
        session.getMetadata().remove("_last_interrupt_reason");
        // 保存会话到存储
        sessionManager.save(session);

        // 构建返回给用户的出站消息
        OutboundMessage outbound = buildOutboundMessage(request.message(), outcome.finalContent());
        return new PersistenceResult(session, outbound);
    }

    /**
     * 持久化系统轮次（系统主动发起的消息，如通知、定时任务等）
     *
     * @param sourceMessage 源入站消息（可能为 null 或触发源）
     * @param channel       渠道标识
     * @param chatId        聊天 ID
     * @param request       Agent 请求上下文
     * @param outcome       执行结果
     * @return PersistenceResult 包含更新后的会话和 outbound 消息
     */
    PersistenceResult persistSystemTurn(
            InboundMessage sourceMessage,
            String channel,
            String chatId,
            AgentRequestContext request,
            ExecutionOutcome outcome
    ) {
        // 获取当前会话对象
        Session session = request.session();
        // 保存本轮产生的新消息到会话中，跳过历史消息
        saveTurn(session, outcome.runResult().getMessages(), 1 + request.history().size());
        // 更新工具调用轨迹
        updateToolTrace(session, outcome.runResult());
        updateRunTrace(session, outcome.runResult());
        updateContextTrace(session, request.contextTrace());
        // 更新任务状态
        updateTaskState(session, outcome);
        // 清理会话元数据中的临时运行时键
        markCheckpointCommitted(session);
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RECOVERY_DECISIONS_KEY);
        session.getMetadata().remove("_last_interrupt_reason");
        // 保存会话到存储
        sessionManager.save(session);

        // 构建系统出站消息
        OutboundMessage outbound = OutboundMessages.of(channel, chatId, outcome.finalContent());
        return new PersistenceResult(session, outbound);
    }

    private void markCheckpointCommitted(Session session) {
        Object raw = session.getMetadata().get(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        if (!(raw instanceof Map<?, ?> checkpoint)) {
            return;
        }
        Object checkpointId = checkpoint.get("checkpoint_id");
        if (checkpointId != null && !String.valueOf(checkpointId).isBlank()) {
            session.getMetadata().put(
                    SessionRuntimeKeys.LAST_RESTORED_CHECKPOINT_ID_KEY,
                    String.valueOf(checkpointId).trim()
            );
        }
    }

    /**
     * 构建回复给用户的出站消息
     *
     * @param msg         原始入站消息
     * @param finalContent 最终回复内容
     * @return OutboundMessage 出站消息对象
     */
    private OutboundMessage buildOutboundMessage(InboundMessage msg, String finalContent) {
        return OutboundMessages.replyTo(msg, finalContent);
    }

    /**
     * 保存轮次消息到会话中
     *
     * @param session  会话对象
     * @param messages 消息列表
     * @param skip     需要跳过的消息数量（即前 skip 条消息不保存）
     */
    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        // 如果消息列表为空或 null，直接返回
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 计算开始保存的索引位置，确保不越界
        int start = Math.max(0, Math.min(skip, messages.size()));
        // 遍历需要保存的消息
        for (int i = start; i < messages.size(); i++) {
            // 规范化单条消息条目
            Map<String, Object> normalized = normalizeTurnEntry(messages.get(i));
            // 如果规范化后的消息不为 null，则添加到会话消息列表中
            if (normalized != null) {
                session.getMessages().add(normalized);
            }
        }
        // 更新会话的最后更新时间
        session.setUpdatedAt(Instant.now());
    }

    /**
     * 规范化单条消息条目，处理空内容、截断过长工具结果、清理运行时上下文标签等
     *
     * @param raw 原始消息映射
     * @return 规范化后的消息映射，如果该消息应被丢弃则返回 null
     */
    private Map<String, Object> normalizeTurnEntry(Map<String, Object> raw) {
        // 如果原始消息为 null，返回 null
        if (raw == null) {
            return null;
        }

        // 创建一个新的 LinkedHashMap 以保留插入顺序并复制原始数据
        Map<String, Object> entry = new LinkedHashMap<>(raw);
        entry = SessionMessage.fromMap(entry).toMap();
        // 获取角色和内容
        Object role = entry.get("role");
        Object content = entry.get("content");

        // 如果是 assistant 角色且内容为空且没有工具调用，则丢弃该消息
        if ("assistant".equals(role) && (content == null || String.valueOf(content).isBlank()) && !entry.containsKey("tool_calls")) {
            return null;
        }

        // 如果是 tool 角色且内容字符串长度超过限制，则截断内容
        if ("tool".equals(role) && content instanceof String s && s.length() > maxToolResultChars) {
            entry.put("content", HelperUtils.truncateText(s, maxToolResultChars));
            content = entry.get("content");
        }

        // 如果是 user 角色且内容以运行时上下文标签开头，则提取标签后的实际用户内容
        if ("user".equals(role) && content instanceof String s && s.startsWith(ContextBuilder.RUNTIME_CONTEXT_TAG)) {
            // 查找结束标签的位置
            int endPos = s.indexOf(ContextBuilder.RUNTIME_CONTEXT_END);
            if (endPos >= 0) {
                // 提取结束标签之后的内容并去除前导空白
                String after = s.substring(endPos + ContextBuilder.RUNTIME_CONTEXT_END.length()).stripLeading();
                // 如果提取后的内容为空，则丢弃该消息
                if (after.isBlank()) {
                    return null;
                }
                // 更新消息内容为提取后的实际内容
                entry.put("content", after);
            }
        }

        // 如果不存在时间戳字段，则添加当前时间戳
        entry.putIfAbsent("timestamp", Instant.now().toString());
        return entry;
    }

    /**
     * 更新会话中的工具调用轨迹摘要
     *
     * @param session 会话对象
     * @param result  Agent 运行结果
     */
    private void updateToolTrace(Session session, AgentRunResult result) {
        // 使用摘要器生成工具事件摘要
        List<Map<String, Object>> traces = toolTraceSummarizer.summarize(result.getToolEvents());
        // 如果没有摘要记录，直接返回
        if (traces.isEmpty()) {
            return;
        }
        // 计算需要保留的最新轨迹数量，最少4条，最多12条
        int keep = Math.max(4, Math.min(12, traces.size()));
        // 将最新的 keep 条轨迹存入会话元数据
        session.getMetadata().put(
                SessionRuntimeKeys.TOOL_TRACE_KEY,
                new java.util.ArrayList<>(traces.subList(Math.max(0, traces.size() - keep), traces.size()))
        );
    }

    private void updateRunTrace(Session session, AgentRunResult result) {
        if (result == null) {
            return;
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("run_id", result.getRunId());
        trace.put("started_at", result.getStartedAt());
        trace.put("ended_at", result.getEndedAt());
        trace.put("iterations", result.getIterations());
        trace.put("stop_reason", result.getStopReason());
        trace.put("error", result.getError());
        trace.put("tools_used", result.getToolsUsed());
        trace.put("usage", result.getUsage());

        List<Map<String, Object>> events = result.getRunEvents() != null ? result.getRunEvents() : List.of();
        int keep = Math.min(80, events.size());
        trace.put("events", new java.util.ArrayList<>(events.subList(Math.max(0, events.size() - keep), events.size())));
        session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, trace);
    }

    private void updateContextTrace(Session session, Map<String, Object> contextTrace) {
        if (contextTrace == null || contextTrace.isEmpty()) {
            return;
        }
        session.getMetadata().put(SessionRuntimeKeys.CONTEXT_TRACE_KEY, new LinkedHashMap<>(contextTrace));
    }

    /**
     * 更新会话中的任务状态（标记为完成或阻塞）
     *
     * @param session 会话对象
     * @param outcome 执行结果
     */
    private void updateTaskState(Session session, ExecutionOutcome outcome) {
        // 从会话中获取当前任务状态
        TaskState taskState = TaskState.fromSession(session);
        // 如果停止原因是 "stop"，标记任务为已完成
        if ("stop".equals(outcome.runResult().getStopReason())) {
            taskState.markCompleted(outcome.finalContent());
        } 
        // 如果运行结果中有错误信息，标记任务为阻塞
        else if (outcome.runResult().getError() != null && !outcome.runResult().getError().isBlank()) {
            taskState.markBlocked(outcome.runResult().getError());
        }
        // 持久化任务状态到会话
        taskState.persist(session);
    }

    private void appendMemoryCandidates(InboundMessage message, ExecutionOutcome outcome) {
        if (memoryStore == null || message == null || message.getContent() == null) {
            return;
        }
        List<MemoryEntry> candidates = memoryWritePolicy.createCandidates(message.getContent());
        if (!candidates.isEmpty()) {
            memoryStore.appendMemoryCandidates(candidates);
        }
    }
}
