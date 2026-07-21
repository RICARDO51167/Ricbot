package ricbot.infra.persistence;

import org.junit.jupiter.api.Test;
import ricbot.domain.agent.RunEventType;
import ricbot.domain.team.StepAuditEventType;
import ricbot.domain.trace.TraceEventType;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventClassificationCatalogTest {
    @Test
    void classifiesEveryTypedEventExactlyOnce() {
        assertCoverage(EventClassificationCatalog.RUN, RunEventType.values());
        assertCoverage(EventClassificationCatalog.TRACE, TraceEventType.values());
        assertCoverage(EventClassificationCatalog.STEP_AUDIT, StepAuditEventType.values());
    }

    @Test
    void keepsEvidenceImmutableAndConsoleEventsAsProjections() {
        assertEquals(EventClassificationCatalog.Classification.IMMUTABLE_ARTIFACT,
                EventClassificationCatalog.classification(EventClassificationCatalog.EVIDENCE, "DiffEvidence"));
        assertEquals(EventClassificationCatalog.Classification.IMMUTABLE_ARTIFACT,
                EventClassificationCatalog.classification(EventClassificationCatalog.EVIDENCE, "ExecutedTestEvidence"));
        assertEquals(EventClassificationCatalog.Classification.IMMUTABLE_ARTIFACT,
                EventClassificationCatalog.classification(EventClassificationCatalog.EVIDENCE, "ApprovalEvidence"));
        assertEquals(EventClassificationCatalog.Classification.IMMUTABLE_ARTIFACT,
                EventClassificationCatalog.classification(EventClassificationCatalog.EVIDENCE, "VerificationEvidence"));
        assertEquals(EventClassificationCatalog.Classification.READ_MODEL,
                EventClassificationCatalog.classification(EventClassificationCatalog.CONSOLE, "ConsoleEvent"));
    }

    private static void assertCoverage(String namespace, Enum<?>[] values) {
        long count = EventClassificationCatalog.entries().keySet().stream()
                .filter(key -> key.startsWith(namespace + ":"))
                .count();
        assertEquals(values.length, count);
        Arrays.stream(values).forEach(value ->
                EventClassificationCatalog.classification(namespace, value.name()));
    }
}
