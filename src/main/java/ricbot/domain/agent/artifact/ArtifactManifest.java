package ricbot.domain.agent.artifact;

/** Versioned, relocatable artifact manifest. Physical workspace paths are never persisted. */
public record ArtifactManifest(int schemaVersion, ArtifactRef reference, String blobPath) {
    public static final int SCHEMA_VERSION = 2;
    public ArtifactManifest {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported artifact manifest: " + schemaVersion);
        if (reference == null) throw new IllegalArgumentException("reference is required");
        if (blobPath == null || !blobPath.matches("blobs/[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid logical blob path");
        }
    }
}
