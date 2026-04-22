package ricbot.domain.agent;

import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

final class PreparedSessionContext {

    private final String sessionKey;
    private final Session session;
    private final String archivedSummary;
    private final TaskState taskStateSnapshot;
    private final OutboundMessage immediateResponse;
    private final boolean userPersistedEarly;

    PreparedSessionContext(
            String sessionKey,
            Session session,
            String archivedSummary,
            TaskState taskStateSnapshot,
            OutboundMessage immediateResponse,
            boolean userPersistedEarly
    ) {
        this.sessionKey = sessionKey;
        this.session = session;
        this.archivedSummary = archivedSummary;
        this.taskStateSnapshot = taskStateSnapshot;
        this.immediateResponse = immediateResponse;
        this.userPersistedEarly = userPersistedEarly;
    }

    String sessionKey() {
        return sessionKey;
    }

    Session session() {
        return session;
    }

    String archivedSummary() {
        return archivedSummary;
    }

    TaskState taskStateSnapshot() {
        return taskStateSnapshot;
    }

    OutboundMessage immediateResponse() {
        return immediateResponse;
    }

    boolean userPersistedEarly() {
        return userPersistedEarly;
    }

    PreparedSessionContext withUserPersistedEarly(boolean userPersistedEarly) {
        return new PreparedSessionContext(sessionKey, session, archivedSummary, taskStateSnapshot, immediateResponse, userPersistedEarly);
    }

    PreparedSessionContext withTaskStateSnapshot(TaskState taskStateSnapshot) {
        return new PreparedSessionContext(sessionKey, session, archivedSummary, taskStateSnapshot, immediateResponse, userPersistedEarly);
    }
}
