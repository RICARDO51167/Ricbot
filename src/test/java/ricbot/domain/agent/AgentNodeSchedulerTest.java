package ricbot.domain.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentNodeSchedulerTest {
    @Test
    void validatesTheDefaultReactGraph() {
        AgentNodeScheduler scheduler = new AgentNodeScheduler(AgentRunController.withMaxTurns(2));

        assertEquals(AgentNodeType.MODEL, scheduler.startModel().node());
        assertEquals(AgentNodeType.TOOLS, scheduler.tools().node());
        assertEquals(AgentNodeType.MODEL, scheduler.nextModel().node());
        assertEquals(2, scheduler.startModel().iteration());
        assertFalse(scheduler.canSchedule());
        assertEquals(AgentNodeType.TERMINAL, scheduler.terminal().node());
    }

    @Test
    void rejectsInvalidNodeTransitions() {
        AgentNodeScheduler scheduler = new AgentNodeScheduler(AgentRunController.withMaxTurns(3));
        scheduler.startModel();

        assertThrows(IllegalStateException.class, scheduler::nextModel);
        scheduler.tools();
        assertThrows(IllegalStateException.class, scheduler::tools);
    }
}
