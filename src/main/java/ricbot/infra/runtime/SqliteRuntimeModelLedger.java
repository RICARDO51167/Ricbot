package ricbot.infra.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.ModelInvocation;
import ricbot.domain.runtime.RunState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/** Model invocation ledger operations scoped to the caller's SQLite transaction. */
final class SqliteRuntimeModelLedger {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    Optional<ModelInvocation> find(Connection connection, String invocationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invocation_json FROM model_invocations WHERE invocation_id=?")) {
            statement.setString(1, required(invocationId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result.getString(1))) : Optional.empty();
            }
        }
    }

    ModelInvocation save(Connection connection, ModelInvocation invocation, RunState run,
                         SqliteRuntimeBudgetLedger budgets, long commitSequence,
                         SqliteRuntimeEventAppender events) throws SQLException {
        ModelInvocation previous = find(connection, invocation.invocationId()).orElse(null);
        validateTransition(previous, invocation);
        if (previous == null && invocation.status() == ModelInvocation.Status.PREPARED) {
            budgets.reserveModel(connection, invocation, run);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO model_invocations(invocation_id, run_id, activation_id, attempt, status,
                  request_digest, provider_request_id, invocation_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(invocation_id) DO UPDATE SET status=excluded.status,
                  provider_request_id=excluded.provider_request_id, invocation_json=excluded.invocation_json,
                  updated_at=excluded.updated_at
                """)) {
            statement.setString(1, invocation.invocationId()); statement.setString(2, invocation.runId());
            statement.setString(3, invocation.activationId()); statement.setInt(4, invocation.attempt());
            statement.setString(5, invocation.status().name()); statement.setString(6, invocation.requestDigest());
            statement.setString(7, invocation.providerRequestId()); statement.setString(8, json(invocation));
            statement.setString(9, invocation.updatedAt().toString()); statement.executeUpdate();
        }
        budgets.settleModel(connection, invocation, run);
        events.append(invocation.runId(), commitSequence, "MODEL_INVOCATION_" + invocation.status().name(),
                invocation.updatedAt(), Map.of("invocationId", invocation.invocationId(),
                        "attempt", invocation.attempt(), "reservedTokens", invocation.reservedTokens(),
                        "providerRequestId", invocation.providerRequestId(),
                        "possibleDuplicateCharge", invocation.possibleDuplicateCharge()));
        return invocation;
    }

    private static void validateTransition(ModelInvocation previous, ModelInvocation next) {
        if (previous == null) {
            if (next.status() != ModelInvocation.Status.PREPARED) {
                throw new IllegalStateException("model invocation must start PREPARED");
            }
            return;
        }
        if (!previous.runId().equals(next.runId()) || !previous.activationId().equals(next.activationId())
                || previous.attempt() != next.attempt() || !previous.requestDigest().equals(next.requestDigest())
                || previous.reservedTokens() != next.reservedTokens()
                || previous.unknownPolicy() != next.unknownPolicy()) {
            throw new IllegalStateException("model invocation identity is immutable");
        }
        boolean valid = switch (previous.status()) {
            case PREPARED -> next.status() == ModelInvocation.Status.DISPATCHING
                    || next.status() == ModelInvocation.Status.FAILED;
            case DISPATCHING -> next.status() == ModelInvocation.Status.OBSERVED
                    || next.status() == ModelInvocation.Status.FAILED
                    || next.status() == ModelInvocation.Status.UNKNOWN;
            case UNKNOWN -> next.status() == ModelInvocation.Status.OBSERVED
                    || next.status() == ModelInvocation.Status.FAILED;
            case OBSERVED, FAILED -> next.status() == previous.status();
        };
        if (!valid) throw new IllegalStateException(
                "invalid model invocation transition: " + previous.status() + " -> " + next.status());
    }

    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("invocationId is required");
        return clean;
    }
    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode model invocation", failure); }
    }
    private static ModelInvocation read(String value) {
        try { return MAPPER.readValue(value, ModelInvocation.class); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode model invocation", failure); }
    }
}
