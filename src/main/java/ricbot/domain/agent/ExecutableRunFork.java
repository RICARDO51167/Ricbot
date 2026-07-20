package ricbot.domain.agent;

import ricbot.domain.session.Session;

import java.util.Objects;

/** A journal branch paired with its independently persisted executable session. */
public record ExecutableRunFork(RunFork lineage, ResumePoint source, Session childSession) {
    public ExecutableRunFork {
        lineage = Objects.requireNonNull(lineage, "lineage");
        source = Objects.requireNonNull(source, "source");
        childSession = Objects.requireNonNull(childSession, "childSession");
    }

    public AgentRunSpec applyTo(AgentRunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return spec.setSessionKey(childSession.getKey())
                .setInitialMessages(childSession.getMessages())
                .setCheckpointMessageOffset(childSession.getMessages().size())
                .setInitialRunState(lineage.childState())
                .setResumeCheckpoint(source.checkpoint());
    }
}
