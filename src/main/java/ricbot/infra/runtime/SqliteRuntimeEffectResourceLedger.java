package ricbot.infra.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.EffectRecord;
import ricbot.domain.runtime.ExternalEvent;
import ricbot.domain.runtime.RunState;
import ricbot.domain.runtime.WaitReason;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Effect ledger and resource-lease operations scoped to one SQLite transaction. */
final class SqliteRuntimeEffectResourceLedger {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    Optional<EffectRecord> find(Connection connection, String effectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT effect_json FROM effects WHERE effect_id=?")) {
            statement.setString(1, required(effectId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result.getString(1))) : Optional.empty();
            }
        }
    }

    EffectRecord save(Connection connection, EffectRecord effect, long commitSequence,
                      SqliteRuntimeEventAppender events) throws SQLException {
        EffectRecord previous = find(connection, effect.intent().effectId()).orElse(null);
        validateTransition(previous, effect);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO effects(effect_id, run_id, activation_id, status, idempotency_key,
                  committed_superstep, effect_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(effect_id) DO UPDATE SET status=excluded.status,
                  committed_superstep=excluded.committed_superstep, effect_json=excluded.effect_json,
                  updated_at=excluded.updated_at
                """)) {
            statement.setString(1, effect.intent().effectId()); statement.setString(2, effect.intent().runId());
            statement.setString(3, effect.intent().activationId()); statement.setString(4, effect.status().name());
            statement.setString(5, effect.intent().idempotencyKey());
            statement.setLong(6, effect.committedSuperstep()); statement.setString(7, json(effect));
            statement.setString(8, effect.updatedAt().toString()); statement.executeUpdate();
        }
        events.append(effect.intent().runId(), commitSequence, "EFFECT_" + effect.status().name(),
                effect.updatedAt(), Map.of("effectId", effect.intent().effectId(),
                        "tool", effect.intent().tool()));
        return effect;
    }

    boolean acquire(Connection connection, String effectId, String owner, List<String> resources,
                    Instant now, Duration leaseDuration) throws SQLException {
        List<String> ordered = normalize(resources);
        for (String resource : ordered) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM resource_leases WHERE resource=? AND expires_at < ?")) {
                delete.setString(1, resource); delete.setString(2, now.toString()); delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO resource_leases(resource, effect_id, owner, expires_at, version)
                    VALUES (?, ?, ?, ?, 0) ON CONFLICT(resource) DO NOTHING
                    """)) {
                insert.setString(1, resource); insert.setString(2, effectId); insert.setString(3, owner);
                insert.setString(4, now.plus(leaseDuration).toString());
                if (insert.executeUpdate() != 1) {
                    release(connection, effectId, owner);
                    return false;
                }
            }
        }
        return true;
    }

    boolean renew(Connection connection, String effectId, String owner, List<String> resources,
                  Instant now, Duration leaseDuration) throws SQLException {
        List<String> ordered = normalize(resources);
        if (ordered.isEmpty()) return true;
        int owned;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM resource_leases
                WHERE effect_id=? AND owner=? AND expires_at>=?
                """)) {
            query.setString(1, effectId); query.setString(2, owner); query.setString(3, now.toString());
            try (ResultSet result = query.executeQuery()) { owned = result.next() ? result.getInt(1) : 0; }
        }
        if (owned != ordered.size()) return false;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE resource_leases SET expires_at=?,version=version+1
                WHERE effect_id=? AND owner=? AND expires_at>=?
                """)) {
            update.setString(1, now.plus(leaseDuration).toString()); update.setString(2, effectId);
            update.setString(3, owner); update.setString(4, now.toString());
            return update.executeUpdate() == ordered.size();
        }
    }

    void release(Connection connection, String effectId, String owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM resource_leases WHERE effect_id=? AND owner=?")) {
            statement.setString(1, effectId); statement.setString(2, owner); statement.executeUpdate();
        }
    }

    List<EffectRecord> unresolved(Connection connection, String runId) throws SQLException {
        List<EffectRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT effect_json FROM effects
                WHERE run_id=? AND status IN ('DISPATCHING','UNKNOWN') ORDER BY effect_id
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(read(result.getString(1)));
            }
        }
        return List.copyOf(records);
    }

    void confirm(Connection connection, String runId, long commitSequence,
                 ExternalEvent.EffectConfirmation confirmation,
                 SqliteRuntimeEventAppender events) throws SQLException {
        String effectId = String.valueOf(confirmation.payload().getOrDefault("effectId", ""));
        EffectRecord current = find(connection, effectId).orElseThrow(() ->
                new IllegalArgumentException("effect not found: " + effectId));
        if (!runId.equals(current.intent().runId()) || current.status() != EffectRecord.Status.UNKNOWN) {
            throw new IllegalStateException("effect confirmation does not match an UNKNOWN effect");
        }
        String outcome = String.valueOf(confirmation.payload().getOrDefault("outcome", ""))
                .toUpperCase(Locale.ROOT);
        EffectRecord.Status status = switch (outcome) {
            case "SUCCEEDED" -> EffectRecord.Status.SUCCEEDED;
            case "FAILED" -> EffectRecord.Status.FAILED;
            default -> throw new IllegalArgumentException(
                    "effect confirmation outcome must be SUCCEEDED or FAILED");
        };
        EffectRecord confirmed = new EffectRecord(current.intent(), status, current.attempt(),
                current.committedSuperstep(), confirmation.payload(),
                String.valueOf(confirmation.payload().getOrDefault("resultReference", "")),
                status == EffectRecord.Status.FAILED ? "externally confirmed failure" : "",
                confirmation.occurredAt());
        validateTransition(current, confirmed);
        updateConfirmed(connection, effectId, "UNKNOWN", confirmed);
        events.append(runId, commitSequence, "EFFECT_" + status.name(), confirmation.occurredAt(),
                Map.of("effectId", effectId, "confirmed", true));
    }

    void confirmExternal(Connection connection, RunState current,
                         ExternalEvent.ExternalActionResult result,
                         SqliteRuntimeEventAppender events) throws SQLException {
        if (!(current.waitReason() instanceof WaitReason.ExternalEventWait wait)
                || !"ExternalActionResult".equals(wait.eventType())) return;
        String effectId = String.valueOf(wait.detail().getOrDefault("effectId",
                result.payload().getOrDefault("effectId", "")));
        if (effectId.isBlank()) return;
        EffectRecord existing = find(connection, effectId).orElseThrow(() ->
                new IllegalStateException("external action effect not found: " + effectId));
        if (existing.status() == EffectRecord.Status.SUCCEEDED
                || existing.status() == EffectRecord.Status.FAILED) return;
        if (existing.status() != EffectRecord.Status.DISPATCHING) {
            throw new IllegalStateException("external action effect is not pending: " + effectId);
        }
        String outcome = String.valueOf(result.payload().getOrDefault("outcome", "SUCCEEDED"))
                .toUpperCase(Locale.ROOT);
        boolean failed = "FAILED".equals(outcome) || Boolean.FALSE.equals(result.payload().get("success"));
        EffectRecord.Status status = failed ? EffectRecord.Status.FAILED : EffectRecord.Status.SUCCEEDED;
        EffectRecord confirmed = new EffectRecord(existing.intent(), status, existing.attempt(),
                existing.committedSuperstep(), result.payload(),
                String.valueOf(result.payload().getOrDefault("resultReference", "")),
                failed ? String.valueOf(result.payload().getOrDefault("error", "external action failed")) : "",
                result.occurredAt());
        validateTransition(existing, confirmed);
        updateConfirmed(connection, effectId, "DISPATCHING", confirmed);
        events.append(current.spec().runId(), current.commitSequence(), "EFFECT_" + status.name(),
                result.occurredAt(), Map.of("effectId", effectId, "external", true));
    }

    private static void updateConfirmed(Connection connection, String effectId, String expected,
                                        EffectRecord confirmed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE effects SET status=?,effect_json=?,updated_at=? WHERE effect_id=? AND status=?
                """)) {
            statement.setString(1, confirmed.status().name()); statement.setString(2, json(confirmed));
            statement.setString(3, confirmed.updatedAt().toString()); statement.setString(4, effectId);
            statement.setString(5, expected);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("effect confirmation conflict: " + effectId);
            }
        }
    }

    private static void validateTransition(EffectRecord previous, EffectRecord next) {
        if (previous == null) {
            if (next.status() != EffectRecord.Status.PREPARED) {
                throw new IllegalStateException("effect must start PREPARED");
            }
            return;
        }
        if (!previous.intent().equals(next.intent()) || previous.attempt() != next.attempt()) {
            throw new IllegalStateException("effect intent and attempt are immutable");
        }
        boolean valid = switch (previous.status()) {
            case PREPARED -> next.status() == EffectRecord.Status.DISPATCHING
                    || next.status() == EffectRecord.Status.FAILED;
            case DISPATCHING -> next.status() == EffectRecord.Status.SUCCEEDED
                    || next.status() == EffectRecord.Status.FAILED
                    || next.status() == EffectRecord.Status.UNKNOWN;
            case UNKNOWN -> next.status() == EffectRecord.Status.SUCCEEDED
                    || next.status() == EffectRecord.Status.FAILED;
            case SUCCEEDED, FAILED -> next.status() == previous.status();
        };
        if (!valid) throw new IllegalStateException(
                "invalid effect transition: " + previous.status() + " -> " + next.status());
    }

    private static List<String> normalize(List<String> resources) {
        return resources != null ? resources.stream().map(String::trim).filter(value -> !value.isBlank())
                .sorted().distinct().toList() : List.of();
    }
    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("effectId is required");
        return clean;
    }
    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode effect", failure); }
    }
    private static EffectRecord read(String value) {
        try { return MAPPER.readValue(value, EffectRecord.class); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode effect", failure); }
    }
}
