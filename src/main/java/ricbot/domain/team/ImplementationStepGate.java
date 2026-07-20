package ricbot.domain.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ImplementationStepGate {

    public StepGateResult canApply(PendingImplementationStep step, List<PendingImplementationStep> allSteps, GateContext context) {
        if (step == null) {
            return StepGateResult.blocked(List.of("implementation step not found"), List.of("choose an existing step id"), "", List.of());
        }
        GateContext safeContext = context != null ? context : GateContext.empty();
        List<PendingImplementationStep> safeSteps = allSteps != null ? allSteps : List.of();
        Map<String, PendingImplementationStep> byId = safeSteps.stream()
                .collect(Collectors.toMap(PendingImplementationStep::id, value -> value, (left, right) -> left));
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> blockedBy = new ArrayList<>();
        List<String> validationErrors = validateFields(step);

        if (step.status() == ImplementationStepStatus.APPLIED) {
            reasons.add("step is already applied");
        }
        if (step.status() == ImplementationStepStatus.REJECTED || step.status() == ImplementationStepStatus.FAILED) {
            reasons.add("step is " + step.status());
        }
        if (step.status() == ImplementationStepStatus.DRAFT) {
            reasons.add("step is DRAFT and is missing executable arguments");
            actions.add("update the step with concrete oldText/newText or reject it");
        }
        if (!validationErrors.isEmpty()) {
            reasons.addAll(validationErrors);
            actions.add("run /team update-step " + step.id() + " <jsonUpdate>");
        }

        for (String dependencyId : step.dependsOnStepIds()) {
            PendingImplementationStep dependency = byId.get(dependencyId);
            if (dependency == null) {
                reasons.add("dependency is missing: " + dependencyId);
                blockedBy.add(dependencyId);
                continue;
            }
            if (dependency.status() == ImplementationStepStatus.REJECTED || dependency.status() == ImplementationStepStatus.FAILED) {
                reasons.add("dependency " + dependency.id() + " is " + dependency.status());
                actions.add("create a replacement step or reject this step");
                blockedBy.add(dependency.id());
            } else if (dependency.status() != ImplementationStepStatus.APPLIED) {
                reasons.add("dependency " + dependency.id() + " must be applied first");
                actions.add("/team apply-step " + dependency.id());
                blockedBy.add(dependency.id());
            }
        }

        if ((step.type() == ImplementationStepType.EDIT || step.type() == ImplementationStepType.WRITE) && !step.targetPath().isBlank()) {
            boolean readExists = safeSteps.stream()
                    .anyMatch(candidate -> candidate.type() == ImplementationStepType.READ && sameTarget(candidate, step));
            boolean readApplied = safeSteps.stream()
                    .anyMatch(candidate -> candidate.type() == ImplementationStepType.READ
                            && sameTarget(candidate, step)
                            && candidate.status() == ImplementationStepStatus.APPLIED);
            if (readExists && !readApplied) {
                reasons.add(step.type() + " requires target READ step to be applied first");
                actions.add(firstCommandFor(safeSteps, candidate -> candidate.type() == ImplementationStepType.READ && sameTarget(candidate, step)));
            }
        }

        if (step.type() == ImplementationStepType.CREATE_CHANGESET) {
            boolean priorWorkApplied = safeSteps.stream()
                    .anyMatch(candidate -> candidate.status() == ImplementationStepStatus.APPLIED
                            && (candidate.type() == ImplementationStepType.EDIT
                            || candidate.type() == ImplementationStepType.WRITE
                            || candidate.type() == ImplementationStepType.EXEC_TEST));
            if (!priorWorkApplied && !safeContext.workspaceDiffExists()) {
                reasons.add("CREATE_CHANGESET requires an applied EDIT/WRITE/EXEC_TEST step or existing workspace diff");
                actions.add(nextMutableOrTestCommand(safeSteps));
            }
        }

        if (step.type() == ImplementationStepType.RUN_VERIFIER) {
            boolean changeStepApplied = safeSteps.stream()
                    .anyMatch(candidate -> candidate.type() == ImplementationStepType.CREATE_CHANGESET
                            && candidate.status() == ImplementationStepStatus.APPLIED);
            if (!safeContext.changeSetExists() && !changeStepApplied) {
                reasons.add("RUN_VERIFIER requires an existing ChangeSet or applied CREATE_CHANGESET step");
                actions.add(firstCommandFor(safeSteps, candidate -> candidate.type() == ImplementationStepType.CREATE_CHANGESET));
            }
        }

        reasons = dedupe(reasons);
        actions = dedupe(actions.stream().filter(value -> value != null && !value.isBlank()).toList());
        blockedBy = dedupe(blockedBy);
        if (!reasons.isEmpty()) {
            return StepGateResult.blocked(reasons, actions, actions.isEmpty() ? "" : actions.get(0), blockedBy);
        }
        return StepGateResult.allow();
    }

    public List<String> validateFields(PendingImplementationStep step) {
        if (step == null) {
            return List.of("step is required");
        }
        List<String> errors = new ArrayList<>();
        switch (step.type()) {
            case READ -> {
                if (step.targetPath().isBlank()) {
                    errors.add("READ requires targetPath");
                }
            }
            case EDIT -> {
                if (step.targetPath().isBlank()) {
                    errors.add("EDIT requires targetPath");
                }
                if (step.oldText().isBlank()) {
                    errors.add("EDIT requires oldText");
                }
                if (step.newText().isBlank()) {
                    errors.add("EDIT requires newText");
                }
            }
            case WRITE -> {
                if (step.targetPath().isBlank()) {
                    errors.add("WRITE requires targetPath");
                }
                if (step.newText().isBlank()) {
                    errors.add("WRITE requires newText");
                }
            }
            case EXEC_TEST -> {
                if (step.command().isBlank()) {
                    errors.add("EXEC_TEST requires command");
                }
            }
            case CREATE_CHANGESET, RUN_VERIFIER -> {
            }
        }
        return dedupe(errors);
    }

    public PendingImplementationStep nextStep(List<PendingImplementationStep> steps, GateContext context) {
        List<PendingImplementationStep> ordered = (steps != null ? steps : List.<PendingImplementationStep>of()).stream()
                .filter(step -> step.status() != ImplementationStepStatus.APPLIED
                        && step.status() != ImplementationStepStatus.REJECTED
                        && step.status() != ImplementationStepStatus.FAILED)
                .sorted(Comparator.comparingInt(PendingImplementationStep::orderIndex).thenComparing(PendingImplementationStep::createdAt))
                .toList();
        return ordered.stream()
                .filter(step -> canApply(step, steps, context).allowed())
                .findFirst()
                .orElse(ordered.stream().findFirst().orElse(null));
    }

    private boolean sameTarget(PendingImplementationStep left, PendingImplementationStep right) {
        return left != null && right != null && !left.targetPath().isBlank() && left.targetPath().equals(right.targetPath());
    }

    private String firstCommandFor(List<PendingImplementationStep> steps, java.util.function.Predicate<PendingImplementationStep> predicate) {
        return (steps != null ? steps : List.<PendingImplementationStep>of()).stream()
                .filter(predicate)
                .sorted(Comparator.comparingInt(PendingImplementationStep::orderIndex))
                .map(step -> "/team apply-step " + step.id())
                .findFirst()
                .orElse("");
    }

    private String nextMutableOrTestCommand(List<PendingImplementationStep> steps) {
        return firstCommandFor(steps, step -> step.type() == ImplementationStepType.EDIT
                || step.type() == ImplementationStepType.WRITE
                || step.type() == ImplementationStepType.EXEC_TEST);
    }

    private List<String> dedupe(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = value != null ? value.trim() : "";
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return out;
    }

    public record GateContext(boolean workspaceDiffExists, boolean changeSetExists) {
        public static GateContext empty() {
            return new GateContext(false, false);
        }
    }
}
