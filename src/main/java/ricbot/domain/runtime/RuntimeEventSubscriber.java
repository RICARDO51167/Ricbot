package ricbot.domain.runtime;

@FunctionalInterface
public interface RuntimeEventSubscriber {
    void onEvent(RuntimeEventEnvelope event);
}
