package ricbot.domain.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.workspace.dto.WorkspaceSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PolicyEngine {
    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final RoleToolPolicy policy;
    private final CommandRiskAnalyzer riskAnalyzer;

    public PolicyEngine(Path workspace) {
        this(workspace, loadPolicy(workspace));
    }

    public PolicyEngine(Path workspace, RoleToolPolicy policy) {
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : Path.of(".").toAbsolutePath().normalize();
        this.policy = policy != null ? policy : RoleToolPolicy.defaultPolicy();
        this.riskAnalyzer = new CommandRiskAnalyzer(this.workspace);
    }

    public PolicyDecision evaluate(PolicyRole role, String toolName, Map<String, Object> args, WorkspaceSession workspaceSession) {
        String tool = normalizeTool(toolName);
        RiskAssessment risk = riskAnalyzer.analyzeTool(tool, pathFromArgs(args));
        return decide(role, tool, risk, "tool policy evaluated");
    }

    public PolicyDecision evaluateCommand(PolicyRole role, String command, WorkspaceSession workspaceSession) {
        String workingDir = workspaceSession != null && !workspaceSession.workspacePath().isBlank() ? workspaceSession.workspacePath() : workspace.toString();
        RiskAssessment risk = riskAnalyzer.analyzeExec(command, workingDir);
        String tool = isTestCommand(command) ? "exec test" : "exec";
        return decide(role, tool, risk, "command policy evaluated");
    }

    public PolicyDecision evaluatePath(PolicyRole role, String path, WorkspaceSession workspaceSession) {
        RiskAssessment risk = riskAnalyzer.analyzeTool("read_file", path);
        return decide(role, "read_file", risk, "path policy evaluated");
    }

    public RoleToolPolicy policy() {
        return policy;
    }

    private PolicyDecision decide(PolicyRole role, String tool, RiskAssessment risk, String reason) {
        PolicyRole safeRole = role != null ? role : PolicyRole.LEADER;
        List<String> reasons = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        reasons.add(reason);
        if (risk != null) {
            reasons.addAll(risk.reasons());
        }
        for (PolicyRule rule : policy.extraRules()) {
            if (rule.matches(safeRole, tool)) {
                matched.add(rule.id());
                reasons.addAll(rule.reasons());
                return decision(rule.decisionType(), safeRole, tool, riskLevel(risk), reasons, matched);
            }
        }
        RoleToolPolicy.RolePolicy rolePolicy = policy.forRole(safeRole);
        if (risk != null && risk.riskLevel() == CommandRiskLevel.BLOCKED) {
            matched.add("risk:blocked");
            reasons.add("CommandRiskAnalyzer blocked this operation.");
            return decision(PolicyDecisionType.DENY, safeRole, tool, risk.riskLevel(), reasons, matched);
        }
        if (matches(rolePolicy.deny(), tool)) {
            matched.add(safeRole.name() + ":deny");
            return decision(PolicyDecisionType.DENY, safeRole, tool, riskLevel(risk), reasons, matched);
        }
        if (risk != null && (risk.riskLevel() == CommandRiskLevel.HIGH || risk.riskLevel() == CommandRiskLevel.MEDIUM)) {
            if (safeRole == PolicyRole.DEVELOPER || matches(rolePolicy.approval(), tool)) {
                matched.add("risk:approval");
                return decision(PolicyDecisionType.REQUIRE_APPROVAL, safeRole, tool, risk.riskLevel(), reasons, matched);
            }
            matched.add("risk:deny");
            return decision(PolicyDecisionType.DENY, safeRole, tool, risk.riskLevel(), reasons, matched);
        }
        if (matches(rolePolicy.allow(), tool)) {
            matched.add(safeRole.name() + ":allow");
            return decision(PolicyDecisionType.ALLOW, safeRole, tool, riskLevel(risk), reasons, matched);
        }
        if (matches(rolePolicy.approval(), tool)) {
            matched.add(safeRole.name() + ":approval");
            return decision(PolicyDecisionType.REQUIRE_APPROVAL, safeRole, tool, riskLevel(risk), reasons, matched);
        }
        matched.add(safeRole.name() + ":default-deny");
        reasons.add("tool is not in role allowlist");
        return decision(PolicyDecisionType.DENY, safeRole, tool, riskLevel(risk), reasons, matched);
    }

    private PolicyDecision decision(PolicyDecisionType type, PolicyRole role, String tool, CommandRiskLevel risk, List<String> reasons, List<String> matched) {
        String action = switch (type) {
            case ALLOW -> "continue";
            case REQUIRE_APPROVAL -> "request approval before execution";
            case DENY -> "do not execute";
        };
        return new PolicyDecision(type, role, tool, reasons, risk, type == PolicyDecisionType.REQUIRE_APPROVAL,
                type == PolicyDecisionType.DENY, matched, action);
    }

    private boolean matches(List<String> patterns, String tool) {
        String value = tool != null ? tool.toLowerCase(java.util.Locale.ROOT) : "";
        for (String pattern : patterns != null ? patterns : List.<String>of()) {
            if (PolicyRule.matchesPattern(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTool(String toolName) {
        String tool = toolName != null ? toolName.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return tool.isBlank() ? "unknown" : tool;
    }

    private String pathFromArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        Object path = args.get("path");
        return path != null ? String.valueOf(path) : "";
    }

    private boolean isTestCommand(String command) {
        String lower = command != null ? command.toLowerCase(java.util.Locale.ROOT) : "";
        return lower.contains(" test") || lower.contains("mvnw test") || lower.contains("npm test") || lower.contains("-dtest=");
    }

    private CommandRiskLevel riskLevel(RiskAssessment risk) {
        return risk != null && risk.riskLevel() != null ? risk.riskLevel() : CommandRiskLevel.SAFE;
    }

    private static RoleToolPolicy loadPolicy(Path workspace) {
        Path file = workspace != null ? workspace.toAbsolutePath().normalize().resolve("config").resolve("ricbot.policy.json") : null;
        if (file == null || !Files.exists(file)) {
            return RoleToolPolicy.defaultPolicy();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
            List<PolicyRule> rules = new ArrayList<>();
            Object rawRules = raw.get("rules");
            if (rawRules instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        rules.add(new PolicyRule(
                                string(map.get("id")),
                                parseRole(map.get("role")),
                                string(map.get("tool")),
                                parseDecision(map.get("decision")),
                                stringList(map.get("reasons"))
                        ));
                    }
                }
            }
            return new RoleToolPolicy(RoleToolPolicy.defaultPolicy().policies(), rules, "config/ricbot.policy.json");
        } catch (Exception e) {
            log.warn("policy config parse failed, fallback default policy: {}", file, e);
            return RoleToolPolicy.defaultPolicy();
        }
    }

    private static PolicyRole parseRole(Object raw) {
        try {
            return raw != null ? PolicyRole.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : PolicyRole.LEADER;
        } catch (Exception e) {
            return PolicyRole.LEADER;
        }
    }

    private static PolicyDecisionType parseDecision(Object raw) {
        try {
            return raw != null ? PolicyDecisionType.valueOf(String.valueOf(raw).replace("-", "_").toUpperCase(java.util.Locale.ROOT)) : PolicyDecisionType.DENY;
        } catch (Exception e) {
            return PolicyDecisionType.DENY;
        }
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }
}
