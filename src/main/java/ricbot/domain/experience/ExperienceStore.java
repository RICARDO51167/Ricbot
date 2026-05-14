package ricbot.domain.experience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExperienceStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final Path experienceDir;
    private final Path candidatesFile;
    private final Path verifiedFile;
    private final Path rejectedFile;

    public ExperienceStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.experienceDir = this.workspace.resolve("experience");
        this.candidatesFile = experienceDir.resolve("candidates.jsonl");
        this.verifiedFile = experienceDir.resolve("verified.jsonl");
        this.rejectedFile = experienceDir.resolve("rejected.jsonl");
        ensureLayout();
    }

    public ExperienceEntry addCandidate(ExperienceEntry entry) {
        ExperienceEntry candidate = entry.status() == ExperienceStatus.CANDIDATE
                ? entry
                : entry.withStatus(ExperienceStatus.CANDIDATE);
        ExperienceEntry duplicate = findSimilarCandidate(candidate);
        if (duplicate != null) {
            return duplicate;
        }
        append(candidatesFile, candidate);
        return candidate;
    }

    public List<ExperienceEntry> listCandidates() {
        List<ExperienceEntry> out = new ArrayList<>();
        for (ExperienceEntry entry : readAll(candidatesFile)) {
            if (entry.status() == ExperienceStatus.CANDIDATE) {
                out.add(entry);
            }
        }
        out.sort(Comparator.comparing(ExperienceEntry::createdAt, Comparator.nullsLast(String::compareTo)).reversed());
        return out;
    }

    public ExperienceEntry find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Path file : List.of(candidatesFile, verifiedFile, rejectedFile)) {
            for (ExperienceEntry entry : readAll(file)) {
                if (id.equals(entry.id())) {
                    return entry;
                }
            }
        }
        return null;
    }

    public ExperienceEntry verify(String id) {
        ExperienceEntry found = findInCandidates(id);
        if (found == null) {
            throw new IllegalArgumentException("experience candidate not found: " + id);
        }
        ExperienceEntry verified = found.withStatus(ExperienceStatus.VERIFIED);
        replaceInCandidates(verified);
        if (findInFile(verifiedFile, id) == null) {
            append(verifiedFile, verified);
        }
        return verified;
    }

    public ExperienceEntry reject(String id) {
        ExperienceEntry found = findInCandidates(id);
        if (found == null) {
            throw new IllegalArgumentException("experience candidate not found: " + id);
        }
        ExperienceEntry rejected = found.withStatus(ExperienceStatus.REJECTED);
        replaceInCandidates(rejected);
        if (findInFile(rejectedFile, id) == null) {
            append(rejectedFile, rejected);
        }
        return rejected;
    }

    public Path candidatesFile() {
        return candidatesFile;
    }

    public Path verifiedFile() {
        return verifiedFile;
    }

    public Path rejectedFile() {
        return rejectedFile;
    }

    private ExperienceEntry findSimilarCandidate(ExperienceEntry candidate) {
        String key = similarityKey(candidate);
        for (ExperienceEntry existing : listCandidates()) {
            if (similarityKey(existing).equals(key)) {
                return existing;
            }
        }
        return null;
    }

    private String similarityKey(ExperienceEntry entry) {
        return entry.type().name() + "|"
                + normalize(entry.title()) + "|"
                + normalize(entry.whenToApply());
    }

    private String normalize(String value) {
        return value != null
                ? value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim()
                : "";
    }

    private ExperienceEntry findInCandidates(String id) {
        for (ExperienceEntry entry : readAll(candidatesFile)) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        return null;
    }

    private ExperienceEntry findInFile(Path file, String id) {
        for (ExperienceEntry entry : readAll(file)) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        return null;
    }

    private void replaceInCandidates(ExperienceEntry updated) {
        List<ExperienceEntry> entries = readAll(candidatesFile);
        List<ExperienceEntry> next = new ArrayList<>();
        boolean replaced = false;
        for (ExperienceEntry entry : entries) {
            if (entry.id().equals(updated.id())) {
                next.add(updated);
                replaced = true;
            } else {
                next.add(entry);
            }
        }
        if (!replaced) {
            next.add(updated);
        }
        writeAll(candidatesFile, next);
    }

    private List<ExperienceEntry> readAll(Path file) {
        ensureLayout();
        if (!Files.exists(file)) {
            return List.of();
        }
        List<ExperienceEntry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = MAPPER.readValue(line, MAP_TYPE);
                ExperienceEntry entry = ExperienceEntry.fromMap(new LinkedHashMap<>(row));
                if (entry != null) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("read experience file failed: " + file, e);
        }
        return out;
    }

    private void append(Path file, ExperienceEntry entry) {
        ensureLayout();
        try {
            Files.writeString(
                    file,
                    MAPPER.writeValueAsString(entry.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new RuntimeException("write experience file failed: " + file, e);
        }
    }

    private void writeAll(Path file, List<ExperienceEntry> entries) {
        ensureLayout();
        try {
            StringBuilder sb = new StringBuilder();
            for (ExperienceEntry entry : entries != null ? entries : List.<ExperienceEntry>of()) {
                sb.append(MAPPER.writeValueAsString(entry.toMap())).append("\n");
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("write experience file failed: " + file, e);
        }
    }

    private void ensureLayout() {
        try {
            Files.createDirectories(experienceDir);
            for (Path file : List.of(candidatesFile, verifiedFile, rejectedFile)) {
                if (!Files.exists(file)) {
                    Files.writeString(file, "", StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("create experience layout failed: " + experienceDir, e);
        }
    }
}
