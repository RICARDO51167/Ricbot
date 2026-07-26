package ricbot.domain.agent;

import ricbot.domain.session.SessionManager;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.runtime.SqliteSessionManager;

import java.nio.file.Path;
import java.util.Objects;

/** SQLite-backed persistence components for the single runtime. */
public record AgentPersistenceComponents(SessionManager sessionManager, SideEffectStore sideEffectStore) {
    public AgentPersistenceComponents {
        Objects.requireNonNull(sessionManager, "sessionManager");
        Objects.requireNonNull(sideEffectStore, "sideEffectStore");
    }

    public static AgentPersistenceComponents unified(Path workspace, SessionManager supplied) {
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(workspace);
        return new AgentPersistenceComponents(
                supplied != null ? supplied : new SqliteSessionManager(workspace, runtime), runtime.sideEffectStore());
    }
}
