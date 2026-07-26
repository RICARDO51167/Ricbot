package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionServiceTest {
    @Test
    void delegatesExactlyOnceAndLeavesRetryToGraph(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(new AgentRunResult().setFinalContent("done").setStopReason("stop"));
        AgentExecutionService service = service(runner, workspace);

        ExecutionOutcome outcome = service.executeInteractive(request(), ignored -> fail("legacy callback must not run"));

        assertEquals("done", outcome.finalContent());
        assertEquals(1, runner.specs.size());
        assertEquals(4, runner.specs.get(0).getMaxIterations());
        assertEquals(workspace, runner.specs.get(0).getWorkspace());
    }

    @Test
    void systemExecutionUsesSingleGraphInvocation(@TempDir Path workspace) throws Exception {
        StubRunner runner = new StubRunner(new AgentRunResult().setFinalContent("").setStopReason("stop"));
        ExecutionOutcome outcome = service(runner, workspace).executeSystem(request(), ignored -> { });
        assertEquals("后台任务已完成。", outcome.finalContent());
        assertEquals(1, runner.specs.size());
    }

    private static AgentExecutionService service(AgentInvocationRuntime runner, Path workspace) {
        return new AgentExecutionService(runner, new ToolRegistry(), workspace, "model", 4, 4000,
                "standard", 8000, 24);
    }

    private static AgentRequestContext request() {
        InboundMessage message = new InboundMessage("cli", "user", "direct", "hello");
        return new AgentRequestContext(message, "cli:direct", new Session("cli:direct"), "",
                new PromptContextBundle(), List.of(), List.of(Map.of("role", "user", "content", "hello")),
                null, false);
    }

    private static final class StubRunner implements AgentInvocationRuntime {
        private final AgentRunResult result;
        private final List<AgentRunSpec> specs = new ArrayList<>();
        private StubRunner(AgentRunResult result) { this.result = result; }
        @Override public AgentRunResult run(AgentRunSpec spec) { specs.add(spec); return result; }
    }
}
