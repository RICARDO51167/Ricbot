package ricbot.domain.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceStoreTest {

    @Test
    void addCandidateAndListCandidates(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);

        ExperienceEntry added = store.addCandidate(candidate("Run filesystem tests"));

        assertTrue(Files.exists(workspace.resolve("experience").resolve("candidates.jsonl")));
        assertEquals(1, store.listCandidates().size());
        assertEquals(added.id(), store.listCandidates().get(0).id());
    }

    @Test
    void verifyPromotesCandidateToVerifiedFile(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry added = store.addCandidate(candidate("Run agent tests"));

        ExperienceEntry verified = store.verify(added.id());

        assertEquals(ExperienceStatus.VERIFIED, verified.status());
        assertEquals(0, store.listCandidates().size());
        assertTrue(Files.readString(store.verifiedFile()).contains(added.id()));
        assertEquals(ExperienceStatus.VERIFIED, store.find(added.id()).status());
    }

    @Test
    void rejectWritesRejectedFile(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry added = store.addCandidate(candidate("Reject noisy rule"));

        ExperienceEntry rejected = store.reject(added.id());

        assertEquals(ExperienceStatus.REJECTED, rejected.status());
        assertEquals(0, store.listCandidates().size());
        assertTrue(Files.readString(store.rejectedFile()).contains(added.id()));
    }

    @Test
    void addCandidateDeduplicatesSimilarExperiences(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);

        ExperienceEntry first = store.addCandidate(candidate("Run filesystem tests"));
        ExperienceEntry second = store.addCandidate(candidate("Run filesystem tests"));

        assertEquals(first.id(), second.id());
        assertEquals(1, store.listCandidates().size());
        assertNotNull(store.find(first.id()));
    }

    @Test
    void addCandidateDeduplicatesSameSourceRefTypeAndTitle(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry first = ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                "Eval assertion_failed: case-a",
                "first",
                "When eval fails.",
                "failureKind: assertion_failed",
                "eval_case",
                "eval:run-1:case-a",
                List.of(),
                List.of(),
                0.6d,
                "assertion_failed"
        );
        ExperienceEntry second = ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                "Eval assertion_failed: case-a",
                "second",
                "When another eval fails.",
                "failureKind: assertion_failed",
                "eval_case",
                "eval:run-1:case-a",
                List.of(),
                List.of(),
                0.8d,
                "assertion_failed"
        );

        ExperienceEntry firstStored = store.addCandidate(first);
        ExperienceEntry secondStored = store.addCandidate(second);

        assertEquals(firstStored.id(), secondStored.id());
        assertEquals(1, store.listCandidates().size());
    }

    @Test
    void addCandidateDeduplicatesSourceRefAlreadyVerified(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry first = store.addCandidate(ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                "Eval assertion_failed: case-a",
                "first",
                "When eval fails.",
                "failureKind: assertion_failed",
                "eval_case",
                "eval:run-1:case-a",
                List.of(),
                List.of(),
                0.6d,
                "assertion_failed"
        ));
        store.verify(first.id());

        ExperienceEntry duplicate = store.addCandidate(ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                "Eval assertion_failed: case-a",
                "second",
                "When eval fails again.",
                "failureKind: assertion_failed",
                "eval_case",
                "eval:run-1:case-a",
                List.of(),
                List.of(),
                0.8d,
                "assertion_failed"
        ));

        assertEquals(first.id(), duplicate.id());
        assertEquals(0, store.listCandidates().size());
    }

    @Test
    void searchVerifiedOnlyReturnsVerifiedExperience(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry verifiedCandidate = store.addCandidate(candidate("Run filesystem tests"));
        store.addCandidate(candidate("Candidate should not appear"));
        ExperienceEntry rejectedCandidate = store.addCandidate(candidate("Rejected should not appear"));
        store.verify(verifiedCandidate.id());
        store.reject(rejectedCandidate.id());

        List<ExperienceStore.ScoredExperience> results = store.searchVerified("filesystem tests", List.of(), 5);

        assertEquals(1, results.size());
        assertEquals(ExperienceStatus.VERIFIED, results.get(0).entry().status());
        assertEquals(verifiedCandidate.id(), results.get(0).entry().id());
    }

    @Test
    void searchVerifiedUsesRelatedFilesForRanking(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry lowConfidencePathMatch = store.addCandidate(candidate(
                "Run filesystem targeted tests",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                0.4d
        ));
        ExperienceEntry highConfidenceNoPath = store.addCandidate(candidate(
                "Run unrelated agent tests",
                List.of("src/main/java/ricbot/domain/agent/AgentLoop.java"),
                0.9d
        ));
        store.verify(lowConfidencePathMatch.id());
        store.verify(highConfidenceNoPath.id());

        List<ExperienceStore.ScoredExperience> results = store.searchVerified(
                "targeted tests",
                List.of("src/main/java/ricbot/tool/filesystem/EditFileTool.java"),
                5
        );

        assertFalse(results.isEmpty());
        assertEquals(lowConfidencePathMatch.id(), results.get(0).entry().id());
    }

    @Test
    void searchVerifiedUsesConfidenceAsTieBreaker(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry low = store.addCandidate(candidate("Run shared smoke tests low", List.of(), 0.3d));
        ExperienceEntry high = store.addCandidate(candidate("Run shared smoke tests high", List.of(), 0.9d));
        store.verify(low.id());
        store.verify(high.id());

        List<ExperienceStore.ScoredExperience> results = store.searchVerified("shared smoke tests", List.of(), 5);

        assertEquals(high.id(), results.get(0).entry().id());
    }

    @Test
    void searchVerifiedSkipsInvalidJsonlRows(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry valid = store.addCandidate(candidate("Run filesystem tests after edits"));
        store.verify(valid.id());
        Files.writeString(store.verifiedFile(), "{broken json\n", java.nio.file.StandardOpenOption.APPEND);

        List<ExperienceStore.ScoredExperience> results = store.searchVerified("filesystem tests", List.of(), 5);

        assertEquals(1, results.size());
        assertEquals(valid.id(), results.get(0).entry().id());
    }

    @Test
    void recordUsageWritesTraceAndUpdatesLastUsedAt(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = store.addCandidate(candidate("Run filesystem tests after edits"));
        store.verify(entry.id());

        ExperienceUsageTrace trace = store.recordUsage(entry.id(), "session-1", "filesystem tests", "goal", 0.82d, "keywords=filesystem");

        assertEquals(entry.id(), trace.experienceId());
        assertEquals(ExperienceOutcome.UNKNOWN, trace.outcome());
        assertTrue(Files.readString(store.usageTraceFile()).contains(entry.id()));
        assertEquals(1, store.listUsage(entry.id()).size());
        assertFalse(store.find(entry.id()).lastUsedAt().isBlank());
    }

    @Test
    void feedbackUpdatesSuccessAndFailureCounts(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = store.addCandidate(candidate("Run filesystem tests after edits"));
        store.verify(entry.id());
        store.recordUsage(entry.id(), "filesystem tests", "goal", 0.8d, "keywords=filesystem");

        ExperienceEntry success = store.feedback(entry.id(), ExperienceOutcome.SUCCESS);
        ExperienceEntry failure = store.feedback(entry.id(), ExperienceOutcome.FAILURE);

        assertEquals(1, success.successCount());
        assertEquals(1, failure.successCount());
        assertEquals(1, failure.failureCount());
        assertEquals(ExperienceOutcome.SUCCESS, store.listUsage(entry.id()).get(0).outcome());
    }

    @Test
    void searchVerifiedUsesEffectiveConfidenceForRanking(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry highBase = store.addCandidate(candidate("Run shared smoke tests high base", List.of(), 0.7d));
        ExperienceEntry lowerBaseWithSuccess = store.addCandidate(candidate("Run shared smoke tests with success", List.of(), 0.6d));
        store.verify(highBase.id());
        store.verify(lowerBaseWithSuccess.id());
        store.feedback(lowerBaseWithSuccess.id(), ExperienceOutcome.SUCCESS);
        store.feedback(lowerBaseWithSuccess.id(), ExperienceOutcome.SUCCESS);
        store.feedback(lowerBaseWithSuccess.id(), ExperienceOutcome.SUCCESS);

        List<ExperienceStore.ScoredExperience> results = store.searchVerified("shared smoke tests", List.of(), 5);

        assertEquals(lowerBaseWithSuccess.id(), results.get(0).entry().id());
        assertTrue(results.get(0).effectiveConfidence() > results.get(1).effectiveConfidence());
    }

    @Test
    void listStaleVerifiedFindsLowConfidenceFailuresAndOldEntries(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry failed = store.addCandidate(candidate("Run filesystem stale tests", List.of(), 0.8d));
        store.verify(failed.id());
        store.feedback(failed.id(), ExperienceOutcome.FAILURE);

        List<ExperienceStore.ScoredExperience> stale = store.listStaleVerified();

        assertEquals(1, stale.size());
        assertEquals(failed.id(), stale.get(0).entry().id());
        assertTrue(stale.get(0).reason().contains("failureCount>successCount"), stale.get(0).reason());

        ExperienceEntry old = new ExperienceEntry(
                null,
                ExperienceType.TEST_POLICY,
                ExperienceStatus.CANDIDATE,
                "Run old filesystem tests",
                "Run targeted tests after changing filesystem tools.",
                "When changing filesystem tools.",
                "Old verified rule.",
                "task_summary",
                "old",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                List.of(),
                0.7d,
                0,
                0,
                Instant.now().minus(java.time.Duration.ofDays(120)).toString(),
                "",
                "",
                "",
                "",
                Instant.now().minus(java.time.Duration.ofDays(120)).toString(),
                Instant.now().minus(java.time.Duration.ofDays(120)).toString()
        );
        ExperienceEntry oldStored = store.addCandidate(old);
        store.verify(oldStored.id());

        List<String> staleIds = store.listStaleVerified().stream().map(item -> item.entry().id()).toList();
        assertTrue(staleIds.contains(oldStored.id()), staleIds.toString());
    }

    @Test
    void archiveRemovesExperienceFromVerifiedSearch(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = store.addCandidate(candidate("Run filesystem archive tests"));
        store.verify(entry.id());

        ExperienceEntry archived = store.archive(entry.id());

        assertEquals(ExperienceStatus.ARCHIVED, archived.status());
        assertEquals(ExperienceStatus.ARCHIVED, store.find(entry.id()).status());
        assertTrue(store.searchVerified("filesystem archive tests", List.of(), 5).isEmpty());
    }

    @Test
    void markPromotedAndDemoteAndRestoreArchived(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = store.addCandidate(candidate("Run filesystem governance tests"));
        store.verify(entry.id());

        ExperienceEntry promoted = store.markPromoted(entry.id(), "notes/project/test_policy.md", "manual promote");
        ExperienceEntry demoted = store.demote(entry.id());
        ExperienceEntry archived = store.archive(entry.id());
        ExperienceEntry restored = store.restore(entry.id());

        assertFalse(promoted.promotedAt().isBlank());
        assertEquals("notes/project/test_policy.md", promoted.promotedTo());
        assertFalse(demoted.demotedAt().isBlank());
        assertEquals(ExperienceStatus.ARCHIVED, archived.status());
        assertEquals(ExperienceStatus.VERIFIED, restored.status());
    }

    @Test
    void statsAndReviewReportGovernanceData(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry high = store.addCandidate(candidate("Run high value tests", List.of(), 0.9d));
        ExperienceEntry failing = store.addCandidate(candidate("Run failing tests", List.of(), 0.6d));
        ExperienceEntry archived = store.addCandidate(candidate("Run archived tests", List.of(), 0.7d));
        store.verify(high.id());
        store.verify(failing.id());
        store.verify(archived.id());
        store.recordUsage(high.id(), "high value tests", "goal", 0.9d, "keywords=high");
        store.feedback(failing.id(), ExperienceOutcome.FAILURE);
        store.markPromoted(high.id(), "notes/project/test_policy.md", "manual promote");
        store.archive(archived.id());

        ExperienceStore.GovernanceStats stats = store.stats();
        List<String> reviewReasons = store.review(10).stream().map(ExperienceStore.ReviewItem::reason).toList();

        assertEquals(2, stats.verified());
        assertEquals(1, stats.archived());
        assertEquals(1, stats.promoted());
        assertEquals(high.id(), stats.topUsed().get(0).entry().id());
        assertTrue(reviewReasons.contains("failureCount>successCount"), reviewReasons.toString());
    }

    private ExperienceEntry candidate(String title) {
        return candidate(title, List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"), 0.7d);
    }

    private ExperienceEntry candidate(String title, List<String> relatedFiles, double confidence) {
        return ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                title,
                "Run targeted tests after changing filesystem tools.",
                "When changing src/main/java/ricbot/tool/filesystem.",
                "Task summary suggested targeted tests.",
                "task_summary",
                "V3.5",
                relatedFiles,
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                confidence
        );
    }
}
