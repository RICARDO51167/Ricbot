package ricbot.domain.verification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Trusted, baseline-owned verification commands. Model plans may reference ids only.
 * @author rcd*/
public record VerificationProfile(int version, String digest, List<Check> checks) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public VerificationProfile {
        if (version != 1) throw new IllegalArgumentException("unsupported verification profile version");
        digest = digest != null ? digest : "";
        checks = checks != null ? List.copyOf(checks) : List.of();
    }

    public record Check(String id, VerificationCheckResult.Stage stage, String command,
                        int timeoutSeconds, boolean required) {
        public Check {
            id = requireText(id, "check id"); command = requireText(command, "check command");
            stage = stage != null ? stage : VerificationCheckResult.Stage.ACCEPTANCE;
            timeoutSeconds = Math.max(1, Math.min(1800, timeoutSeconds > 0 ? timeoutSeconds : 300));
        }
    }

    public static VerificationProfile load(Path trustedWorkspace) {
        Path root = trustedWorkspace.toAbsolutePath().normalize();
        Path configured = root.resolve(".ricbot/verification.json");
        if (Files.isRegularFile(configured)) {
            try {
                byte[] bytes = Files.readAllBytes(configured);
                Map<String, Object> raw = MAPPER.readValue(bytes, new TypeReference<>() {});
                List<Check> checks = new ArrayList<>();
                add(checks, raw.get("compile"), VerificationCheckResult.Stage.COMPILE);
                add(checks, raw.get("test"), VerificationCheckResult.Stage.TEST);
                add(checks, raw.get("acceptance"), VerificationCheckResult.Stage.ACCEPTANCE);
                return new VerificationProfile(number(raw.get("version"), 1), sha256(bytes), checks);
            } catch (Exception e) {
                throw new IllegalStateException("invalid trusted verification profile: " + configured, e);
            }
        }
        List<Check> detected = detect(root);
        String canonical = detected.stream().map(check -> check.id() + "\n" + check.command()).reduce("", String::concat);
        return new VerificationProfile(1, sha256(canonical.getBytes(StandardCharsets.UTF_8)), detected);
    }

    private static void add(List<Check> out, Object value, VerificationCheckResult.Stage stage) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException(stage + " check must be an object");
            out.add(new Check(text(raw.get("id")), stage, text(raw.get("command")),
                    number(raw.get("timeoutSeconds"), 300), !Boolean.FALSE.equals(raw.get("required"))));
        }
    }

    private static List<Check> detect(Path root) {
        if (Files.isRegularFile(root.resolve("mvnw"))) return List.of(
                new Check("compile", VerificationCheckResult.Stage.COMPILE, "sh ./mvnw -q -DskipTests package", 900, true),
                new Check("test", VerificationCheckResult.Stage.TEST, "sh ./mvnw -q test", 1200, true));
        if (Files.isRegularFile(root.resolve("pom.xml"))) return List.of(
                new Check("compile", VerificationCheckResult.Stage.COMPILE, "mvn -q -DskipTests package", 900, true),
                new Check("test", VerificationCheckResult.Stage.TEST, "mvn -q test", 1200, true));
        if (Files.isRegularFile(root.resolve("gradlew"))) return List.of(
                new Check("compile", VerificationCheckResult.Stage.COMPILE, "sh ./gradlew classes", 900, true),
                new Check("test", VerificationCheckResult.Stage.TEST, "sh ./gradlew test", 1200, true));
        if (Files.isRegularFile(root.resolve("package.json"))) return List.of(
                new Check("compile", VerificationCheckResult.Stage.COMPILE, "npm run build --if-present", 900, true),
                new Check("test", VerificationCheckResult.Stage.TEST, "npm test -- --run", 1200, true));
        if (Files.isRegularFile(root.resolve("go.mod"))) return List.of(
                new Check("compile", VerificationCheckResult.Stage.COMPILE, "go test -run '^$' ./...", 900, true),
                new Check("test", VerificationCheckResult.Stage.TEST, "go test ./...", 1200, true));
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) return List.of(
                new Check("compile", VerificationCheckResult.Stage.COMPILE, "cargo check", 900, true),
                new Check("test", VerificationCheckResult.Stage.TEST, "cargo test", 1200, true));
        return List.of();
    }

    private static int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static String text(Object value) { return value != null ? String.valueOf(value).trim() : ""; }
    private static String requireText(String value, String field) {
        String clean = text(value); if (clean.isBlank()) throw new IllegalArgumentException(field + " is required"); return clean;
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
