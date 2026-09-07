package ricbot.domain.runtime;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test seam for deterministic process-crash simulation at durable-runtime boundaries. */
@FunctionalInterface
public interface CrashInjector {
    CrashInjector NONE = (point, detail) -> { };

    void at(Point point, Map<String, Object> detail);

    default void at(Point point) { at(point, Map.of()); }

    static CrashInjector failOnce(Point target) {
        AtomicBoolean fired = new AtomicBoolean();
        return (point, detail) -> {
            if (point == target && fired.compareAndSet(false, true)) throw new InjectedCrash(point);
        };
    }

    enum Point {
        MODEL_BEFORE_OBSERVATION_PERSIST,
        MODEL_AFTER_OBSERVATION_PERSIST,
        EFFECT_BEFORE_DISPATCH,
        EFFECT_AFTER_DISPATCH,
        PENDING_WRITE_STAGED,
        COMMIT_BEFORE,
        COMMIT_AFTER,
        CHILD_PARENT_HANDOFF,
        INBOX_CONSUMPTION,
        ACTIVATION_LEASE_ACQUIRED,
        RESOURCE_LEASE_ACQUIRED,
        CANCELLATION_PERSISTED
    }

    final class InjectedCrash extends Error {
        private final Point point;
        public InjectedCrash(Point point) {
            super("injected crash at " + point);
            this.point = point;
        }
        public Point point() { return point; }
    }
}
