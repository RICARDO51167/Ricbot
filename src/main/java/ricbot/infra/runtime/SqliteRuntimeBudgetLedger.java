package ricbot.infra.runtime;

import ricbot.domain.runtime.BudgetUsage;
import ricbot.domain.runtime.ModelInvocation;
import ricbot.domain.runtime.RunState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Root-scoped token, cost, tool-call and active-time ledger for one SQLite transaction. */
final class SqliteRuntimeBudgetLedger {
    void reserveModel(Connection connection, ModelInvocation invocation, RunState run) throws SQLException {
        OptionalLong estimatedCost = estimatedModelCost(run, invocation.reservedTokens());
        reserve(connection, "model:" + invocation.invocationId(), run, "MODEL",
                invocation.reservedTokens(), estimatedCost.orElse(0L), 0L, 0L,
                estimatedCost.isPresent(), invocation.updatedAt());
    }

    void settleModel(Connection connection, ModelInvocation invocation, RunState run) throws SQLException {
        if (invocation.status() == ModelInvocation.Status.PREPARED
                || invocation.status() == ModelInvocation.Status.DISPATCHING) return;
        String reservationId = "model:" + invocation.invocationId();
        BudgetRow row = row(connection, reservationId).orElseThrow(() ->
                new IllegalStateException("model budget reservation missing: " + reservationId));
        String status;
        long tokens = 0L;
        long cost = 0L;
        boolean costKnown = row.costKnown();
        if (invocation.status() == ModelInvocation.Status.OBSERVED) {
            tokens = observedTokens(invocation);
            if (tokens == 0L) tokens = invocation.reservedTokens();
            OptionalLong actualCost = actualModelCost(run, invocation);
            cost = actualCost.orElse(row.reservedCostMicrousd());
            costKnown = actualCost.isPresent() || row.costKnown();
            status = "SETTLED";
        } else if (invocation.status() == ModelInvocation.Status.UNKNOWN
                || invocation.possibleDuplicateCharge()) {
            status = "RETAINED";
        } else {
            status = "RELEASED";
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE budget_reservations SET status=?,consumed_tokens=?,consumed_cost_microusd=?,
                  cost_known=?,updated_at=? WHERE reservation_id=?
                """)) {
            statement.setString(1, status); statement.setLong(2, tokens); statement.setLong(3, cost);
            statement.setInt(4, costKnown ? 1 : 0); statement.setString(5, invocation.updatedAt().toString());
            statement.setString(6, reservationId); statement.executeUpdate();
        }
    }

    void reserveToolCall(Connection connection, RunState run, String reservationId, Instant now)
            throws SQLException {
        reserve(connection, required(reservationId), run, "TOOL", 0L, 0L, 1L, 0L, true, instant(now));
    }

    void reserveActiveTime(Connection connection, RunState run, String reservationId,
                           long millis, Instant now) throws SQLException {
        if (millis <= 0L) throw new IllegalArgumentException("active-time reservation must be positive");
        String id = required(reservationId);
        BudgetRow current = row(connection, id).orElseThrow(() ->
                new IllegalArgumentException("budget reservation not found: " + id));
        if (!current.runId().equals(run.spec().runId()) || !"ACTIVE".equals(current.status())) {
            throw new IllegalStateException("budget reservation is not active: " + id);
        }
        long nextActive = saturatedAdd(current.reservedActiveMillis(), millis);
        enforceBudgets(connection, run, id, current.reservedTokens(), current.reservedCostMicrousd(),
                current.reservedToolCalls(), nextActive, current.costKnown());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE budget_reservations SET reserved_active_millis=?,updated_at=?
                WHERE reservation_id=? AND status='ACTIVE'
                """)) {
            statement.setLong(1, nextActive); statement.setString(2, instant(now).toString());
            statement.setString(3, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("budget reservation conflict: " + id);
            }
        }
    }

    void settleActiveTime(Connection connection, String reservationId, long actualMillis, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE budget_reservations SET consumed_active_millis=?,updated_at=?
                WHERE reservation_id=? AND status IN ('ACTIVE','RETAINED')
                """)) {
            statement.setLong(1, Math.max(0L, actualMillis));
            statement.setString(2, instant(now).toString()); statement.setString(3, required(reservationId));
            statement.executeUpdate();
        }
    }

    void settleToolCall(Connection connection, String reservationId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE budget_reservations SET status='SETTLED',consumed_tool_calls=1,updated_at=?
                WHERE reservation_id=? AND kind='TOOL' AND status IN ('ACTIVE','RETAINED')
                """)) {
            statement.setString(1, instant(now).toString()); statement.setString(2, required(reservationId));
            statement.executeUpdate();
        }
    }

    BudgetUsage usage(Connection connection, String rootRunId) throws SQLException {
        return aggregate(connection, "root_run_id", required(rootRunId), "");
    }

    private void reserve(Connection connection, String reservationId, RunState run, String kind,
                         long tokens, long costMicrousd, long toolCalls, long activeMillis,
                         boolean costKnown, Instant now) throws SQLException {
        Optional<BudgetRow> found = row(connection, reservationId);
        if (found.isPresent()) {
            BudgetRow existing = found.get();
            if (!existing.runId().equals(run.spec().runId()) || !existing.kind().equals(kind)) {
                throw new IllegalStateException("budget reservation identity conflict: " + reservationId);
            }
            return;
        }
        enforceBudgets(connection, run, "", tokens, costMicrousd, toolCalls, activeMillis, costKnown);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO budget_reservations(reservation_id,root_run_id,run_id,kind,status,
                  reserved_tokens,reserved_cost_microusd,reserved_tool_calls,reserved_active_millis,
                  consumed_tokens,consumed_cost_microusd,consumed_tool_calls,consumed_active_millis,
                  cost_known,updated_at)
                VALUES(?,?,?,?,'ACTIVE',?,?,?,?,0,0,0,0,?,?)
                """)) {
            statement.setString(1, reservationId); statement.setString(2, run.spec().rootRunId());
            statement.setString(3, run.spec().runId()); statement.setString(4, kind);
            statement.setLong(5, Math.max(0L, tokens)); statement.setLong(6, Math.max(0L, costMicrousd));
            statement.setLong(7, Math.max(0L, toolCalls)); statement.setLong(8, Math.max(0L, activeMillis));
            statement.setInt(9, costKnown ? 1 : 0); statement.setString(10, now.toString());
            statement.executeUpdate();
        }
    }

    private void enforceBudgets(Connection connection, RunState run, String excludedReservationId,
                                long tokens, long costMicrousd, long toolCalls, long activeMillis,
                                boolean costKnown) throws SQLException {
        enforceBudget(connection, run, "run_id", run.spec().runId(), policy(run, "budgetPolicy"),
                excludedReservationId, tokens, costMicrousd, toolCalls, activeMillis, costKnown);
        enforceBudget(connection, run, "root_run_id", run.spec().rootRunId(), policy(run, "rootBudgetPolicy"),
                excludedReservationId, tokens, costMicrousd, toolCalls, activeMillis, costKnown);
    }

    private void enforceBudget(Connection connection, RunState run, String scopeColumn, String scopeId,
                               Map<String, Object> policy, String excludedReservationId,
                               long tokens, long costMicrousd, long toolCalls, long activeMillis,
                               boolean costKnown) throws SQLException {
        BudgetUsage used = aggregate(connection, scopeColumn, scopeId, excludedReservationId);
        Long tokenLimit = nullablePositiveLong(policy.get("maxTotalTokens"));
        long finalization = nonNegativeLong(policy.get("finalizationTokens"), 0L);
        if (tokenLimit != null && saturatedAdd(used.tokens(), tokens) > Math.max(0L, tokenLimit - finalization)) {
            reject("token_budget", scopeId, tokenLimit, used.tokens(), tokens);
        }
        Long costLimit = nullablePositiveLong(policy.get("maxCostMicrousd"));
        if (costLimit != null && !costKnown) {
            throw new IllegalStateException("budget reservation rejected: cost_budget_unpriced (scope="
                    + scopeId + ", run=" + run.spec().runId() + ")");
        }
        if (costLimit != null && saturatedAdd(used.costMicrousd(), costMicrousd) > costLimit) {
            reject("cost_budget", scopeId, costLimit, used.costMicrousd(), costMicrousd);
        }
        Long toolLimit = nullablePositiveLong(policy.get("maxToolCalls"));
        if (toolLimit != null && saturatedAdd(used.toolCalls(), toolCalls) > toolLimit) {
            reject("tool_call_budget", scopeId, toolLimit, used.toolCalls(), toolCalls);
        }
        Long activeSeconds = nullablePositiveLong(policy.get("maxActiveSeconds"));
        long activeLimit = activeSeconds != null ? saturatedMultiply(activeSeconds, 1_000L) : Long.MAX_VALUE;
        if (activeSeconds != null && saturatedAdd(used.activeMillis(), activeMillis) > activeLimit) {
            reject("active_time_budget", scopeId, activeLimit, used.activeMillis(), activeMillis);
        }
    }

    private static void reject(String dimension, String scopeId, long maximum,
                               long used, long requested) {
        throw new IllegalStateException("budget reservation rejected: " + dimension + " (scope=" + scopeId
                + ", maximum=" + maximum + ", used=" + used + ", requested=" + requested + ")");
    }

    private BudgetUsage aggregate(Connection connection, String scopeColumn, String scopeId,
                                  String excludedReservationId) throws SQLException {
        if (!"run_id".equals(scopeColumn) && !"root_run_id".equals(scopeColumn)) {
            throw new IllegalArgumentException("invalid budget scope");
        }
        String sql = "SELECT "
                + "COALESCE(SUM(CASE WHEN status IN ('ACTIVE','RETAINED') THEN reserved_tokens ELSE consumed_tokens END),0),"
                + "COALESCE(SUM(CASE WHEN status IN ('ACTIVE','RETAINED') THEN reserved_cost_microusd ELSE consumed_cost_microusd END),0),"
                + "COALESCE(SUM(CASE WHEN status IN ('ACTIVE','RETAINED') THEN reserved_tool_calls ELSE consumed_tool_calls END),0),"
                + "COALESCE(SUM(CASE WHEN status IN ('ACTIVE','RETAINED') THEN reserved_active_millis ELSE consumed_active_millis END),0) "
                + "FROM budget_reservations WHERE " + scopeColumn + "=? AND reservation_id<>?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeId);
            statement.setString(2, excludedReservationId != null ? excludedReservationId : "");
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new BudgetUsage(result.getLong(1), result.getLong(2),
                        result.getLong(3), result.getLong(4)) : new BudgetUsage(0, 0, 0, 0);
            }
        }
    }

    private Optional<BudgetRow> row(Connection connection, String reservationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reservation_id,root_run_id,run_id,kind,status,reserved_tokens,
                  reserved_cost_microusd,reserved_tool_calls,reserved_active_millis,cost_known
                FROM budget_reservations WHERE reservation_id=?
                """)) {
            statement.setString(1, reservationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new BudgetRow(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getLong(6), result.getLong(7),
                        result.getLong(8), result.getLong(9), result.getInt(10) != 0));
            }
        }
    }

    private static long observedTokens(ModelInvocation invocation) {
        Object raw = invocation.response().get("usage");
        return raw instanceof Map<?, ?> usage
                ? firstNonNegative(usage.get("total_tokens"), usage.get("totalTokens")) : 0L;
    }

    private static OptionalLong estimatedModelCost(RunState run, long tokens) {
        Map<String, Object> pricing = pricing(run);
        long unit = nonNegativeLong(pricing.get("unitTokens"), 0L);
        BigDecimal rate = maxPrice(pricing);
        return unit <= 0L || rate == null ? OptionalLong.empty()
                : OptionalLong.of(microUsd(tokens, rate, unit));
    }

    private static OptionalLong actualModelCost(RunState run, ModelInvocation invocation) {
        Map<String, Object> pricing = pricing(run);
        long unit = nonNegativeLong(pricing.get("unitTokens"), 0L);
        BigDecimal inputRate = decimal(pricing.get("inputUsd"));
        BigDecimal outputRate = decimal(pricing.get("outputUsd"));
        if (unit <= 0L || inputRate == null || outputRate == null) return OptionalLong.empty();
        Object raw = invocation.response().get("usage");
        if (!(raw instanceof Map<?, ?> usage)) return estimatedModelCost(run, invocation.reservedTokens());
        long input = firstNonNegative(usage.get("prompt_tokens"), usage.get("input_tokens"));
        long output = firstNonNegative(usage.get("completion_tokens"), usage.get("output_tokens"));
        return OptionalLong.of(saturatedAdd(microUsd(input, inputRate, unit),
                microUsd(output, outputRate, unit)));
    }

    private static Map<String, Object> pricing(RunState run) {
        Object raw = run.spec().metadata().get("modelPricing");
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, item) -> value.put(String.valueOf(key), item));
        return Map.copyOf(value);
    }

    private static Map<String, Object> policy(RunState run, String key) {
        Object raw = run.spec().metadata().get(key);
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((name, item) -> value.put(String.valueOf(name), item));
        return Map.copyOf(value);
    }

    private static BigDecimal maxPrice(Map<String, Object> pricing) {
        BigDecimal result = null;
        for (String key : List.of("inputUsd", "outputUsd", "cachedInputUsd")) {
            BigDecimal value = decimal(pricing.get(key));
            if (value != null && (result == null || value.compareTo(result) > 0)) result = value;
        }
        return result;
    }

    private static BigDecimal decimal(Object value) {
        try {
            String text = value != null ? String.valueOf(value).trim() : "";
            return text.isBlank() ? null : new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static long microUsd(long tokens, BigDecimal rate, long unit) {
        try {
            return BigDecimal.valueOf(Math.max(0L, tokens)).multiply(rate)
                    .multiply(BigDecimal.valueOf(1_000_000L))
                    .divide(BigDecimal.valueOf(unit), 0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Long nullablePositiveLong(Object value) {
        return value instanceof Number number && number.longValue() > 0L ? number.longValue() : null;
    }
    private static long nonNegativeLong(Object value, long fallback) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : fallback;
    }
    private static long firstNonNegative(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) return Math.max(0L, number.longValue());
        }
        return 0L;
    }
    private static long saturatedAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    private static long saturatedMultiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    private static Instant instant(Instant value) { return value != null ? value : Instant.now(); }
    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("reservationId is required");
        return clean;
    }

    private record BudgetRow(String reservationId, String rootRunId, String runId, String kind,
                             String status, long reservedTokens, long reservedCostMicrousd,
                             long reservedToolCalls, long reservedActiveMillis, boolean costKnown) { }
}
