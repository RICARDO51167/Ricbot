package ricbot.domain.team;

import java.util.List;

public record VerificationEvidence(
        List<ExecutedTestEvidence> executedTests,
        List<DiffEvidence> changedFiles,
        List<ApprovalEvidence> approvals
) {
    public VerificationEvidence {
        executedTests = executedTests != null ? List.copyOf(executedTests) : List.of();
        changedFiles = changedFiles != null ? List.copyOf(changedFiles) : List.of();
        approvals = approvals != null ? List.copyOf(approvals) : List.of();
    }

    public boolean isEmpty() {
        return executedTests.isEmpty() && changedFiles.isEmpty() && approvals.isEmpty();
    }
}
