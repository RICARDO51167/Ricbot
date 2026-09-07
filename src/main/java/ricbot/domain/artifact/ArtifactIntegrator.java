package ricbot.domain.artifact;

import java.util.Map;
import java.util.Optional;

/** Peripheral port; integrations must invoke it through the Effect Runtime. */
@FunctionalInterface
public interface ArtifactIntegrator {
    IntegrationResult integrate(ArtifactDelta delta);

    default Optional<IntegrationResult> reconcile(ArtifactDelta delta, Map<String, Object> evidence) {
        return Optional.empty();
    }

    record IntegrationResult(String resultReference, Map<String, Object> evidence) {
        public IntegrationResult { evidence = Map.copyOf(evidence != null ? evidence : Map.of()); }
    }
}
