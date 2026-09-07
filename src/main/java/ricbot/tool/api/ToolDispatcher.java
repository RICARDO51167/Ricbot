package ricbot.tool.api;

import ricbot.domain.runtime.dto.RuntimeDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** The only production execution boundary: resolve, validate, authorize, lock, execute. */
public final class ToolDispatcher {
    @FunctionalInterface public interface AuthorizationPolicy {
        ToolAuthorizationDecision decide(ToolDescriptor descriptor, ToolRiskEvidence risk,
                                         ToolInvocation invocation, ToolExecutionContext context);
    }
    public record Prepared(Tool tool, ToolInvocation invocation, ToolAuthorizationDecision authorization,
                           List<String> resourceKeys, String invocationDigest) { }

    private static final ConcurrentHashMap<String, Semaphore> READ_LIMITS = new ConcurrentHashMap<>();
    private final ToolCatalog catalog;
    private final ToolResolver resolver;
    private final AuthorizationPolicy policy;

    public ToolDispatcher(ToolCatalog catalog, AuthorizationPolicy policy) {
        this.catalog = catalog;
        this.resolver = new ToolResolver(catalog);
        this.policy = policy != null ? policy : ToolDispatcher::defaultPolicy;
    }

    public Prepared prepare(ToolInvocation invocation, ToolExecutionContext base) {
        return prepare(invocation, base, catalog.exposure().activeGroups());
    }

    public Prepared prepare(ToolInvocation invocation, ToolExecutionContext base, Set<ToolGroup> activeGroups) {
        Tool tool = catalog.get(invocation.toolName());
        if (tool == null && catalog.get(invocation.toolName()) == null) {
            throw new ToolDispatchException("TOOL_NOT_FOUND", "Tool '" + invocation.toolName() + "' not found", false);
        }
        Set<ToolGroup> visibleGroups = activeGroups != null ? Set.copyOf(activeGroups)
                : catalog.exposure().activeGroups();
        if (!visibleGroups.contains(tool.descriptor().group())) {
            throw new ToolDispatchException("TOOL_NOT_VISIBLE", "Tool '" + invocation.toolName() + "' is not active for this run", false);
        }
        ToolDescriptor descriptor = tool.descriptor();
        List<String> schemaErrors = JsonSchemaValidator.validate(descriptor.parameters(), invocation.arguments());
        if (!schemaErrors.isEmpty()) throw new ToolDispatchException("INVALID_ARGUMENTS", String.join("; ", schemaErrors), false);
        if (!descriptor.effectPolicy().declared()) throw new ToolDispatchException("UNDECLARED_EFFECT", "tool effect is undeclared", true);
        ToolRiskEvidence risk = tool.assessRisk(invocation, base);
        if (risk.decision() == ToolRiskEvidence.Decision.DENY && risk.irreversible()) {
            throw new ToolDispatchException("SAFETY_DENY", risk.reason(), true);
        }
        ToolAuthorizationDecision authorization = policy.decide(descriptor, risk, invocation, base);
        if (authorization.decision() == ToolAuthorizationDecision.Decision.DENY) {
            throw new ToolDispatchException("AUTHORIZATION_DENIED", authorization.reason(), authorization.safetyDeny());
        }
        List<String> keys = tool.resourceKeys(invocation, base).stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
        if (keys.isEmpty() && !descriptor.effectPolicy().concurrentSafe()) {
            keys = List.of("run:" + base.runId() + ":serial");
        }
        return new Prepared(tool, invocation, authorization, keys,
                RuntimeDigest.sha256(Map.of("tool", invocation.toolName(), "arguments", invocation.arguments())));
    }

    public ToolResult execute(Prepared prepared, ToolExecutionContext base, ToolChunkSink chunks) throws Exception {
        if (prepared.authorization().decision() != ToolAuthorizationDecision.Decision.ALLOW) {
            throw new ToolDispatchException("APPROVAL_REQUIRED", prepared.authorization().reason(), false);
        }
        if (base.isCancelled()) {
            throw new ToolDispatchException("CANCELLED", "run was cancelled before tool dispatch", false);
        }
        Semaphore readPermit = null;
        try {
            if (prepared.resourceKeys().isEmpty() && prepared.tool().descriptor().effectPolicy().readOnly()) {
                Object configured = base.backendCapabilities().get("maxParallelReadCalls");
                int limit = configured instanceof Number number ? Math.max(1, number.intValue()) : 4;
                readPermit = READ_LIMITS.computeIfAbsent(base.runId(), ignored -> new Semaphore(limit, true));
                readPermit.acquire();
                if (base.isCancelled()) {
                    throw new ToolDispatchException("CANCELLED", "run was cancelled before tool execution", false);
                }
            }
            ToolExecutionContext authorized = new ToolExecutionContext(base.runId(), base.sessionId(), base.taskId(),
                    base.activationId(), base.workspaceId(), base.workspace(), base.role(), prepared.authorization(),
                    base.backendCapabilities(), base.fileReadReceipts(), base.cancelled());
            if (prepared.tool().descriptor().executionMode() == ExecutionMode.EXTERNAL) {
                String actionId = "ext-" + prepared.invocationDigest().substring(0, 20);
                return new ToolResult.ExternalPending(actionId, prepared.invocationDigest(),
                        Map.of("tool", prepared.invocation().toolName(), "arguments", prepared.invocation().arguments()), List.of());
            }
            return prepared.tool().execute(prepared.invocation(), authorized,
                    chunks != null ? chunks : ToolChunkSink.discard());
        } finally {
            if (readPermit != null) readPermit.release();
        }
    }

    private static ToolAuthorizationDecision defaultPolicy(ToolDescriptor descriptor, ToolRiskEvidence risk,
                                                            ToolInvocation invocation, ToolExecutionContext context) {
        if (risk.decision() == ToolRiskEvidence.Decision.DENY) return ToolAuthorizationDecision.deny(risk.reason(), risk.irreversible());
        if (descriptor.effectPolicy().approval() == ToolEffectPolicy.Approval.ALWAYS
                || risk.decision() == ToolRiskEvidence.Decision.REQUIRE_APPROVAL) {
            return new ToolAuthorizationDecision(ToolAuthorizationDecision.Decision.REQUIRE_APPROVAL,
                    !risk.reason().isBlank() ? risk.reason() : "tool policy requires approval", List.of("effect", "risk"), false);
        }
        return new ToolAuthorizationDecision(ToolAuthorizationDecision.Decision.ALLOW,
                "visible role-authorized tool", List.of("exposure", "schema", "effect", "risk"), false);
    }

    public static final class ToolDispatchException extends IllegalStateException {
        private final String code; private final boolean safetyDeny;
        public ToolDispatchException(String code, String message, boolean safetyDeny) { super(message); this.code = code; this.safetyDeny = safetyDeny; }
        public String code() { return code; } public boolean safetyDeny() { return safetyDeny; }
    }
}
