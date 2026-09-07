package ricbot.tool.api;

import java.util.List;

public record ToolAuthorizationDecision(Decision decision, String reason, List<String> evidence, boolean safetyDeny) {
    public enum Decision { ALLOW, DENY, REQUIRE_APPROVAL }
    public ToolAuthorizationDecision { decision = decision != null ? decision : Decision.DENY; reason = reason != null ? reason : ""; evidence = List.copyOf(evidence != null ? evidence : List.of()); }
    public static ToolAuthorizationDecision deny(String reason, boolean safety) { return new ToolAuthorizationDecision(Decision.DENY, reason, List.of(), safety); }
}
