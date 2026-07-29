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
import java.util.UUID;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Run-scoped, read-only artifact store. It never accepts arbitrary filesystem paths. */
public final class ArtifactStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Pattern URI = Pattern.compile("artifact://([^/]+)/([A-Za-z0-9._-]+)");
    private final Path root;
    private final String rootRunId;
    private final String runId;
    private final String taskId;

    public ArtifactStore(Path runtimeWorkspace, String rootRunId, String runId, String taskId) {
        Path workspace = runtimeWorkspace.toAbsolutePath().normalize();
        this.rootRunId = safeSegment(rootRunId != null && !rootRunId.isBlank() ? rootRunId : runId);
        this.runId = safeSegment(runId);
        this.taskId = taskId != null ? taskId.trim() : "";
        this.root = workspace.resolve(".ricbot/artifacts").resolve(this.rootRunId).resolve(this.runId).normalize();
        if (!root.startsWith(workspace.resolve(".ricbot/artifacts").normalize())) {
            throw new IllegalArgumentException("artifact root escapes runtime workspace");
        }
    }

    public ArtifactRef writeText(String content, String source, int summaryChars) throws Exception {
        String text = content != null ? content : "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        String id = "art-" + digest.substring(0, 16) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = root.resolve(id);
        Files.createDirectories(directory);
        harden(directory);
        Path contentFile = directory.resolve("content.txt");
        Path temp = directory.resolve("content.tmp");
        Files.write(temp, bytes);
        harden(temp);
        move(temp, contentFile);
        String preview = text.substring(0, Math.min(Math.max(0, summaryChars), text.length()));
        ArtifactRef ref = new ArtifactRef(id, "artifact://" + runId + "/" + id,
                contentFile.toString(), digest, bytes.length, text.length(), "text/plain; charset=utf-8",
                preview, source, rootRunId, runId, taskId, Instant.now());
        Path manifestTemp = directory.resolve("manifest.tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestTemp.toFile(), ref);
        harden(manifestTemp);
        move(manifestTemp, directory.resolve("manifest.json"));
        return ref;
    }

    public String read(String uri, int offsetChars, int limitChars) throws Exception {
        ArtifactRef ref = require(uri);
        String content = Files.readString(Path.of(ref.path()), StandardCharsets.UTF_8);
        verify(ref, content);
        int offset = Math.max(0, offsetChars);
        if (offset >= content.length()) return "";
        int limit = Math.max(1, limitChars);
        return content.substring(offset, Math.min(content.length(), offset + limit));
    }

    public List<String> grep(String uri, String expression, int maxMatches) throws Exception {
        ArtifactRef ref = require(uri);
        String content = Files.readString(Path.of(ref.path()), StandardCharsets.UTF_8);
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
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            List<ArtifactRef> refs = new ArrayList<>();
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path manifest = path.resolve("manifest.json");
                if (Files.isRegularFile(manifest)) refs.add(MAPPER.readValue(manifest.toFile(), ArtifactRef.class));
            }
            return List.copyOf(refs);
        }
    }

    private ArtifactRef require(String uri) throws Exception {
        Matcher matcher = URI.matcher(uri != null ? uri.trim() : "");
        if (!matcher.matches() || !runId.equals(matcher.group(1))) {
            throw new SecurityException("artifact is not owned by current run");
        }
        String id = safeSegment(matcher.group(2));
        Path manifest = root.resolve(id).resolve("manifest.json").normalize();
        if (!manifest.startsWith(root) || !Files.isRegularFile(manifest)) throw new IllegalArgumentException("artifact not found");
        ArtifactRef ref = MAPPER.readValue(manifest.toFile(), ArtifactRef.class);
        if (!runId.equals(ref.runId()) || !id.equals(ref.artifactId())) throw new SecurityException("artifact manifest ownership mismatch");
        Path content = Path.of(ref.path()).toAbsolutePath().normalize();
        if (!content.startsWith(root.resolve(id)) || Files.isSymbolicLink(content)) throw new SecurityException("unsafe artifact content path");
        return ref;
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
