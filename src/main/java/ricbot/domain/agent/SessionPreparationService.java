package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionPreparationService {

    @FunctionalInterface
    interface CommandDispatcher {
        OutboundMessage dispatch(InboundMessage msg, Session session, String sessionKey, String raw);
    }

    private final SessionManager sessionManager;
    private final AutoCompact autoCompact;
    private final Consolidator consolidator;

    SessionPreparationService(SessionManager sessionManager, AutoCompact autoCompact, Consolidator consolidator) {
        this.sessionManager = sessionManager;
        this.autoCompact = autoCompact;
        this.consolidator = consolidator;
    }

    /**
     * 准备交互式对话轮次的上下文。
     *
     * @param msg               接收到的入站消息
     * @param sessionKey        会话的唯一标识键
     * @param commandDispatcher 命令分发器，用于处理以 "/" 开头的特殊指令
     * @return 包含处理后会话状态和可能立即响应的 PreparedSessionContext 对象
     */
    PreparedSessionContext prepareInteractiveTurn(InboundMessage msg, String sessionKey, CommandDispatcher commandDispatcher) {
        // 获取或创建基础会话对象
        Session baseSession = sessionManager.getOrCreate(sessionKey);

        // 尝试进行会话自动压缩准备（如生成摘要等）
        AutoCompact.PreparedSession prepared = autoCompact.prepareSession(baseSession, sessionKey);

        // 如果压缩准备成功，使用新的会话实例和摘要；否则使用原始会话且无摘要
        Session session = prepared != null ? prepared.session() : baseSession;
        String summaryContext = prepared != null ? prepared.summary() : null;

        // 根据 Token 数量判断是否需要合并/压缩历史消息
        consolidator.maybeConsolidateByTokens(session);

        // 恢复运行时检查点（如中断前的工具调用状态、任务状态等）
        restoreRuntimeCheckpoint(session);

        // 恢复待处理的用户轮次标记（处理之前未完成的交互）
        restorePendingUserTurn(session);

        // 初始化立即响应对象，默认为 null
        OutboundMessage immediateResponse = null;

        // 获取并修剪消息内容
        String raw = trim(msg.getContent());

        // 如果消息内容非空且以 "/" 开头，视为命令，通过分发器处理并获取立即响应
        if (raw != null && raw.startsWith("/")) {
            immediateResponse = commandDispatcher.dispatch(msg, session, sessionKey, raw);
        }

        // 构建并返回准备好的会话上下文，包含会话信息、摘要、任务状态及可能的立即响应
        return new PreparedSessionContext(sessionKey, session, summaryContext, TaskState.fromSession(session), immediateResponse, false);
    }

    PreparedSessionContext prepareSystemTurn(String sessionKey) {
        Session session = sessionManager.getOrCreate(sessionKey);
        restoreRuntimeCheckpoint(session);
        restorePendingUserTurn(session);
        return new PreparedSessionContext(sessionKey, session, null, TaskState.fromSession(session), null, false);
    }

    PreparedSessionContext persistUserTurnIfNeeded(PreparedSessionContext prepared, InboundMessage msg) {
        if (msg.getContent() == null || msg.getContent().isBlank()) {
            return prepared;
        }

        prepared.session().addMessage("user", msg.getContent());
        prepared.session().getMetadata().put(SessionRuntimeKeys.PENDING_USER_TURN_KEY, true);
        TaskState taskState = TaskState.fromSession(prepared.session());
        taskState.beginTurn(msg.getContent());
        taskState.persist(prepared.session());
        sessionManager.save(prepared.session());
        return prepared.withTaskStateSnapshot(taskState).withUserPersistedEarly(true);
    }

    @SuppressWarnings("unchecked")
    void restoreRuntimeCheckpoint(Session session) {
        // 从会话元数据中获取运行时检查点对象
        Object raw = session.getMetadata().get(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        // 如果检查点不是 Map 类型，则直接返回，不进行恢复操作
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }

        // 将原始对象强制转换为 Map<String, Object> 以便后续处理
        Map<String, Object> checkpoint = (Map<String, Object>) rawMap;
        // 提取检查点中的各个组成部分：助手消息、已完成的工具结果、待处理的工具调用、任务状态
        Object assistantMessage = checkpoint.get("assistant_message");
        Object completedToolResults = checkpoint.get("completed_tool_results");
        Object pendingToolCalls = checkpoint.get("pending_tool_calls");
        Object taskState = checkpoint.get("task_state");

        // 如果存在助手消息且为 Map 类型，将其添加到会话消息列表中
        if (assistantMessage instanceof Map<?, ?> assistant) {
            session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) assistant));
        }

        // 如果存在已完成的工具结果列表，遍历并添加每个有效的结果消息到会话中
        if (completedToolResults instanceof List<?> completed) {
            for (Object item : completed) {
                if (item instanceof Map<?, ?> result) {
                    session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) result));
                }
            }
        }

        // 如果存在待处理的工具调用列表，为每个未完成的调用生成一个中断错误消息并添加到会话中
        if (pendingToolCalls instanceof List<?> pending) {
            for (Object item : pending) {
                // 跳过非 Map 类型的无效项
                if (!(item instanceof Map<?, ?> toolCall)) {
                    continue;
                }

                // 提取工具调用的函数定义，如果不存在则创建空 Map
                Map<String, Object> function = toolCall.get("function") instanceof Map<?, ?> fn
                        ? (Map<String, Object>) fn
                        : new LinkedHashMap<>();

                // 构建表示工具执行中断的消息对象
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool"); // 角色标记为 tool
                toolMessage.put("tool_call_id", toolCall.get("id")); // 关联的工具调用 ID
                toolMessage.put("name", function.getOrDefault("name", "tool")); // 工具名称，默认为 "tool"
                toolMessage.put("content", interruptedToolMessage(checkpoint, session)); // 中断原因描述
                toolMessage.put("timestamp", Instant.now().toString()); // 当前时间戳
                session.getMessages().add(toolMessage); // 将中断消息加入会话历史
            }
        }

        // 如果存在任务状态且为 Map 类型，将其恢复至会话元数据中
        if (taskState instanceof Map<?, ?> taskMap) {
            session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, new LinkedHashMap<>((Map<String, Object>) taskMap));
        }

        // 清理元数据中的临时标记和已恢复的检查点数据
        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        // 保存更新后的会话状态
        sessionManager.save(session);
    }

    void restorePendingUserTurn(Session session) {
        Object flag = session.getMetadata().get(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        if (!(flag instanceof Boolean b) || !b) {
            return;
        }

        List<Map<String, Object>> messages = session.getMessages();
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if ("user".equals(last.get("role"))) {
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", "错误：任务在生成回复前被中断。");
                assistant.put("timestamp", Instant.now().toString());
                messages.add(assistant);
                session.setUpdatedAt(Instant.now());
            }
        }

        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        sessionManager.save(session);
    }

    private String interruptedToolMessage(Map<String, Object> checkpoint, Session session) {
        String reason = null;
        Object checkpointReason = checkpoint.get("interruption_reason");
        if (checkpointReason instanceof String s && !s.isBlank()) {
            reason = s;
        }
        if (reason == null) {
            Object sessionReason = session.getMetadata().get("_last_interrupt_reason");
            if (sessionReason instanceof String s && !s.isBlank()) {
                reason = s;
            }
        }
        if (reason == null) {
            reason = "interrupted";
        }

        return switch (reason) {
            case "manual_stop" -> "错误：任务在该工具执行完成前被手动停止。";
            case "shutdown" -> "错误：任务在该工具执行完成前因服务关闭而中断。";
            case "timeout" -> "错误：任务在该工具执行完成前因超时而中断。";
            default -> "错误：任务在该工具执行完成前被中断。";
        };
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
