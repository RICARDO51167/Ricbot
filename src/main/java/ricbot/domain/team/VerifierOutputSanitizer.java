package ricbot.domain.team;

import java.util.ArrayList;
import java.util.List;

public final class VerifierOutputSanitizer {
    private VerifierOutputSanitizer() {
    }

    public static String display(String output, VerificationResult.Status status) {
        SanitizedOutput sanitized = sanitize(output);
        if (sanitized.text().isBlank() && status == VerificationResult.Status.PASS) {
            return sanitized.hiddenNoise() ? "tests passed; noisy JVM warnings hidden" : "tests passed";
        }
        return sanitized.text();
    }

    public static SanitizedOutput sanitize(String output) {
        List<String> kept = new ArrayList<>();
        boolean hiddenNoise = false;
        for (String line : (output != null ? output : "").split("\\R")) {
            if (isNoise(line)) {
                hiddenNoise = true;
                continue;
            }
            if (!line.isBlank() || !kept.isEmpty()) {
                kept.add(line);
            }
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) {
            kept.remove(kept.size() - 1);
        }
        return new SanitizedOutput(String.join("\n", kept).trim(), hiddenNoise);
    }

    private static boolean isNoise(String line) {
        String value = line != null ? line.trim() : "";
        return value.equals("WARNING: A restricted method in java.lang.System has been called")
                || value.startsWith("WARNING: java.lang.System::load has been called by org.fusesource.jansi.internal.JansiLoader")
                || value.startsWith("WARNING: Use --enable-native-access=ALL-UNNAMED")
                || value.startsWith("WARNING: Restricted methods will be blocked in a future release unless native access is enabled");
    }

    public record SanitizedOutput(String text, boolean hiddenNoise) {
    }
}
