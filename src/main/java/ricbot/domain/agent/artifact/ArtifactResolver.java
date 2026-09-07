package ricbot.domain.agent.artifact;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves manifest-relative locations against the current runtime mount. */
public final class ArtifactResolver {
    private final Path root;
    public ArtifactResolver(Path runtimeWorkspace, String rootRunId) {
        Path workspace = runtimeWorkspace.toAbsolutePath().normalize();
        this.root = workspace.resolve(".ricbot/artifacts").resolve(safe(rootRunId)).normalize();
        if (!root.startsWith(workspace.resolve(".ricbot/artifacts").normalize())) {
            throw new IllegalArgumentException("artifact root escapes runtime workspace");
        }
    }
    public Path root() { return root; }
    public Path resolveBlob(String logicalPath) {
        if (logicalPath == null || !logicalPath.matches("blobs/[a-f0-9]{64}")) {
            throw new SecurityException("unsafe artifact blob path");
        }
        Path path = root.resolve(logicalPath).normalize();
        if (!path.startsWith(root) || Files.isSymbolicLink(path)) throw new SecurityException("artifact path escape");
        return path;
    }
    public Path resolveRef(String runId, String artifactId) {
        Path path = root.resolve("refs").resolve(safe(runId)).resolve(safe(artifactId) + ".json").normalize();
        if (!path.startsWith(root.resolve("refs"))) throw new SecurityException("artifact reference path escape");
        return path;
    }
    private static String safe(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank() || !clean.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("unsafe artifact identity");
        return clean;
    }
}
