package ricbot.domain.agent.budget;

import ricbot.domain.agent.graph.dto.GraphRuntimeEvent;
import ricbot.domain.agent.graph.enump.GraphRuntimeEventType;
import ricbot.domain.agent.graph.interfacep.GraphRuntimeStore;
import ricbot.domain.agent.usage.UsageDelta;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-backed reservation coordinator shared by root and child Runs. The lock
 * prevents sibling Workers in this runtime process from overselling a root
 * budget; the event deduplication key makes retries idempotent and crash-safe.
 */
public final class BudgetCoordinator {
    private static final ConcurrentHashMap<String, Object> ROOT_LOCKS = new ConcurrentHashMap<>();
    private final GraphRuntimeStore store;

    public BudgetCoordinator(GraphRuntimeStore store) { this.store = java.util.Objects.requireNonNull(store); }

    public BudgetReservation reserve(String rootRunId, String runId, String taskId, String reservationId,
                                     BudgetPolicy policy, long tokens, long costMicrousd,
                                     long toolCalls, long activeMillis) {
        String root = clean(rootRunId).isBlank() ? clean(runId) : clean(rootRunId);
        Object lock = ROOT_LOCKS.computeIfAbsent(root, ignored -> new Object());
        synchronized (lock) {
            Totals totals = totals(root);
            BudgetReservation existing = totals.reservations.get(reservationId);
            if (existing != null) return existing;
            BudgetPolicy limit = policy != null ? policy : BudgetPolicy.unlimited();
            require(limit.maxTotalTokens(), totals.tokens + tokens, limit.finalizationTokens(), "token_budget");
            require(limit.maxCostMicrousd(), totals.cost + costMicrousd, 0, "cost_budget");
            require(limit.maxToolCalls(), totals.tools + toolCalls, 0, "tool_call_budget");
            require(limit.maxActiveSeconds() == null ? null : limit.maxActiveSeconds() * 1000L,
                    totals.active + activeMillis, 0, "active_time_budget");
            BudgetReservation reservation = new BudgetReservation(reservationId, root, runId, taskId,
                    Math.max(0, tokens), Math.max(0, costMicrousd), Math.max(0, toolCalls),
                    Math.max(0, activeMillis), Instant.now(), false);
            store.append(root, 0, GraphRuntimeEventType.BUDGET_RESERVED, map(reservation),
                    "budget-reserved:" + reservationId);
            return reservation;
        }
    }

    public void settle(BudgetReservation reservation, UsageDelta actual) {
        if (reservation == null || actual == null) return;
        String root = reservation.rootRunId();
        synchronized (ROOT_LOCKS.computeIfAbsent(root, ignored -> new Object())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reservationId", reservation.reservationId());
            data.put("runId", reservation.runId());
            data.put("taskId", reservation.taskId());
            data.put("tokens", actual.totalTokens());
            data.put("costMicrousd", actual.costMicrousd());
            data.put("toolCalls", actual.toolCalls());
            data.put("activeMillis", actual.activeMillis());
            store.append(root, 0, GraphRuntimeEventType.BUDGET_SETTLED, Map.copyOf(data),
                    "budget-settled:" + reservation.reservationId());
        }
    }

    private Totals totals(String root) {
        Map<String, BudgetReservation> reservations = new LinkedHashMap<>();
        Map<String, long[]> settlements = new LinkedHashMap<>();
        for (GraphRuntimeEvent event : store.events(root)) {
            if (event.type() == GraphRuntimeEventType.BUDGET_RESERVED) {
                Map<String, Object> data = event.data();
                String id = clean(data.get("reservationId"));
                reservations.putIfAbsent(id, new BudgetReservation(id, root, clean(data.get("runId")),
                        clean(data.get("taskId")), number(data, "tokens"), number(data, "costMicrousd"),
                        number(data, "toolCalls"), number(data, "activeMillis"), event.occurredAt(), false));
            } else if (event.type() == GraphRuntimeEventType.BUDGET_SETTLED) {
                settlements.putIfAbsent(clean(event.data().get("reservationId")), new long[]{
                        number(event.data(), "tokens"), number(event.data(), "costMicrousd"),
                        number(event.data(), "toolCalls"), number(event.data(), "activeMillis")});
            }
        }
        long tokens = 0, cost = 0, tools = 0, active = 0;
        for (BudgetReservation reservation : reservations.values()) {
            long[] settled = settlements.get(reservation.reservationId());
            tokens = add(tokens, settled != null ? settled[0] : reservation.tokens());
            cost = add(cost, settled != null ? settled[1] : reservation.costMicrousd());
            tools = add(tools, settled != null ? settled[2] : reservation.toolCalls());
            active = add(active, settled != null ? settled[3] : reservation.activeMillis());
        }
        return new Totals(reservations, tokens, cost, tools, active);
    }

    private static Map<String, Object> map(BudgetReservation value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservationId", value.reservationId()); result.put("rootRunId", value.rootRunId());
        result.put("runId", value.runId()); result.put("taskId", value.taskId());
        result.put("tokens", value.tokens()); result.put("costMicrousd", value.costMicrousd());
        result.put("toolCalls", value.toolCalls()); result.put("activeMillis", value.activeMillis());
        result.put("createdAt", value.createdAt().toString()); result.put("settled", value.settled());
        return Map.copyOf(result);
    }
    private static void require(Long limit, long requested, long reserve, String reason) {
        if (limit != null && requested > Math.max(0, limit - reserve)) throw new BudgetExhaustedException(reason);
    }
    private static long number(Map<String, Object> data, String key) {
        Object value = data.get(key); return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }
    private static long add(long left, long right) {
        try { return Math.addExact(left, right); } catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    private static String clean(Object value) { return value != null ? String.valueOf(value).trim() : ""; }
    private record Totals(Map<String, BudgetReservation> reservations, long tokens, long cost, long tools, long active) { }

    public static final class BudgetExhaustedException extends RuntimeException {
        private final String reason;
        public BudgetExhaustedException(String reason) { super("budget reservation rejected: " + reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
