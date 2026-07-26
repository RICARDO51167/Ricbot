package ricbot.tool.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionPolicyTest {

    @Test
    void defaultPolicy_preservesExistingBehavior() {
        ToolExecutionPolicy policy = ToolExecutionPolicy.defaultPolicy();
        ToolRegistry.ToolPolicy toolPolicy = policy.policyFor("echo", readOnlyTool("echo"));

        assertTrue(toolPolicy.readOnly());
        assertFalse(toolPolicy.exclusive());
        assertTrue(toolPolicy.concurrentSafe());
        assertEquals("read_only", toolPolicy.risk());
        assertEquals(0, policy.timeoutSecondsFor(toolPolicy));
        assertEquals(16000, policy.maxToolResultChars(16000));
    }

    @Test
    void policyForKnownTool_returnsExpectedTimeout() {
        ToolExecutionPolicy policy = ToolExecutionPolicy.defaultPolicy();

        assertEquals(0, policy.timeoutSecondsFor(policy.policyFor("read_file", readOnlyTool("read_file"))));
    }

    @Test
    void policyForUnknownTool_usesDefaultPolicy() {
        ToolExecutionPolicy policy = ToolExecutionPolicy.defaultPolicy();
        ToolRegistry.ToolPolicy missing = policy.policyFor("missing", null);

        assertEquals("missing", missing.name());
        assertFalse(missing.readOnly());
        assertFalse(missing.exclusive());
        assertTrue(missing.concurrentSafe());
        assertEquals("missing", missing.risk());
    }

    @Test
    void concurrentPolicy_preservesExistingDecision() {
        ToolExecutionPolicy policy = ToolExecutionPolicy.defaultPolicy();

        assertTrue(policy.canRunConcurrently(
                List.of("read_a", "read_b"),
                name -> policy.policyFor(name, readOnlyTool(name))
        ));
        assertFalse(policy.canRunConcurrently(
                List.of("read_a", "write_b"),
                name -> "write_b".equals(name)
                        ? policy.policyFor(name, sideEffectTool(name))
                        : policy.policyFor(name, readOnlyTool(name))
        ));
        assertFalse(policy.shouldRunConcurrently(
                false,
                List.of("read_a"),
                name -> policy.policyFor(name, readOnlyTool(name))
        ));
    }

    @Test
    void errorPolicy_preservesExistingDecision() {
        ToolExecutionPolicy policy = ToolExecutionPolicy.defaultPolicy();

        assertTrue(policy.shouldStopRunOnToolError(Map.of("status", "error"), true));
        assertFalse(policy.shouldStopRunOnToolError(Map.of("status", "error"), false));
        assertFalse(policy.shouldStopRunOnToolError(null, true));
    }

    private static Tool readOnlyTool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override public ToolEffectPolicy effectPolicy() {
                return ToolEffectPolicy.readOnly(java.time.Duration.ofSeconds(30));
            }
        };
    }

    private static Tool sideEffectTool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }
            @Override public ToolEffectPolicy effectPolicy() {
                return ToolEffectPolicy.atMostOnce(java.time.Duration.ofMinutes(1),
                        ToolEffectPolicy.Concurrency.SERIAL_PER_RUN, ToolEffectPolicy.Approval.RISK_BASED);
            }
        };
    }
}
