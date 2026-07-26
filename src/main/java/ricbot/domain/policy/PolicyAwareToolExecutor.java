package ricbot.domain.policy;

import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.task.TaskRole;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PolicyAwareToolExecutor {
    private final PolicyEngine policyEngine;
    private final ToolRegistry toolRegistry;
    private final ApprovalService approvalService;
    private final TraceStore traceStore;

    public PolicyAwareToolExecutor(
            PolicyEngine policyEngine,
            ToolRegistry toolRegistry,
            ApprovalService approvalService,
            TraceStore traceStore
    ) {
        this.policyEngine = policyEngine != null ? policyEngine : new PolicyEngine(Path.of(".").toAbsolutePath().normalize());
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService != null ? approvalService : new ApprovalService();
        this.traceStore = traceStore;
    }

    public PolicyToolResult execute(
            TaskRole role,
            String toolName,
            Map<String, Object> args,
            WorkspaceSession workspaceSession,
            String sessionId,
            String teamSessionId,
            String taskId
    ) {
        Map<String, Object> routedArgs = copy(args);
        PathRouting routing = routeWorkspaceArgs(role, toolName, routedArgs, workspaceSession);
        if (routing.decision() != null) {
            tracePolicy(routing.decision(), sessionId, teamSessionId, taskId, workspaceSession, "");
            tracePolicyEvent(TraceEventType.POLICY_DENIED, routing.decision(), sessionId, teamSessionId, taskId, workspaceSession, "");
            return new PolicyToolResult(routing.decision(), routedArgs, false, "", "Policy denied before tool execution.", null, routing.decision().suggestedAction());
        }

        PolicyDecision decision = decision(role, toolName, routedArgs, workspaceSession);
        tracePolicy(decision, sessionId, teamSessionId, taskId, workspaceSession, "");
        if (decision.decisionType() == PolicyDecisionType.DENY) {
            tracePolicyEvent(TraceEventType.POLICY_DENIED, decision, sessionId, teamSessionId, taskId, workspaceSession, "");
            return new PolicyToolResult(decision, routedArgs, false, "", "Policy denied before tool execution.", null, decision.suggestedAction());
        }
        if (decision.decisionType() == PolicyDecisionType.REQUIRE_APPROVAL) {
            Map<String, Object> pendingArgs = pendingArgs(routedArgs, role, teamSessionId, taskId, workspaceSession);
            ApprovalRequest request = approvalService.createRequest(
                    riskAssessment(decision, toolName, routedArgs),
                    PendingToolCall.create(null, normalizeTool(toolName), pendingArgs, sessionId, riskAssessment(decision, toolName, routedArgs))
            );
            String requestId = request != null ? request.requestId() : "";
            tracePolicyEvent(TraceEventType.POLICY_APPROVAL_REQUIRED, decision, sessionId, teamSessionId, taskId, workspaceSession, requestId);
            return new PolicyToolResult(decision, routedArgs, false, requestId,
                    "Approval required before tool execution.", null, "run /approve " + requestId);
        }
        if (toolRegistry == null) {
            return new PolicyToolResult(decision, routedArgs, false, "", "ToolRegistry is not available.", null, "register ToolRegistry before execution");
        }
        Object result = toolRegistry.execute(normalizeTool(toolName), routedArgs);
        return new PolicyToolResult(decision, routedArgs, true, "", summarize(result), result, "executed");
    }

    private PolicyDecision decision(TaskRole role, String toolName, Map<String, Object> args, WorkspaceSession workspaceSession) {
        String tool = normalizeTool(toolName);
        if ("exec".equals(tool)) {
            Object command = args != null ? args.get("command") : null;
            if (command == null) {
                command = args != null ? args.get("cmd") : null;
            }
            return policyEngine.evaluateCommand(role, command != null ? String.valueOf(command) : "", workspaceSession);
        }
        return policyEngine.evaluate(role, tool, args, workspaceSession);
    }

    private PathRouting routeWorkspaceArgs(TaskRole role, String toolName, Map<String, Object> args, WorkspaceSession workspaceSession) {
        if (workspaceSession == null || workspaceSession.workspacePath().isBlank() || args == null) {
            return new PathRouting(null);
        }
        String tool = normalizeTool(toolName);
        Path root = Path.of(workspaceSession.workspacePath()).toAbsolutePath().normalize();
        try {
            if (List.of("read_file", "write_file", "edit_file").contains(tool) && args.get("path") != null) {
                args.put("path", routePath(root, String.valueOf(args.get("path"))));
            }
            if (List.of("grep", "glob").contains(tool)) {
                Object baseDir = args.get("base_dir");
                args.put("base_dir", routePath(root, baseDir != null ? String.valueOf(baseDir) : "."));
            }
            return new PathRouting(null);
        } catch (IllegalArgumentException e) {
            PolicyDecision denied = new PolicyDecision(
                    PolicyDecisionType.DENY,
                    role != null ? role : TaskRole.LEADER,
                    tool,
                    List.of("workspace path routing denied: " + e.getMessage()),
                    CommandRiskLevel.BLOCKED,
                    false,
                    true,
                    List.of("workspace:path"),
                    "do not execute"
            );
            return new PathRouting(denied);
        }
    }

    private String routePath(Path root, String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Path path = Path.of(raw);
        Path target = path.isAbsolute() ? path.toAbsolutePath().normalize() : root.resolve(path).toAbsolutePath().normalize();
        if (!target.equals(root) && !target.startsWith(root)) {
            throw new IllegalArgumentException("path escapes active workspace: " + raw);
        }
        return target.toString();
    }

    private RiskAssessment riskAssessment(PolicyDecision decision, String toolName, Map<String, Object> args) {
        String command = "";
        if ("exec".equals(normalizeTool(toolName)) && args != null) {
            Object raw = args.get("command");
            command = raw != null ? String.valueOf(raw) : "";
        }
        String path = "";
        if (args != null && args.get("path") != null) {
            path = String.valueOf(args.get("path"));
        } else if (args != null && args.get("base_dir") != null) {
            path = String.valueOf(args.get("base_dir"));
        }
        return RiskAssessment.of(
                decision != null ? decision.riskLevel() : CommandRiskLevel.MEDIUM,
                decision != null ? decision.reasons() : List.of("policy approval required"),
                command,
                normalizeTool(toolName),
                path.isBlank() ? List.of() : List.of(path)
        );
    }

    private Map<String, Object> pendingArgs(
            Map<String, Object> routedArgs,
            TaskRole role,
            String teamSessionId,
            String taskId,
            WorkspaceSession workspaceSession
    ) {
        Map<String, Object> out = new LinkedHashMap<>(routedArgs != null ? routedArgs : Map.of());
        out.put("__role", role != null ? role.name() : "");
        out.put("__team_session_id", teamSessionId != null ? teamSessionId : "");
        out.put("__task_id", taskId != null ? taskId : "");
        out.put("__workspace_session_id", workspaceSession != null ? workspaceSession.id() : "");
        out.put("__workspace_path", workspaceSession != null ? workspaceSession.workspacePath() : "");
        return out;
    }

    private void tracePolicy(PolicyDecision decision, String sessionId, String teamSessionId, String taskId, WorkspaceSession workspaceSession, String requestId) {
        tracePolicyEvent(TraceEventType.POLICY_EVALUATED, decision, sessionId, teamSessionId, taskId, workspaceSession, requestId);
    }

    private void tracePolicyEvent(
            TraceEventType type,
            PolicyDecision decision,
            String sessionId,
            String teamSessionId,
            String taskId,
            WorkspaceSession workspaceSession,
            String requestId
    ) {
        if (traceStore == null || decision == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", decision.role().name());
            payload.put("toolName", decision.toolName());
            payload.put("decisionType", decision.decisionType().name());
            payload.put("reasons", decision.reasons());
            payload.put("riskLevel", decision.riskLevel().name());
            payload.put("requiresApproval", decision.requiresApproval());
            payload.put("denied", decision.denied());
            payload.put("taskId", taskId != null ? taskId : "");
            payload.put("workspaceSessionId", workspaceSession != null ? workspaceSession.id() : "");
            payload.put("workspacePath", workspaceSession != null ? workspaceSession.workspacePath() : "");
            traceStore.append(new TraceEvent(
                    traceStore.traceIdForSession(sessionId),
                    null,
                    "",
                    sessionId,
                    teamSessionId,
                    "",
                    requestId,
                    type,
                    "policy",
                    "policy gated tool call",
                    payload,
                    null,
                    null
            ));
        } catch (Exception ignored) {
        }
    }

    private String summarize(Object result) {
        String value = String.valueOf(result);
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private Map<String, Object> copy(Map<String, Object> raw) {
        return raw != null ? new LinkedHashMap<>(raw) : new LinkedHashMap<>();
    }

    private String normalizeTool(String toolName) {
        String tool = toolName != null ? toolName.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return tool.isBlank() ? "unknown" : tool;
    }

    private record PathRouting(PolicyDecision decision) {
    }

    public record PolicyToolResult(
            PolicyDecision decision,
            Map<String, Object> routedArgs,
            boolean executed,
            String approvalRequestId,
            String resultSummary,
            Object rawResult,
            String suggestedAction
    ) {
        public PolicyToolResult {
            routedArgs = routedArgs != null ? Collections.unmodifiableMap(new LinkedHashMap<>(routedArgs)) : Map.of();
            approvalRequestId = approvalRequestId != null ? approvalRequestId.trim() : "";
            resultSummary = resultSummary != null ? resultSummary.trim() : "";
            suggestedAction = suggestedAction != null ? suggestedAction.trim() : "";
        }
    }
}
