package ricbot.domain.runtime;

import ricbot.domain.agent.SideEffectRecord;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskDelivery;

import java.util.List;

/** Immutable input for the one-shot legacy import transaction. */
public record LegacyRuntimeSnapshot(
        String migrationId,
        List<GraphExecutionState> activeRuns,
        List<TaskRecord> tasks,
        List<TaskResult> taskResults,
        List<TaskDelivery> deliveries,
        List<ApprovalRequest> approvals,
        List<SideEffectRecord> sideEffects,
        int archivedV1Records
) {
    public LegacyRuntimeSnapshot {
        if (migrationId == null || migrationId.isBlank()) throw new IllegalArgumentException("migrationId is required");
        activeRuns = List.copyOf(activeRuns != null ? activeRuns : List.of());
        tasks = List.copyOf(tasks != null ? tasks : List.of());
        taskResults = List.copyOf(taskResults != null ? taskResults : List.of());
        deliveries = List.copyOf(deliveries != null ? deliveries : List.of());
        approvals = List.copyOf(approvals != null ? approvals : List.of());
        sideEffects = List.copyOf(sideEffects != null ? sideEffects : List.of());
        if (archivedV1Records < 0) archivedV1Records = 0;
    }
}
