package ricbot.domain.artifact;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface Verifier {
    VerificationResult verify(ArtifactDelta delta);

    record VerificationResult(boolean passed, List<String> findings, Map<String, Object> evidence) {
        public VerificationResult {
            findings = List.copyOf(findings != null ? findings : List.of());
            evidence = Map.copyOf(evidence != null ? evidence : Map.of());
        }
    }
}
