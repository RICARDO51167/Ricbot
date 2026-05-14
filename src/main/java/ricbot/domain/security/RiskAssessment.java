package ricbot.domain.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RiskAssessment(
        CommandRiskLevel riskLevel,
        List<String> reasons,
        String command,
        String toolName,
        List<String> affectedPaths,
        boolean requiresApproval,
        boolean blocked
) {
    public static RiskAssessment of(
            CommandRiskLevel riskLevel,
            List<String> reasons,
            String command,
            String toolName,
            List<String> affectedPaths
    ) {
        CommandRiskLevel level = riskLevel != null ? riskLevel : CommandRiskLevel.SAFE;
        return new RiskAssessment(
                level,
                reasons != null ? List.copyOf(reasons) : List.of(),
                command != null ? command : "",
                toolName != null ? toolName : "",
                affectedPaths != null ? List.copyOf(affectedPaths) : List.of(),
                level == CommandRiskLevel.MEDIUM || level == CommandRiskLevel.HIGH,
                level == CommandRiskLevel.BLOCKED
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("riskLevel", riskLevel.name());
        out.put("reasons", reasons);
        out.put("command", command);
        out.put("toolName", toolName);
        out.put("affectedPaths", affectedPaths);
        out.put("requiresApproval", requiresApproval);
        out.put("blocked", blocked);
        return out;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("RiskAssessment\n");
        sb.append("riskLevel: ").append(riskLevel).append("\n");
        sb.append("toolName: ").append(toolName).append("\n");
        if (command != null && !command.isBlank()) {
            sb.append("command: ").append(command).append("\n");
        }
        sb.append("requiresApproval: ").append(requiresApproval).append("\n");
        sb.append("blocked: ").append(blocked).append("\n");
        if (affectedPaths != null && !affectedPaths.isEmpty()) {
            sb.append("affectedPaths: ").append(String.join(", ", affectedPaths)).append("\n");
        }
        if (reasons != null && !reasons.isEmpty()) {
            sb.append("reasons:\n");
            for (String reason : reasons) {
                sb.append("- ").append(reason).append("\n");
            }
        }
        return sb.toString().trim();
    }

    public RiskAssessment withReason(String reason) {
        List<String> next = new ArrayList<>(reasons != null ? reasons : List.of());
        if (reason != null && !reason.isBlank()) {
            next.add(reason);
        }
        return new RiskAssessment(riskLevel, next, command, toolName, affectedPaths, requiresApproval, blocked);
    }
}
