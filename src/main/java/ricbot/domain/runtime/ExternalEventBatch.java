package ricbot.domain.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One atomic batch containing a primary external event and any preplanned derived events. */
public record ExternalEventBatch(List<ExternalEventCommit> commits) {
    public ExternalEventBatch {
        commits = List.copyOf(commits != null ? commits : List.of());
        if (commits.isEmpty()) throw new IllegalArgumentException("external event batch is empty");
        Set<String> eventIds = new HashSet<>();
        for (ExternalEventCommit commit : commits) {
            if (!eventIds.add(commit.event().eventId())) {
                throw new IllegalArgumentException("duplicate event in batch: " + commit.event().eventId());
            }
        }
    }

    public static ExternalEventBatch single(ExternalEventCommit commit) {
        return new ExternalEventBatch(List.of(commit));
    }
}
