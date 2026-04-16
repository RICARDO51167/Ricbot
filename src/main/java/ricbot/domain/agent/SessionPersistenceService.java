package ricbot.domain.agent;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.infra.common.HelperUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionPersistenceService {

    private final SessionManager sessionManager;
    private final int maxToolResultChars;

    SessionPersistenceService(SessionManager sessionManager, int maxToolResultChars) {
        this.sessionManager = sessionManager;
        this.maxToolResultChars = maxToolResultChars;
    }

    PersistenceResult persistInteractiveTurn(AgentRequestContext request, ExecutionOutcome outcome) {
        Session session = request.session();
        int saveSkip = 1 + request.history().size() + (request.userPersistedEarly() ? 1 : 0);
        saveTurn(session, outcome.runResult().getMessages(), saveSkip);

        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        session.getMetadata().remove("_last_interrupt_reason");
        sessionManager.save(session);

        OutboundMessage outbound = buildOutboundMessage(request.message(), outcome.finalContent());
        return new PersistenceResult(session, outbound);
    }

    PersistenceResult persistSystemTurn(
            InboundMessage sourceMessage,
            String channel,
            String chatId,
            AgentRequestContext request,
            ExecutionOutcome outcome
    ) {
        Session session = request.session();
        saveTurn(session, outcome.runResult().getMessages(), 1 + request.history().size());
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        session.getMetadata().remove("_last_interrupt_reason");
        sessionManager.save(session);

        OutboundMessage outbound = new OutboundMessage();
        outbound.setChannel(channel);
        outbound.setChatId(chatId);
        outbound.setContent(outcome.finalContent());
        outbound.setMetadata(new java.util.HashMap<>());
        return new PersistenceResult(session, outbound);
    }

    private OutboundMessage buildOutboundMessage(InboundMessage msg, String finalContent) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(finalContent);
        out.setMetadata(msg.getMetadata() != null ? new java.util.HashMap<>(msg.getMetadata()) : new java.util.HashMap<>());
        return out;
    }

    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int start = Math.max(0, Math.min(skip, messages.size()));
        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> normalized = normalizeTurnEntry(messages.get(i));
            if (normalized != null) {
                session.getMessages().add(normalized);
            }
        }
        session.setUpdatedAt(Instant.now());
    }

    private Map<String, Object> normalizeTurnEntry(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }

        Map<String, Object> entry = new LinkedHashMap<>(raw);
        Object role = entry.get("role");
        Object content = entry.get("content");

        if ("assistant".equals(role) && (content == null || String.valueOf(content).isBlank()) && !entry.containsKey("tool_calls")) {
            return null;
        }

        if ("tool".equals(role) && content instanceof String s && s.length() > maxToolResultChars) {
            entry.put("content", HelperUtils.truncateText(s, maxToolResultChars));
            content = entry.get("content");
        }

        if ("user".equals(role) && content instanceof String s && s.startsWith(ContextBuilder.RUNTIME_CONTEXT_TAG)) {
            int endPos = s.indexOf(ContextBuilder.RUNTIME_CONTEXT_END);
            if (endPos >= 0) {
                String after = s.substring(endPos + ContextBuilder.RUNTIME_CONTEXT_END.length()).stripLeading();
                if (after.isBlank()) {
                    return null;
                }
                entry.put("content", after);
            }
        }

        entry.putIfAbsent("timestamp", Instant.now().toString());
        return entry;
    }
}
