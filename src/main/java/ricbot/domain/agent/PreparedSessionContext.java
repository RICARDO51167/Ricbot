package ricbot.domain.agent;

import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

final class PreparedSessionContext {

    private final String sessionKey;
    private final Session session;
    private final String summaryContext;
    private final OutboundMessage immediateResponse;
    private final boolean userPersistedEarly;

    PreparedSessionContext(
            String sessionKey,
            Session session,
            String summaryContext,
            OutboundMessage immediateResponse,
            boolean userPersistedEarly
    ) {
        this.sessionKey = sessionKey;
        this.session = session;
        this.summaryContext = summaryContext;
        this.immediateResponse = immediateResponse;
        this.userPersistedEarly = userPersistedEarly;
    }

    String sessionKey() {
        return sessionKey;
    }

    Session session() {
        return session;
    }

    String summaryContext() {
        return summaryContext;
    }

    OutboundMessage immediateResponse() {
        return immediateResponse;
    }

    boolean userPersistedEarly() {
        return userPersistedEarly;
    }

    PreparedSessionContext withUserPersistedEarly(boolean userPersistedEarly) {
        return new PreparedSessionContext(sessionKey, session, summaryContext, immediateResponse, userPersistedEarly);
    }
}
