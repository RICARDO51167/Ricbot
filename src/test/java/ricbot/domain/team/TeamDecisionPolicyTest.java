package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import ricbot.domain.security.CommandRiskLevel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamDecisionPolicyTest {

    @Test
    void highRiskMultiFileTaskSuggestsTeam() {
        TeamDecisionPolicy.Decision decision = new TeamDecisionPolicy().evaluate(
                "Change approval risk checks and verify behavior",
                CommandRiskLevel.HIGH,
                List.of(
                        "src/main/java/ricbot/domain/security/ApprovalService.java",
                        "src/main/java/ricbot/tool/process/ExecTool.java"
                ),
                4,
                true,
                true
        );

        assertTrue(decision.useTeam());
        assertTrue(decision.reasons().contains("multiple changed files"));
        assertTrue(decision.reasons().contains("high risk level"));
        assertTrue(decision.reasons().contains("security sensitive scope"));
        assertTrue(decision.reasons().contains("requires verifier"));
    }

    @Test
    void simpleLowRiskSingleFileTaskDoesNotSuggestTeam() {
        TeamDecisionPolicy.Decision decision = new TeamDecisionPolicy().evaluate(
                "Update one docs paragraph",
                CommandRiskLevel.SAFE,
                List.of("docs/demo/self-improving-agent-loop.md"),
                1,
                false,
                false
        );

        assertFalse(decision.useTeam());
        assertTrue(decision.reasons().contains("simple low-risk single-step task"));
    }
}
