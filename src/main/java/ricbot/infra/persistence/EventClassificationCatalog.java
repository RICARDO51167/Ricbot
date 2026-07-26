package ricbot.infra.persistence;

import ricbot.domain.trace.TraceEventType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Migration catalog that prevents diagnostic and projection data becoming a source of truth. */
public final class EventClassificationCatalog {
    public static final String TRACE = "trace";
    public static final String TEAM = "team";
    public static final String STEP_AUDIT = "step_audit";
    public static final String EVIDENCE = "evidence";

    private static final Map<String, Classification> CATALOG = build();

    private EventClassificationCatalog() {
    }

    public static Classification classification(String namespace, String type) {
        String key = key(namespace, type);
        Classification value = CATALOG.get(key);
        if (value == null) throw new IllegalArgumentException("unclassified event data: " + key);
        return value;
    }

    public static Map<String, Classification> entries() {
        return CATALOG;
    }

    private static Map<String, Classification> build() {
        Map<String, Classification> values = new LinkedHashMap<>();

        diagnostic(values, TRACE,
                TraceEventType.SESSION_STARTED,
                TraceEventType.CONTEXT_BUILT,
                TraceEventType.WORKER_STARTED,
                TraceEventType.WORKER_FINISHED,
                TraceEventType.WORKER_FAILED,
                TraceEventType.VERIFIER_STARTED,
                TraceEventType.VERIFIER_FINISHED,
                TraceEventType.STEP_AUDIT_RECORDED,
                TraceEventType.STEP_AUDIT_COMPACTED,
                TraceEventType.STEP_AUDIT_LINKED,
                TraceEventType.TASK_SUMMARY_CREATED,
                TraceEventType.TEAM_EVENT);
        durable(values, TRACE,
                TraceEventType.APPROVAL_REQUESTED,
                TraceEventType.APPROVAL_APPROVED,
                TraceEventType.APPROVAL_REJECTED,
                TraceEventType.SIDE_EFFECT_RESERVED,
                TraceEventType.SIDE_EFFECT_REUSED,
                TraceEventType.SIDE_EFFECT_RETRY_AUTHORIZED,
                TraceEventType.SIDE_EFFECT_SUCCEEDED,
                TraceEventType.SIDE_EFFECT_FAILED,
                TraceEventType.SIDE_EFFECT_COMPENSATED,
                TraceEventType.CHANGESET_CREATED,
                TraceEventType.CHANGESET_APPROVED,
                TraceEventType.CHANGESET_COMMIT_REQUESTED,
                TraceEventType.CHANGESET_COMMITTED,
                TraceEventType.CHANGESET_ROLLBACK_REQUESTED,
                TraceEventType.CHANGESET_ROLLED_BACK,
                TraceEventType.WORKSPACE_CREATED,
                TraceEventType.WORKSPACE_SELECTED,
                TraceEventType.WORKSPACE_CLEANED,
                TraceEventType.CHANGESET_CREATED_FROM_WORKSPACE,
                TraceEventType.POLICY_EVALUATED,
                TraceEventType.POLICY_DENIED,
                TraceEventType.POLICY_APPROVAL_REQUIRED,
                TraceEventType.DEVELOPER_PLAN_CREATED,
                TraceEventType.DEVELOPER_TOOL_APPROVAL_REQUIRED,
                TraceEventType.DEVELOPER_TOOL_APPLIED,
                TraceEventType.WORKSPACE_DIFF_REQUIRES_CHANGESET,
                TraceEventType.IMPLEMENTATION_STEP_CREATED,
                TraceEventType.IMPLEMENTATION_STEP_APPLIED,
                TraceEventType.IMPLEMENTATION_STEP_REJECTED,
                TraceEventType.IMPLEMENTATION_STEP_APPROVAL_REQUIRED,
                TraceEventType.IMPLEMENTATION_STEP_FAILED,
                TraceEventType.IMPLEMENTATION_STEP_BLOCKED,
                TraceEventType.IMPLEMENTATION_STEP_GATE_CHECKED,
                TraceEventType.IMPLEMENTATION_STEP_UPDATED,
                TraceEventType.IMPLEMENTATION_STEP_READY,
                TraceEventType.IMPLEMENTATION_STEP_VALIDATION_FAILED);
        artifact(values, TRACE,
                TraceEventType.DIFF_REVIEWED,
                TraceEventType.VERIFICATION_RESULT,
                TraceEventType.WORKSPACE_DIFFED,
                TraceEventType.EVAL_RESULT);

        put(values, EVIDENCE, "DiffEvidence", Classification.IMMUTABLE_ARTIFACT);
        put(values, EVIDENCE, "ExecutedTestEvidence", Classification.IMMUTABLE_ARTIFACT);
        put(values, EVIDENCE, "ApprovalEvidence", Classification.IMMUTABLE_ARTIFACT);
        put(values, EVIDENCE, "VerificationEvidence", Classification.IMMUTABLE_ARTIFACT);

        return Collections.unmodifiableMap(values);
    }

    private static void durable(Map<String, Classification> values, String namespace, Enum<?>... types) {
        putAll(values, namespace, Classification.DURABLE_FACT, types);
    }

    private static void artifact(Map<String, Classification> values, String namespace, Enum<?>... types) {
        putAll(values, namespace, Classification.IMMUTABLE_ARTIFACT, types);
    }

    private static void diagnostic(Map<String, Classification> values, String namespace, Enum<?>... types) {
        putAll(values, namespace, Classification.DIAGNOSTIC, types);
    }

    private static void putAll(
            Map<String, Classification> values,
            String namespace,
            Classification classification,
            Enum<?>... types
    ) {
        for (Enum<?> type : types) put(values, namespace, type.name(), classification);
    }

    private static void put(
            Map<String, Classification> values,
            String namespace,
            String type,
            Classification classification
    ) {
        String key = key(namespace, type);
        if (values.putIfAbsent(key, classification) != null) {
            throw new IllegalStateException("duplicate event classification: " + key);
        }
    }

    private static String key(String namespace, String type) {
        String group = namespace != null ? namespace.trim().toLowerCase(java.util.Locale.ROOT) : "";
        String name = type != null ? type.trim() : "";
        if (group.isBlank() || name.isBlank()) throw new IllegalArgumentException("namespace and type are required");
        return group + ":" + name;
    }

    public enum Classification {
        DURABLE_FACT,
        IMMUTABLE_ARTIFACT,
        READ_MODEL,
        DIAGNOSTIC
    }
}
