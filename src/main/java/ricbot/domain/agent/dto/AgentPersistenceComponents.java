package ricbot.domain.agent.dto;

import ricbot.domain.session.SessionManager;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.runtime.SqliteSessionManager;

import java.nio.file.Path;
import java.util.Objects;

/** SQLite-backed persistence components for the single runtime. */
public record AgentPersistenceComponents(SessionManager sessionManager) {
    public AgentPersistenceComponents {
        Objects.requireNonNull(sessionManager, "sessionManager");
    }

    public static AgentPersistenceComponents unified(Path workspace, SessionManager supplied) {
        SqliteRuntimeStore runtime = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(workspace);
        return unified(runtime, supplied);
    }

    public static AgentPersistenceComponents unified(SqliteRuntimeStore runtime, SessionManager supplied) {
        return new AgentPersistenceComponents(
                supplied != null ? supplied : new SqliteSessionManager(runtime));
    }
}
