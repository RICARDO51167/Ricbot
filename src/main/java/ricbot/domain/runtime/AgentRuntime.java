package ricbot.domain.runtime;

import ricbot.domain.runtime.dto.ReplayView;
import ricbot.domain.runtime.dto.RunRequest;
import ricbot.domain.runtime.dto.RunView;
import ricbot.domain.runtime.dto.RuntimeSignal;

public interface AgentRuntime extends AutoCloseable {
    RunView start(RunRequest request);
    RunView resume(String runId);
    RunView signal(String runId, RuntimeSignal signal);
    RunView cancel(String runId, String reason);
    ReplayView replay(String runId, long throughEventSequence);
    ReplayView fork(String runId, long throughEventSequence, String newRunId);
    AutoCloseable subscribe(RuntimeEventSubscriber subscriber);
    @Override void close();
}
