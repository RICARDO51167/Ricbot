package ricbot.domain.agent.budget;

import org.junit.jupiter.api.Test;
import ricbot.domain.agent.graph.InMemoryGraphRuntimeStore;
import ricbot.domain.agent.usage.UsageDelta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class BudgetCoordinatorTest {
    @Test void siblingReservationsAreAtomicAndRetryIsIdempotent() throws Exception {
        InMemoryGraphRuntimeStore store = new InMemoryGraphRuntimeStore();
        BudgetCoordinator coordinator = new BudgetCoordinator(store);
        BudgetPolicy policy = new BudgetPolicy(3000L, null, null, null, 1000, "root");
        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 2; index++) {
                int worker = index;
                executor.submit(() -> {
                    start.await();
                    try { outcomes.add(coordinator.reserve("root", "worker-" + worker, "t" + worker,
                            "reservation-" + worker, policy, 1500, 0, 0, 0)); }
                    catch (BudgetCoordinator.BudgetExhaustedException failure) { outcomes.add(failure); }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2, outcomes.size());
        assertEquals(1, outcomes.stream().filter(BudgetReservation.class::isInstance).count());
        BudgetReservation accepted = outcomes.stream().filter(BudgetReservation.class::isInstance)
                .map(BudgetReservation.class::cast).findFirst().orElseThrow();
        assertEquals(accepted.reservationId(), coordinator.reserve("root", accepted.runId(), accepted.taskId(),
                accepted.reservationId(), policy, 1500, 0, 0, 0).reservationId());
        coordinator.settle(accepted, new UsageDelta(500, 0, 500, 1, 0, 0, 0, 1, 0, "m", false));
        assertDoesNotThrow(() -> coordinator.reserve("root", "worker-new", "t-new", "reservation-new",
                policy, 1400, 0, 0, 0));
    }
}
