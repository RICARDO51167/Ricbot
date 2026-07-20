package ricbot.domain.agent;

import ricbot.domain.session.SessionManager;
import ricbot.domain.session.SharedSessionManager;
import ricbot.infra.persistence.FileSharedStateStore;
import ricbot.infra.persistence.SharedStateStore;

import java.nio.file.Path;
import java.util.Objects;

/** Coherent persistence bundle used by one agent runtime. */
public record AgentPersistenceComponents(
        SessionManager sessionManager,
        RunCheckpointStore checkpointStore,
        RunJournalStore journalStore,
        SideEffectStore sideEffectStore,
        SharedStateStore sharedStateStore,
        String backend
) {
    public AgentPersistenceComponents {
        Objects.requireNonNull(sessionManager, "sessionManager");
        Objects.requireNonNull(checkpointStore, "checkpointStore");
        Objects.requireNonNull(journalStore, "journalStore");
        Objects.requireNonNull(sideEffectStore, "sideEffectStore");
        Objects.requireNonNull(sharedStateStore, "sharedStateStore");
        backend = backend != null && !backend.isBlank() ? backend : "local";
    }

    public static AgentPersistenceComponents local(Path workspace, SessionManager providedSessionManager) {
        SharedStateStore shared = new FileSharedStateStore(workspace);
        return new AgentPersistenceComponents(
                providedSessionManager != null ? providedSessionManager : new SessionManager(workspace),
                new FileRunCheckpointStore(workspace),
                new FileRunJournalStore(workspace),
                new FileSideEffectStore(workspace),
                shared,
                "local"
        );
    }

    public static AgentPersistenceComponents shared(Path workspace, SessionManager providedSessionManager,
                                                     SharedStateStore shared) {
        Objects.requireNonNull(shared, "shared");
        return new AgentPersistenceComponents(
                providedSessionManager != null ? providedSessionManager : new SharedSessionManager(workspace, shared),
                new SharedRunCheckpointStore(shared),
                new SharedRunJournalStore(shared),
                new SharedSideEffectStore(shared),
                shared,
                "shared"
        );
    }
}
