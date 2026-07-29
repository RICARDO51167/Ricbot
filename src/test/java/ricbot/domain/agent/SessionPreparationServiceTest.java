package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.dto.PreparedSessionContext;
import ricbot.domain.agent.dto.SessionPreparationService;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.SessionManager;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.runtime.SqliteSessionManager;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionPreparationServiceTest {
    @Test
    void preparesSystemSessionWithoutSynthesizingRecoveryMessages(@TempDir Path workspace) {
        SessionManager sessions = new SqliteSessionManager(new SqliteRuntimeStore(workspace));
        var session = sessions.getOrCreate("session");
        session.addMessage("user", "waiting");
        sessions.save(session);
        SessionPreparationService service = new SessionPreparationService(sessions);
        PreparedSessionContext prepared = service.prepareSystemTurn("session");
        assertEquals(1, prepared.session().getMessages().size());
        assertEquals("user", prepared.session().getMessages().get(0).get("role"));
    }

    @Test
    void dispatchesCommandsWithoutLegacyCheckpointRecovery(@TempDir Path workspace) {
        SessionManager sessions = new SqliteSessionManager(new SqliteRuntimeStore(workspace));
        SessionPreparationService service = new SessionPreparationService(sessions);
        PreparedSessionContext prepared = service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "chat", "/status"), "session",
                (message, session, key, raw) -> new ricbot.domain.message.OutboundMessage("cli", "chat", "ok"));
        assertEquals("ok", prepared.immediateResponse().getContent());
    }
}
