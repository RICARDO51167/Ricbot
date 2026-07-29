package ricbot.domain.runtime;

import ricbot.domain.runtime.dto.RuntimeEventEnvelope;

@FunctionalInterface
public interface RuntimeEventSubscriber {
    void onEvent(RuntimeEventEnvelope event);
}
