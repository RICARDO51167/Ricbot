package ricbot.domain.agent;

import ricbot.domain.session.SessionManager;
import java.nio.file.Path;

/** Production persistence is intentionally fixed to the unified SQLite runtime. */
public final class AgentPersistenceFactory {
    private AgentPersistenceFactory() {
    }

    public static AgentPersistenceComponents create(Path workspace, SessionManager providedSessionManager) {
        return AgentPersistenceComponents.unified(workspace, providedSessionManager);
    }
}
