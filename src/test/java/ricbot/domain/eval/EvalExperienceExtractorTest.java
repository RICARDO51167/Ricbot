package ricbot.domain.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceStatus;
import ricbot.domain.experience.ExperienceType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalExperienceExtractorTest {

    @Test
    void assertionFailedBecomesTestPolicy(@TempDir Path runDir) {
        EvalCaseResult result = caseResult("assertion-case", "fail", "assertion_failed")
                .setAssertionErrors(List.of("expected response to contain done"));

        List<ExperienceEntry> entries = extract(runDir, result, false, false);

        assertEquals(1, entries.size());
        ExperienceEntry entry = entries.get(0);
        assertEquals(ExperienceStatus.CANDIDATE, entry.status());
        assertEquals(ExperienceType.TEST_POLICY, entry.type());
        assertEquals("assertion_failed", entry.failureKind());
        assertEquals("eval:run-123:assertion-case", entry.sourceRef());
        assertTrue(entry.evidence().contains("assertionOrStopReason: expected response to contain done"));
    }

    @Test
    void workspaceRestoreErrorBecomesToolPolicy(@TempDir Path runDir) {
        EvalCaseResult result = caseResult("restore-case", "fail", "workspace_restore_error")
                .setWorkspaceRestoreErrors(List.of("failed to restore workspace"));

        List<ExperienceEntry> entries = extract(runDir, result, false, false);

        assertEquals(1, entries.size());
        assertEquals(ExperienceType.TOOL_POLICY, entries.get(0).type());
        assertTrue(entries.get(0).suggestedTests().contains("./mvnw -q -Dtest='ricbot.tool.*.*Test' test"));
    }

    @Test
    void unexpectedPassedBecomesTestPolicy(@TempDir Path runDir) {
        EvalCaseResult result = caseResult("fixed-xfail", "xpass", "unexpected_pass")
                .setExpectationStatus("unexpected_pass")
                .setExpectationReason("xfail scenario passed unexpectedly");

        List<ExperienceEntry> entries = extract(runDir, result, false, false);

        assertEquals(1, entries.size());
        assertEquals(ExperienceType.TEST_POLICY, entries.get(0).type());
        assertTrue(entries.get(0).content().contains("xfail"));
    }

    @Test
    void xfailRequiresIncludeXfail(@TempDir Path runDir) {
        EvalCaseResult result = caseResult("known-gap", "xfail", "assertion_failed")
                .setExpectationStatus("expected_failure")
                .setExpectationReason("known gap");

        assertTrue(extract(runDir, result, false, false).isEmpty());

        List<ExperienceEntry> entries = extract(runDir, result, true, false);

        assertEquals(1, entries.size());
        assertEquals(ExperienceType.TEST_POLICY, entries.get(0).type());
    }

    @Test
    void skippedDoesNotGenerateByDefault(@TempDir Path runDir) {
        EvalCaseResult result = caseResult("skipped-case", "skipped", null)
                .setExpectationStatus("skipped")
                .setExpectationReason("blocked on fixture");

        List<ExperienceEntry> entries = extract(runDir, result, false, false);

        assertTrue(entries.isEmpty());
    }

    @Test
    void includeSkippedGeneratesCandidateWhenReasonExists(@TempDir Path runDir) {
        EvalCaseResult result = caseResult("skipped-case", "skipped", null)
                .setExpectationStatus("skipped")
                .setExpectationReason("blocked on fixture");

        List<ExperienceEntry> entries = extract(runDir, result, false, true);

        assertEquals(1, entries.size());
        assertEquals("skipped", entries.get(0).failureKind());
        assertEquals(ExperienceType.TEST_POLICY, entries.get(0).type());
    }

    private static List<ExperienceEntry> extract(
            Path runDir,
            EvalCaseResult result,
            boolean includeXfail,
            boolean includeSkipped
    ) {
        return new EvalExperienceExtractor().extract(summary(runDir), List.of(result), runDir, includeXfail, includeSkipped);
    }

    private static EvalRunSummary summary(Path runDir) {
        return new EvalRunSummary()
                .setRunId("run-123")
                .setArtifactDir(runDir.toString())
                .setTotal(1)
                .setFailed(1);
    }

    private static EvalCaseResult caseResult(String id, String status, String failureKind) {
        return new EvalCaseResult()
                .setId(id)
                .setStatus(status)
                .setFailureKind(failureKind)
                .setFailureDetail(failureKind != null ? "detail for " + failureKind : null)
                .setArtifactPath("/tmp/" + id + ".json")
                .setToolsUsed(List.of("write_file"))
                .setWorkspaceDiff(Map.of(
                        "change_count", 1,
                        "modified", List.of(Map.of("path", "src/main/java/ricbot/domain/eval/EvalHarness.java"))
                ));
    }
}
