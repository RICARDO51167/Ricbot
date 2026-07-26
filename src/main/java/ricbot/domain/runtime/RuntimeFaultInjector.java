package ricbot.domain.runtime;

@FunctionalInterface
public interface RuntimeFaultInjector {
    void check(RuntimeFaultPoint point);
    static RuntimeFaultInjector none() { return ignored -> { }; }
}
