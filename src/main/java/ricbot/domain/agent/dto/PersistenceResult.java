package ricbot.domain.agent.dto;

import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

public record PersistenceResult(Session session, OutboundMessage outboundMessage) {

}
