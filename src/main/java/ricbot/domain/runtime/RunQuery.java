package ricbot.domain.runtime;

import java.util.List;
import java.util.Optional;

/** Read-only query surface, intentionally separate from runtime commands. */
public interface RunQuery {
    List<RunView> list();
    Optional<RunView> get(String runId);
    List<RunView> children(String runId);
    List<RuntimeEvent> events(String runId);
    List<TimelineEvent> timeline(String runId);
}
