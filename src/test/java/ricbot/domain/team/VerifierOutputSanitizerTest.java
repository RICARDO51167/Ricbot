package ricbot.domain.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierOutputSanitizerTest {

    @Test
    void filtersJansiNativeAccessWarnings() {
        String output = """
                WARNING: A restricted method in java.lang.System has been called
                WARNING: java.lang.System::load has been called by org.fusesource.jansi.internal.JansiLoader in an unnamed module
                WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
                WARNING: Restricted methods will be blocked in a future release unless native access is enabled
                """;

        assertEquals("tests passed; noisy JVM warnings hidden",
                VerifierOutputSanitizer.display(output, VerificationResult.Status.PASS));
    }

    @Test
    void keepsRealTestFailureOutput() {
        String output = """
                [ERROR] COMPILATION ERROR :
                [ERROR] src/test/java/demo/AppTest.java:[10,5] cannot find symbol
                """;

        String sanitized = VerifierOutputSanitizer.display(output, VerificationResult.Status.REJECT);

        assertTrue(sanitized.contains("COMPILATION ERROR"), sanitized);
        assertTrue(sanitized.contains("cannot find symbol"), sanitized);
    }

    @Test
    void mixedOutputOnlyFiltersNoise() {
        String output = """
                WARNING: A restricted method in java.lang.System has been called
                WARNING: java.lang.System::load has been called by org.fusesource.jansi.internal.JansiLoader in an unnamed module
                [ERROR] Tests run: 1, Failures: 1
                AssertionFailedError
                """;

        String sanitized = VerifierOutputSanitizer.display(output, VerificationResult.Status.REJECT);

        assertTrue(sanitized.contains("[ERROR] Tests run"), sanitized);
        assertTrue(sanitized.contains("AssertionFailedError"), sanitized);
        assertTrue(!sanitized.contains("org.fusesource.jansi"), sanitized);
    }
}
