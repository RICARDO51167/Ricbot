package ricbot.domain.agent;

import ricbot.domain.session.SessionManager;
import ricbot.infra.persistence.FileSharedStateStore;

import java.nio.file.Path;
import java.util.Locale;

/** Resolves the persistence backend once, keeping the runtime wiring consistent. */
public final class AgentPersistenceFactory {
    public static final String BACKEND_PROPERTY = "ricbot.persistence.backend";
    public static final String BACKEND_ENV = "RICBOT_PERSISTENCE_BACKEND";

    private AgentPersistenceFactory() {
    }

    public static AgentPersistenceComponents create(Path workspace, SessionManager providedSessionManager) {
        String configured = System.getProperty(BACKEND_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(BACKEND_ENV);
        String backend = configured != null ? configured.trim().toLowerCase(Locale.ROOT) : "local";
        return switch (backend) {
            case "", "local", "file" -> AgentPersistenceComponents.local(workspace, providedSessionManager);
            case "shared", "shared-file" -> AgentPersistenceComponents.shared(
                    workspace, providedSessionManager, new FileSharedStateStore(workspace));
            default -> throw new IllegalArgumentException("unsupported persistence backend: " + backend);
        };
    }
}
