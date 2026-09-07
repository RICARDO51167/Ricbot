package ricbot.integration.artifact;

import ricbot.domain.artifact.ArtifactDelta;
import ricbot.domain.artifact.ArtifactIntegrator;
import ricbot.domain.change.ChangeSetActionAuthorization;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Git/worktree adapter for the provider-neutral ArtifactDelta port. */
public final class GitChangeSetArtifactIntegrator implements ArtifactIntegrator {
    private final ChangeSetService changes;
    private final ChangeSetActionAuthorization authorization;

    public GitChangeSetArtifactIntegrator(ChangeSetService changes,
                                          ChangeSetActionAuthorization authorization) {
        this.changes = Objects.requireNonNull(changes, "changes");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @Override public IntegrationResult integrate(ArtifactDelta delta) {
        Objects.requireNonNull(delta, "delta");
        if (!"git-patch".equals(delta.kind())) {
            throw new IllegalArgumentException("unsupported ArtifactDelta kind: " + delta.kind());
        }
        String changeSetId = String.valueOf(delta.metadata().getOrDefault("changeSetId", "")).trim();
        String action = String.valueOf(delta.metadata().getOrDefault("action", "")).trim();
        GitChangeSet result = switch (action) {
            case "COMMIT" -> changes.commit(changeSetId,
                    String.valueOf(delta.metadata().getOrDefault("commitMessage", "")), authorization);
            case "ROLLBACK" -> changes.rollback(changeSetId, authorization);
            default -> throw new IllegalArgumentException("unsupported ArtifactDelta integration action: " + action);
        };
        return result(delta, result);
    }

    @Override public Optional<IntegrationResult> reconcile(ArtifactDelta delta, Map<String, Object> evidence) {
        String changeSetId = String.valueOf(delta.metadata().getOrDefault("changeSetId", "")).trim();
        String action = String.valueOf(delta.metadata().getOrDefault("action", "")).trim();
        GitChangeSet reconciled = switch (action) {
            case "COMMIT" -> changes.reconcileCommit(changeSetId, authorization.requestId());
            case "ROLLBACK" -> changes.reconcileRollback(changeSetId);
            default -> null;
        };
        return Optional.ofNullable(reconciled).map(value -> result(delta, value));
    }

    private static IntegrationResult result(ArtifactDelta delta, GitChangeSet result) {
        String reference = !result.commitHash().isBlank()
                ? "git:commit:" + result.commitHash()
                : "artifact:changeset:" + result.id() + ":" + result.status().name().toLowerCase(java.util.Locale.ROOT);
        return new IntegrationResult(reference, Map.of(
                "deltaId", delta.deltaId(),
                "changeSetId", result.id(),
                "status", result.status().name(),
                "changedFiles", result.changedFiles()));
    }
}
