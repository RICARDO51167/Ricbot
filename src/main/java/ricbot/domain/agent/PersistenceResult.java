package ricbot.domain.agent;

import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

record PersistenceResult(Session session, OutboundMessage outboundMessage) {

}
