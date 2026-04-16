package ricbot.domain.agent;

import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

final class PersistenceResult {

    private final Session session;
    private final OutboundMessage outboundMessage;

    PersistenceResult(Session session, OutboundMessage outboundMessage) {
        this.session = session;
        this.outboundMessage = outboundMessage;
    }

    Session session() {
        return session;
    }

    OutboundMessage outboundMessage() {
        return outboundMessage;
    }
}
