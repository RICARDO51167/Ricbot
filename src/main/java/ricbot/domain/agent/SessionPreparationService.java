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

    PreparedSessionContext prepareInteractiveTurn(InboundMessage msg, String sessionKey, CommandDispatcher commandDispatcher) {
        Session baseSession = sessionManager.getOrCreate(sessionKey);
        AutoCompact.PreparedSession prepared = autoCompact.prepareSession(baseSession, sessionKey);
        Session session = prepared != null ? prepared.session() : baseSession;
        String summaryContext = prepared != null ? prepared.summary() : null;

        consolidator.maybeConsolidateByTokens(session);
        restoreRuntimeCheckpoint(session);
        restorePendingUserTurn(session);

        OutboundMessage immediateResponse = null;
        String raw = trim(msg.getContent());
        if (raw != null && raw.startsWith("/")) {
            immediateResponse = commandDispatcher.dispatch(msg, session, sessionKey, raw);
        }

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
        Object raw = session.getMetadata().get(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }

        Map<String, Object> checkpoint = (Map<String, Object>) rawMap;
        Object assistantMessage = checkpoint.get("assistant_message");
        Object completedToolResults = checkpoint.get("completed_tool_results");
        Object pendingToolCalls = checkpoint.get("pending_tool_calls");
        Object taskState = checkpoint.get("task_state");

        if (assistantMessage instanceof Map<?, ?> assistant) {
            session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) assistant));
        }

        if (completedToolResults instanceof List<?> completed) {
            for (Object item : completed) {
                if (item instanceof Map<?, ?> result) {
                    session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) result));
                }
            }
        }

        if (pendingToolCalls instanceof List<?> pending) {
            for (Object item : pending) {
                if (!(item instanceof Map<?, ?> toolCall)) {
                    continue;
                }

                Map<String, Object> function = toolCall.get("function") instanceof Map<?, ?> fn
                        ? (Map<String, Object>) fn
                        : new LinkedHashMap<>();

                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCall.get("id"));
                toolMessage.put("name", function.getOrDefault("name", "tool"));
                toolMessage.put("content", interruptedToolMessage(checkpoint, session));
                toolMessage.put("timestamp", Instant.now().toString());
                session.getMessages().add(toolMessage);
            }
        }

        if (taskState instanceof Map<?, ?> taskMap) {
            session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, new LinkedHashMap<>((Map<String, Object>) taskMap));
        }

        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
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
