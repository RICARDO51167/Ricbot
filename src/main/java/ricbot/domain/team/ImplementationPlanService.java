package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ImplementationPlanService {

    public List<PendingImplementationStep> createSteps(WorkerExecutionResult result) {
        if (result == null) {
            return List.of();
        }
        List<PendingImplementationStep> steps = new ArrayList<>();
        List<String> targets = !result.relatedFiles().isEmpty() ? result.relatedFiles() : targetFilesFromPlan(result.developerPlan());
        for (String target : targets) {
            steps.add(step(result, ImplementationStepType.READ, target, "", "", "",
                    "Read target file before preparing edits.", CommandRiskLevel.SAFE, false, ImplementationStepStatus.READY));
        }
        EditDraft edit = editDraft(result, targets);
        if (edit.hasOldNew() && !edit.targetPath().isBlank()) {
            steps.add(step(result, ImplementationStepType.EDIT, edit.targetPath(), "", edit.oldText(), edit.newText(),
                    "Apply explicit replacement from developer plan.", CommandRiskLevel.MEDIUM, true, ImplementationStepStatus.READY));
        } else {
            steps.add(step(result, ImplementationStepType.EDIT, targets.isEmpty() ? "" : targets.get(0), "", "", "",
                    "Draft only: provide oldText/newText before applying an edit step.", CommandRiskLevel.MEDIUM, true, ImplementationStepStatus.DRAFT));
        }
        for (String test : result.suggestedTests()) {
            steps.add(step(result, ImplementationStepType.EXEC_TEST, "", test, "", "",
                    "Run suggested test command.", CommandRiskLevel.LOW, false, ImplementationStepStatus.READY));
        }
        steps.add(step(result, ImplementationStepType.CREATE_CHANGESET, "", "", "", "",
                "Create ChangeSet after approved edits.", CommandRiskLevel.LOW, false, ImplementationStepStatus.READY));
        steps.add(step(result, ImplementationStepType.RUN_VERIFIER, "", "", "", "",
                "Run verifier after ChangeSet creation.", CommandRiskLevel.LOW, false, ImplementationStepStatus.READY));
        return wireDependencies(steps);
    }

    private PendingImplementationStep step(
            WorkerExecutionResult result,
            ImplementationStepType type,
            String targetPath,
            String command,
            String oldText,
            String newText,
            String reason,
            CommandRiskLevel risk,
            boolean requiresApproval,
            ImplementationStepStatus status
    ) {
        return new PendingImplementationStep(null, result.teamSessionId(), result.taskId(), result.role(), type,
                targetPath, command, oldText, newText, reason, risk, requiresApproval, Map.of(), status, null, null);
    }

    private List<PendingImplementationStep> wireDependencies(List<PendingImplementationStep> source) {
        List<PendingImplementationStep> ordered = new ArrayList<>(source);
        List<PendingImplementationStep> reads = ordered.stream().filter(step -> step.type() == ImplementationStepType.READ).toList();
        List<PendingImplementationStep> edits = ordered.stream().filter(step -> step.type() == ImplementationStepType.EDIT || step.type() == ImplementationStepType.WRITE).toList();
        List<PendingImplementationStep> tests = ordered.stream().filter(step -> step.type() == ImplementationStepType.EXEC_TEST).toList();
        PendingImplementationStep createChangeSet = ordered.stream().filter(step -> step.type() == ImplementationStepType.CREATE_CHANGESET).findFirst().orElse(null);
        PendingImplementationStep runVerifier = ordered.stream().filter(step -> step.type() == ImplementationStepType.RUN_VERIFIER).findFirst().orElse(null);

        Map<String, List<String>> depends = new LinkedHashMap<>();
        Map<String, List<String>> unblocks = new LinkedHashMap<>();
        for (PendingImplementationStep step : ordered) {
            depends.put(step.id(), new ArrayList<>());
            unblocks.put(step.id(), new ArrayList<>());
        }

        for (PendingImplementationStep edit : edits) {
            for (PendingImplementationStep read : reads) {
                if (!edit.targetPath().isBlank() && edit.targetPath().equals(read.targetPath())) {
                    depends.get(edit.id()).add(read.id());
                    unblocks.get(read.id()).add(edit.id());
                }
            }
        }

        List<PendingImplementationStep> testDependencies = !edits.isEmpty() ? edits : reads;
        for (PendingImplementationStep test : tests) {
            for (PendingImplementationStep dependency : testDependencies) {
                depends.get(test.id()).add(dependency.id());
                unblocks.get(dependency.id()).add(test.id());
            }
        }

        if (createChangeSet != null) {
            List<PendingImplementationStep> changeSetDependencies = new ArrayList<>();
            changeSetDependencies.addAll(edits);
            changeSetDependencies.addAll(tests);
            for (PendingImplementationStep dependency : changeSetDependencies) {
                depends.get(createChangeSet.id()).add(dependency.id());
                unblocks.get(dependency.id()).add(createChangeSet.id());
            }
        }

        if (runVerifier != null && createChangeSet != null) {
            depends.get(runVerifier.id()).add(createChangeSet.id());
            unblocks.get(createChangeSet.id()).add(runVerifier.id());
        }

        return ordered.stream()
                .sorted(Comparator.comparingInt(this::typeOrder))
                .map(step -> step.withDependencies(
                        (typeOrder(step) * 100) + ordered.indexOf(step),
                        depends.getOrDefault(step.id(), List.of()),
                        unblocks.getOrDefault(step.id(), List.of()),
                        qualityGate(step),
                        requiredBeforeApply(step, depends.getOrDefault(step.id(), List.of()))
                ))
                .toList();
    }

    private int typeOrder(PendingImplementationStep step) {
        return switch (step.type()) {
            case READ -> 1;
            case EDIT, WRITE -> 2;
            case EXEC_TEST -> 3;
            case CREATE_CHANGESET -> 4;
            case RUN_VERIFIER -> 5;
        };
    }

    private String qualityGate(PendingImplementationStep step) {
        return switch (step.type()) {
            case READ -> "Read target before modifying it.";
            case EDIT, WRITE -> "Requires matching READ and policy/approval for writes.";
            case EXEC_TEST -> "Run only after relevant reads or edits.";
            case CREATE_CHANGESET -> "Requires applied work or workspace diff.";
            case RUN_VERIFIER -> "Requires ChangeSet context.";
        };
    }

    private List<String> requiredBeforeApply(PendingImplementationStep step, List<String> dependencies) {
        List<String> out = new ArrayList<>();
        if (!dependencies.isEmpty()) {
            out.add("Apply dependencies: " + String.join(", ", dependencies));
        }
        if (step.type() == ImplementationStepType.EDIT && (step.oldText().isBlank() || step.newText().isBlank())) {
            out.add("Provide oldText/newText before applying.");
        }
        if (step.type() == ImplementationStepType.WRITE && step.newText().isBlank()) {
            out.add("Provide write content before applying.");
        }
        return out;
    }

    private List<String> targetFilesFromPlan(List<String> developerPlan) {
        List<String> out = new ArrayList<>();
        for (String row : developerPlan != null ? developerPlan : List.<String>of()) {
            String lower = row.toLowerCase(Locale.ROOT);
            int index = lower.indexOf("targetfiles=");
            if (index < 0) {
                continue;
            }
            String tail = row.substring(index + "targetFiles=".length());
            int end = tail.indexOf("|");
            if (end >= 0) {
                tail = tail.substring(0, end);
            }
            for (String token : tail.split(",")) {
                String value = token.trim();
                if (!value.isBlank() && !"none yet".equalsIgnoreCase(value) && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private EditDraft editDraft(WorkerExecutionResult result, List<String> targets) {
        String text = result.goal() != null && !result.goal().isBlank()
                ? result.goal()
                : String.join(" ", result.developerPlan());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)replace\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+in\\s+.+|$|[|;])").matcher(text.trim());
        if (!matcher.find()) {
            return new EditDraft(targets.isEmpty() ? "" : targets.get(0), "", "");
        }
        return new EditDraft(targets.isEmpty() ? "" : targets.get(0), cleanQuoted(matcher.group(1)), cleanQuoted(matcher.group(2)));
    }

    private String cleanQuoted(String value) {
        return value != null ? value.trim().replaceAll("^['\"]|['\"]$", "") : "";
    }

    private record EditDraft(String targetPath, String oldText, String newText) {
        private boolean hasOldNew() {
            return !oldText.isBlank() && !newText.isBlank();
        }
    }
}
