package ricbot.domain.agent;

import ricbot.domain.agent.dto.AgentPersistenceComponents;
import ricbot.domain.session.SessionManager;
import java.nio.file.Path;
import ricbot.infra.runtime.SqliteRuntimeStore;

/** Production persistence is intentionally fixed to the unified SQLite runtime. */
public final class AgentPersistenceFactory {
    private AgentPersistenceFactory() {
    }

    public static AgentPersistenceComponents create(Path workspace, SessionManager providedSessionManager) {
        return AgentPersistenceComponents.unified(workspace, providedSessionManager);
    }

    public static AgentPersistenceComponents create(SqliteRuntimeStore store, SessionManager providedSessionManager) {
        return AgentPersistenceComponents.unified(store, providedSessionManager);
    }
}
