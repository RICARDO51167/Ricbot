package ricbot.infra.persistence;

import org.junit.jupiter.api.Test;
import ricbot.domain.trace.TraceEventType;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EventClassificationCatalogTest {
    @Test
    void classifiesRetainedTypedProjections() {
        assertCoverage(EventClassificationCatalog.TRACE, TraceEventType.values());
    }
    private static void assertCoverage(String namespace, Enum<?>[] values) {
        assertEquals(values.length, EventClassificationCatalog.entries().keySet().stream()
                .filter(key -> key.startsWith(namespace + ":")).count());
        Arrays.stream(values).forEach(value -> EventClassificationCatalog.classification(namespace, value.name()));
    }
}
