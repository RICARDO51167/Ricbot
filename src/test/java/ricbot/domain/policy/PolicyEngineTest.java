package ricbot.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.task.TaskRole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {

    @Test
    void explorerWriteFileDenied(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluate(TaskRole.EXPLORER, "write_file", Map.of("path", "a.txt"), null);

        assertEquals(PolicyDecisionType.DENY, decision.decisionType());
        assertTrue(decision.denied());
    }

    @Test
    void explorerReadFileAllowed(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluate(TaskRole.EXPLORER, "read_file", Map.of("path", "a.txt"), null);

        assertEquals(PolicyDecisionType.ALLOW, decision.decisionType());
    }

    @Test
    void verifierExecTestRequiresApprovalOrAllows(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluateCommand(TaskRole.VERIFIER, "./mvnw test", null);

        assertTrue(decision.decisionType() == PolicyDecisionType.ALLOW
                || decision.decisionType() == PolicyDecisionType.REQUIRE_APPROVAL, decision.toString());
    }

    @Test
    void developerEditFileRequiresApproval(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluate(TaskRole.DEVELOPER, "edit_file", Map.of("path", "src/App.java"), null);

        assertEquals(PolicyDecisionType.REQUIRE_APPROVAL, decision.decisionType());
        assertTrue(decision.requiresApproval());
    }

    @Test
    void blockedCommandDenied(@TempDir Path workspace) {
        PolicyDecision decision = new PolicyEngine(workspace).evaluateCommand(TaskRole.TESTER, "sudo rm -rf /", null);

        assertEquals(PolicyDecisionType.DENY, decision.decisionType());
        assertTrue(decision.reasons().toString().contains("blocked"), decision.reasons().toString());
    }

    @Test
    void invalidConfigFallsBackToDefault(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(workspace.resolve("config").resolve("ricbot.policy.json"), "{not-json");

        PolicyEngine engine = new PolicyEngine(workspace);

        assertEquals("default-policy", engine.policy().source());
        assertEquals(PolicyDecisionType.ALLOW, engine.evaluate(TaskRole.EXPLORER, "read_file", Map.of(), null).decisionType());
    }
}
