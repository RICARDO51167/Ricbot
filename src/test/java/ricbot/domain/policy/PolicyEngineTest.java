package ricbot.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {
    @Test
    void policyPatternsAreExactOrFullStringGlobsNotSubstrings() {
        assertFalse(PolicyRule.matchesPattern("read", "read_file"));
        assertTrue(PolicyRule.matchesPattern("read_*", "read_file"));
        assertFalse(PolicyRule.matchesPattern("read_*", "unsafe_read_file"));
    }

    @Test
    void explorerWriteFileDenied(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluate(PolicyRole.EXPLORER, "write_file", Map.of("path", "a.txt"), null);

        assertEquals(PolicyDecisionType.DENY, decision.decisionType());
        assertTrue(decision.denied());
    }

    @Test
    void explorerReadFileAllowed(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluate(PolicyRole.EXPLORER, "read_file", Map.of("path", "a.txt"), null);

        assertEquals(PolicyDecisionType.ALLOW, decision.decisionType());
    }

    @Test
    void verifierExecTestRequiresApprovalOrAllows(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluateCommand(PolicyRole.VERIFIER, "./mvnw test", null);

        assertTrue(decision.decisionType() == PolicyDecisionType.ALLOW
                || decision.decisionType() == PolicyDecisionType.REQUIRE_APPROVAL, decision.toString());
    }

    @Test
    void developerEditFileRequiresApproval(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluate(PolicyRole.DEVELOPER, "edit_file", Map.of("path", "src/App.java"), null);

        assertEquals(PolicyDecisionType.REQUIRE_APPROVAL, decision.decisionType());
        assertTrue(decision.requiresApproval());
    }

    @Test
    void blockedCommandDenied(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluateCommand(PolicyRole.TESTER, "sudo rm -rf /", null);

        assertEquals(PolicyDecisionType.DENY, decision.decisionType());
        assertTrue(decision.reasons().toString().contains("blocked"), decision.reasons().toString());
    }

    @Test
    void invalidConfigFallsBackToDefault(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(workspace.resolve("config").resolve("ricbot.policy.json"), "{not-json");

        PolicyEngine engine = new PolicyEngine(workspace);

        assertEquals("default-policy", engine.policy().source());
        assertEquals(PolicyDecisionType.ALLOW, engine.evaluate(PolicyRole.EXPLORER, "read_file", Map.of(), null).decisionType());
    }
}
