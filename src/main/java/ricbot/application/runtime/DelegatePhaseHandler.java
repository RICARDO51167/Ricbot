package ricbot.application.runtime;

import ricbot.domain.runtime.PhaseContext;
import ricbot.domain.runtime.PhaseResult;
import ricbot.domain.runtime.ChannelWrite;
import ricbot.domain.runtime.RunSpec;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.tool.api.JsonSchemaValidator;
import ricbot.tool.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DelegatePhaseHandler implements AgentPhaseHandler {
    private final AgentPhaseSupport services;
    DelegatePhaseHandler(AgentPhaseSupport services) { this.services = services; }
    @Override public PhaseResult execute(PhaseContext context) {
        Map<String, Object> call = AgentPhaseSupport.objectMap(
                context.state().channels().get("pendingRuntimeControl"));
        if (call.isEmpty() || !AgentPhaseSupport.SPAWN_CHILD_RUNS.equals(
                String.valueOf(call.getOrDefault("name", "")))) {
            return new PhaseResult(List.of(), List.of(new RuntimeCommand.Fail(
                    "RUNTIME_CONTROL_MISSING", "DELEGATE requires a persisted runtime control request")));
        }
        String callId = String.valueOf(call.getOrDefault("id", context.activationId() + ":delegate"));
        List<Map<String, Object>> pending = AgentPhaseSupport.objectList(
                context.state().channels().get("pendingToolCalls"));
        List<Map<String, Object>> remaining = pending.isEmpty() ? List.of()
                : List.copyOf(pending.subList(1, pending.size()));
        Map<String, Object> arguments = AgentPhaseSupport.objectMap(call.get("arguments"));
        List<String> schemaErrors = JsonSchemaValidator.validate(
                AgentPhaseSupport.spawnChildRunsParameters(), arguments);
        if (!schemaErrors.isEmpty()) {
            return services.finishTool(context, services.invocationSpec(context.state().spec().runId()),
                    UsageLedger.from(context.state().channels().get("usageLedger")), remaining,
                    callId, AgentPhaseSupport.SPAWN_CHILD_RUNS,
                    new ToolResult.Failure("INVALID_ARGUMENTS", String.join("; ", schemaErrors),
                            false, List.of()));
        }
        try {
            AgentPhaseSupport.ChildBatch batch = AgentPhaseSupport.childBatch(
                    context.state(), callId, arguments);
            String output = "created child runs: " + String.join(", ", batch.children().stream()
                    .map(RunSpec::runId).toList());
            services.appendTranscript(context.state().spec().runId(),
                    context.activationId() + ":runtime:" + callId,
                    AgentPhaseSupport.toolMessage(callId, AgentPhaseSupport.SPAWN_CHILD_RUNS, output));
            List<ChannelWrite> writes = new ArrayList<>(services.transcriptWrites(context.state()));
            writes.add(ChannelWrite.set("pendingToolCalls", remaining));
            writes.add(new ChannelWrite("pendingRuntimeControl", ChannelWrite.Operation.REMOVE, null));
            writes.add(ChannelWrite.set("continueToolBatch", !remaining.isEmpty()));
            writes.add(ChannelWrite.set("lastSpawnedChildRunIds",
                    batch.children().stream().map(RunSpec::runId).toList()));
            List<RuntimeCommand> commands = new ArrayList<>();
            commands.add(new RuntimeCommand.SpawnChildRuns(batch.children(), batch.waitForAll()));
            if (!batch.waitForAll()) commands.add(new RuntimeCommand.Transition(
                    remaining.isEmpty() ? RuntimePhase.CONTEXT : RuntimePhase.MODEL));
            return new PhaseResult(writes, commands);
        } catch (IllegalArgumentException failure) {
            return services.finishTool(context, services.invocationSpec(context.state().spec().runId()),
                    UsageLedger.from(context.state().channels().get("usageLedger")), remaining,
                    callId, AgentPhaseSupport.SPAWN_CHILD_RUNS,
                    new ToolResult.Failure("RUNTIME_CONTROL_REJECTED", failure.getMessage(),
                            false, List.of()));
        }
    }
}
