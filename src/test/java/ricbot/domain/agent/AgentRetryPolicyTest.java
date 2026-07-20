package ricbot.domain.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRetryPolicyTest {

    @Test
    void noRetryPolicy_cannotRetry() {
        AgentRetryPolicy policy = AgentRetryPolicy.none();

        assertFalse(policy.canRetry());
        assertFalse(policy.recordFailure("tool_loop"));
        assertEquals(0, policy.retryCount());
        assertEquals("tool_loop", policy.lastRetryReason().orElseThrow());
    }

    @Test
    void maxRetriesPolicy_allowsRetryWithinLimit() {
        AgentRetryPolicy policy = AgentRetryPolicy.maxRetries(2);

        assertTrue(policy.canRetry());
        assertTrue(policy.recordFailure("tool_loop"));
        assertTrue(policy.canRetry());
        assertTrue(policy.recordFailure("tool_error_loop"));

        assertEquals(2, policy.retryCount());
        assertEquals("tool_error_loop", policy.lastRetryReason().orElseThrow());
    }

    @Test
    void maxRetriesPolicy_rejectsRetryAfterLimit() {
        AgentRetryPolicy policy = AgentRetryPolicy.maxRetries(1);

        assertTrue(policy.recordFailure("tool_loop"));
        assertFalse(policy.canRetry());
        assertFalse(policy.recordFailure("tool_error_loop"));

        assertEquals(1, policy.retryCount());
        assertEquals("tool_error_loop", policy.lastRetryReason().orElseThrow());
    }

    @Test
    void recordFailure_recordsLastReason() {
        AgentRetryPolicy policy = AgentRetryPolicy.maxRetries(1);

        policy.recordFailure("empty_spin");

        assertEquals("empty_spin", policy.lastRetryReason().orElseThrow());
    }
}
