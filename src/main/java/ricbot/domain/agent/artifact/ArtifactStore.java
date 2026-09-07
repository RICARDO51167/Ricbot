package ricbot.domain.agent.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Run-scoped, read-only artifact store. It never accepts arbitrary filesystem paths. */
public final class ArtifactStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Pattern URI = Pattern.compile("artifact://([^/]+)/([A-Za-z0-9._-]+)");
    public static final long DEFAULT_MAX_ARTIFACT_BYTES = 67_108_864L;
    private final ArtifactResolver resolver;
    private final Path refsRoot;
    private final String rootRunId;
    private final String runId;
    private final String taskId;

    public ArtifactStore(Path runtimeWorkspace, String rootRunId, String runId, String taskId) {
        this.rootRunId = safeSegment(rootRunId != null && !rootRunId.isBlank() ? rootRunId : runId);
        this.runId = safeSegment(runId);
        this.taskId = taskId != null ? taskId.trim() : "";
        this.resolver = new ArtifactResolver(runtimeWorkspace, this.rootRunId);
        this.refsRoot = resolver.root().resolve("refs").resolve(this.runId);
    }

    public ArtifactRef writeText(String content, String source, int summaryChars) throws Exception {
        return writeText(content, source, summaryChars, DEFAULT_MAX_ARTIFACT_BYTES);
    }

    public ArtifactRef writeText(String content, String source, int summaryChars, long maxBytes) throws Exception {
        String text = content != null ? content : "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Math.max(1, maxBytes)) {
            throw new IllegalArgumentException("artifact exceeds configured maximum: " + bytes.length + " bytes");
        }
        String digest = sha256(bytes);
        String id = "art-" + digest.substring(0, 24);
        String blobPath = "blobs/" + digest;
        Path contentFile = resolver.resolveBlob(blobPath);
        Files.createDirectories(contentFile.getParent());
        harden(contentFile.getParent());
        if (!Files.exists(contentFile)) {
            Path temp = Files.createTempFile(contentFile.getParent(), digest, ".tmp");
            Files.write(temp, bytes);
            harden(temp);
            move(temp, contentFile);
        } else if (!digest.equals(sha256(Files.readAllBytes(contentFile)))) {
            throw new IllegalStateException("existing artifact blob failed integrity check");
        }
        String preview = text.substring(0, Math.min(Math.max(0, summaryChars), text.length()));
        ArtifactRef ref = new ArtifactRef(id, "artifact://" + runId + "/" + id,
                blobPath, digest, bytes.length, text.length(), "text/plain; charset=utf-8",
                preview, source, rootRunId, runId, taskId, Instant.now());
        Files.createDirectories(refsRoot);
        harden(refsRoot);
        Path manifest = resolver.resolveRef(runId, id);
        Path manifestTemp = Files.createTempFile(refsRoot, id, ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestTemp.toFile(),
                new ArtifactManifest(ArtifactManifest.SCHEMA_VERSION, ref, blobPath));
        harden(manifestTemp);
        move(manifestTemp, manifest);
        return ref;
    }

    public String read(String uri, int offsetChars, int limitChars) throws Exception {
        ArtifactRef ref = require(uri);
        String content = Files.readString(resolver.resolveBlob(ref.path()), StandardCharsets.UTF_8);
        verify(ref, content);
        int offset = Math.max(0, offsetChars);
        if (offset >= content.length()) return "";
        int limit = Math.max(1, limitChars);
        return content.substring(offset, Math.min(content.length(), offset + limit));
    }

    public List<String> grep(String uri, String expression, int maxMatches) throws Exception {
        ArtifactRef ref = require(uri);
        String content = Files.readString(resolver.resolveBlob(ref.path()), StandardCharsets.UTF_8);
        verify(ref, content);
        Pattern pattern = Pattern.compile(expression != null ? expression : "");
        List<String> matches = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length && matches.size() < Math.max(1, maxMatches); index++) {
            if (pattern.matcher(lines[index]).find()) matches.add((index + 1) + ":" + lines[index]);
        }
        return List.copyOf(matches);
    }

    public List<ArtifactRef> list() throws Exception {
        if (!Files.isDirectory(refsRoot)) return List.of();
        try (var paths = Files.list(refsRoot)) {
            List<ArtifactRef> refs = new ArrayList<>();
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json")) {
                    refs.add(readManifest(path).reference());
                }
            }
            return List.copyOf(refs);
        }
    }

    /** Resume-time integrity verification using only relocatable reference fields. */
    public static void verifyReference(Path runtimeWorkspace, Object raw) {
        try {
            ArtifactRef ref = raw instanceof ArtifactRef value ? value : MAPPER.convertValue(raw, ArtifactRef.class);
            String root = ref.rootRunId() != null && !ref.rootRunId().isBlank() ? ref.rootRunId() : ref.runId();
            ArtifactResolver resolver = new ArtifactResolver(runtimeWorkspace, root);
            Path blob = resolver.resolveBlob(ref.path());
            if (!Files.isRegularFile(blob) || Files.isSymbolicLink(blob))
                throw new IllegalStateException("artifact blob is missing: " + ref.artifactId());
            byte[] bytes = Files.readAllBytes(blob);
            if (bytes.length != ref.byteSize()) throw new IllegalStateException("artifact byte size mismatch: " + ref.artifactId());
            if (!sha256(bytes).equals(ref.sha256())) throw new IllegalStateException("artifact integrity check failed: " + ref.artifactId());
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("cannot validate artifact reference", failure); }
    }

    private ArtifactRef require(String uri) throws Exception {
        Matcher matcher = URI.matcher(uri != null ? uri.trim() : "");
        if (!matcher.matches() || !runId.equals(matcher.group(1))) {
            throw new SecurityException("artifact is not owned by current run");
        }
        String id = safeSegment(matcher.group(2));
        Path manifest = resolver.resolveRef(runId, id);
        if (!Files.isRegularFile(manifest) || Files.isSymbolicLink(manifest)) throw new IllegalArgumentException("artifact not found");
        ArtifactManifest stored = readManifest(manifest);
        ArtifactRef ref = stored.reference();
        if (!runId.equals(ref.runId()) || !id.equals(ref.artifactId())) throw new SecurityException("artifact manifest ownership mismatch");
        Path content = resolver.resolveBlob(stored.blobPath());
        if (!Files.isRegularFile(content)) throw new IllegalStateException("artifact blob is missing");
        if (Files.size(content) != ref.byteSize()) throw new IllegalStateException("artifact byte size mismatch");
        return ref;
    }

    private static ArtifactManifest readManifest(Path path) throws Exception {
        return MAPPER.readValue(path.toFile(), ArtifactManifest.class);
    }

    private static void verify(ArtifactRef ref, String content) {
        String actual = sha256(content.getBytes(StandardCharsets.UTF_8));
        if (!actual.equals(ref.sha256())) throw new IllegalStateException("artifact integrity check failed: " + ref.artifactId());
    }
    private static void move(Path source, Path target) throws Exception {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
        harden(target);
    }
    private static void harden(Path path) {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path)
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) { }
    }
    private static String safeSegment(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank() || !clean.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("unsafe artifact identity");
        return clean;
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception failure) { throw new IllegalStateException("cannot calculate artifact digest", failure); }
    }
}
