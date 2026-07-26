package ricbot.domain.agent;

import ricbot.domain.agent.context.StructuredContextService;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

/** Prepares session projections; graph recovery is owned by AgentRuntime.resume. */
record SessionPreparationService(SessionManager sessionManager, StructuredContextService contextCompaction) {
    @FunctionalInterface
    interface CommandDispatcher {
        OutboundMessage dispatch(InboundMessage message, Session session, String sessionKey, String raw);
    }

    PreparedSessionContext prepareInteractiveTurn(InboundMessage message, String sessionKey,
                                                   CommandDispatcher dispatcher) {
        Session session = sessionManager.getOrCreate(sessionKey);
        clearLegacyPendingMarker(session);
        // Context/Compact graph nodes are the only owners of message compaction.
        String raw = message.getContent() != null ? message.getContent().trim() : "";
        OutboundMessage immediate = raw.startsWith("/")
                ? dispatcher.dispatch(message, session, sessionKey, raw) : null;
        return new PreparedSessionContext(sessionKey, session, null, TaskState.fromSession(session), immediate, false);
    }

    PreparedSessionContext prepareSystemTurn(String sessionKey) {
        Session session = sessionManager.getOrCreate(sessionKey);
        clearLegacyPendingMarker(session);
        return new PreparedSessionContext(sessionKey, session, null, TaskState.fromSession(session), null, false);
    }

    PreparedSessionContext persistUserTurnIfNeeded(PreparedSessionContext prepared, InboundMessage message) {
        if (message.getContent() == null || message.getContent().isBlank()) return prepared;
        prepared.session().addMessage("user", message.getContent());
        TaskState state = TaskState.fromSession(prepared.session());
        state.beginTurn(message.getContent());
        state.persist(prepared.session());
        sessionManager.save(prepared.session());
        return prepared.withTaskStateSnapshot(state).withUserPersistedEarly();
    }

    private void clearLegacyPendingMarker(Session session) {
        if (session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY) != null) sessionManager.save(session);
    }
}
