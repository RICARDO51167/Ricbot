package ricbot.domain.experience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ExperienceStore {
    private static final Logger log = LoggerFactory.getLogger(ExperienceStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final Path experienceDir;
    private final Path candidatesFile;
    private final Path verifiedFile;
    private final Path rejectedFile;
    private final Path usageTraceFile;

    public ExperienceStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.experienceDir = this.workspace.resolve("experience");
        this.candidatesFile = experienceDir.resolve("candidates.jsonl");
        this.verifiedFile = experienceDir.resolve("verified.jsonl");
        this.rejectedFile = experienceDir.resolve("rejected.jsonl");
        this.usageTraceFile = experienceDir.resolve("usage_trace.jsonl");
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
        for (Path file : List.of(verifiedFile, rejectedFile, candidatesFile)) {
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

    public List<ScoredExperience> searchVerified(String query, List<String> relatedFiles, int limit) {
        int actualLimit = limit > 0 ? limit : 3;
        Set<String> queryTokens = tokenize(query);
        List<String> normalizedFiles = normalizeFiles(relatedFiles);
        List<ScoredExperience> scored = new ArrayList<>();
        for (ExperienceEntry entry : readAll(verifiedFile)) {
            if (entry.status() != ExperienceStatus.VERIFIED) {
                continue;
            }
            double textScore = textScore(entry, queryTokens);
            double pathScore = pathScore(entry.relatedFiles(), normalizedFiles);
            if (textScore <= 0d && pathScore <= 0d) {
                continue;
            }
            double effectiveConfidence = effectiveConfidence(entry);
            double score = textScore + pathScore + (effectiveConfidence * 0.35d);
            if (score <= 0d) {
                continue;
            }
            scored.add(new ScoredExperience(
                    entry,
                    Math.min(1d, score),
                    effectiveConfidence,
                    matchReason(entry, queryTokens, normalizedFiles)
            ));
        }
        scored.sort(Comparator
                .comparingDouble(ScoredExperience::score)
                .thenComparing(ScoredExperience::effectiveConfidence)
                .reversed());
        return scored.stream().limit(actualLimit).toList();
    }

    public ExperienceUsageTrace recordUsage(
            String experienceId,
            String query,
            String taskGoal,
            double relevanceScore,
            String evidence
    ) {
        return recordUsage(experienceId, "", query, taskGoal, relevanceScore, evidence);
    }

    public ExperienceUsageTrace recordUsage(
            String experienceId,
            String sessionId,
            String query,
            String taskGoal,
            double relevanceScore,
            String evidence
    ) {
        ExperienceEntry entry = findInFile(verifiedFile, experienceId);
        if (entry == null || entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalArgumentException("verified experience not found: " + experienceId);
        }
        ExperienceUsageTrace trace = ExperienceUsageTrace.unknown(
                experienceId,
                sessionId,
                taskGoal,
                query,
                relevanceScore,
                evidence
        );
        appendUsage(trace);
        replaceInFile(verifiedFile, entry.withUsage(trace.usedAt()));
        return trace;
    }

    public ExperienceEntry feedback(String experienceId, ExperienceOutcome outcome) {
        ExperienceOutcome actual = outcome != null ? outcome : ExperienceOutcome.UNKNOWN;
        if (actual == ExperienceOutcome.UNKNOWN) {
            throw new IllegalArgumentException("feedback outcome must be success, failure, or neutral");
        }
        ExperienceEntry entry = findInFile(verifiedFile, experienceId);
        if (entry == null || entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalArgumentException("verified experience not found: " + experienceId);
        }
        ExperienceEntry updated = entry.withFeedback(actual);
        replaceInFile(verifiedFile, updated);
        updateLatestUsageOutcome(experienceId, actual);
        return updated;
    }

    public List<ExperienceUsageTrace> listUsage(String experienceId) {
        List<ExperienceUsageTrace> out = new ArrayList<>();
        for (ExperienceUsageTrace trace : readUsageAll()) {
            if (experienceId == null || experienceId.isBlank() || experienceId.equals(trace.experienceId())) {
                out.add(trace);
            }
        }
        out.sort(Comparator.comparing(ExperienceUsageTrace::usedAt, Comparator.nullsLast(String::compareTo)).reversed());
        return out;
    }

    public List<ScoredExperience> listStaleVerified() {
        List<ScoredExperience> out = new ArrayList<>();
        for (ExperienceEntry entry : readAll(verifiedFile)) {
            if (entry.status() != ExperienceStatus.VERIFIED) {
                continue;
            }
            double effectiveConfidence = effectiveConfidence(entry);
            if (effectiveConfidence < 0.3d
                    || entry.failureCount() > entry.successCount()
                    || staleDays(entry) >= staleThresholdDays()) {
                out.add(new ScoredExperience(entry, effectiveConfidence, effectiveConfidence, staleReason(entry, effectiveConfidence)));
            }
        }
        out.sort(Comparator
                .comparingDouble(ScoredExperience::effectiveConfidence)
                .thenComparing(item -> item.entry().failureCount(), Comparator.reverseOrder()));
        return out;
    }

    public ExperienceEntry archive(String id) {
        ExperienceEntry entry = findInFile(verifiedFile, id);
        if (entry == null || entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalArgumentException("verified experience not found: " + id);
        }
        ExperienceEntry archived = entry.withStatus(ExperienceStatus.ARCHIVED);
        replaceInFile(verifiedFile, archived);
        return archived;
    }

    public ExperienceEntry restore(String id) {
        ExperienceEntry entry = findInFile(verifiedFile, id);
        if (entry == null || entry.status() != ExperienceStatus.ARCHIVED) {
            throw new IllegalArgumentException("archived experience not found: " + id);
        }
        ExperienceEntry restored = entry.withStatus(ExperienceStatus.VERIFIED);
        replaceInFile(verifiedFile, restored);
        return restored;
    }

    public ExperienceEntry markPromoted(String id, String promotedTo, String governanceNote) {
        ExperienceEntry entry = findInFile(verifiedFile, id);
        if (entry == null || entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalArgumentException("verified experience not found: " + id);
        }
        ExperienceEntry promoted = entry.withPromotion(promotedTo, governanceNote);
        replaceInFile(verifiedFile, promoted);
        return promoted;
    }

    public ExperienceEntry demote(String id) {
        ExperienceEntry entry = findInFile(verifiedFile, id);
        if (entry == null || entry.status() != ExperienceStatus.VERIFIED) {
            throw new IllegalArgumentException("verified experience not found: " + id);
        }
        ExperienceEntry demoted = entry.withDemotion("demoted from project playbook");
        replaceInFile(verifiedFile, demoted);
        return demoted;
    }

    public GovernanceStats stats() {
        int candidates = 0;
        int verified = 0;
        int rejected = 0;
        int archived = 0;
        int promoted = 0;
        for (ExperienceEntry entry : readAll(candidatesFile)) {
            if (entry.status() == ExperienceStatus.CANDIDATE) {
                candidates++;
            }
        }
        for (ExperienceEntry entry : readAll(rejectedFile)) {
            if (entry.status() == ExperienceStatus.REJECTED) {
                rejected++;
            }
        }
        for (ExperienceEntry entry : readAll(verifiedFile)) {
            if (entry.status() == ExperienceStatus.VERIFIED) {
                verified++;
                if (!entry.promotedTo().isBlank()) {
                    promoted++;
                }
            } else if (entry.status() == ExperienceStatus.ARCHIVED) {
                archived++;
            }
        }
        return new GovernanceStats(candidates, verified, rejected, archived, promoted, topUsed(5), review(10));
    }

    public List<ReviewItem> review(int limit) {
        List<ReviewItem> out = new ArrayList<>();
        for (ExperienceEntry entry : readAll(verifiedFile)) {
            if (entry.status() != ExperienceStatus.VERIFIED) {
                continue;
            }
            double effectiveConfidence = effectiveConfidence(entry);
            if (effectiveConfidence >= 0.75d && entry.promotedTo().isBlank()) {
                out.add(new ReviewItem(entry, "high_effective_confidence_unpromoted", effectiveConfidence));
            }
            if (entry.failureCount() > entry.successCount()) {
                out.add(new ReviewItem(entry, "failureCount>successCount", effectiveConfidence));
            }
            if (effectiveConfidence < 0.3d || staleDays(entry) >= staleThresholdDays()) {
                out.add(new ReviewItem(entry, "stale_or_low_confidence", effectiveConfidence));
            }
            if (entry.title().isBlank() || entry.content().isBlank() || entry.whenToApply().isBlank()) {
                out.add(new ReviewItem(entry, "incomplete_verified_experience", effectiveConfidence));
            }
        }
        out.sort(Comparator.comparingDouble(ReviewItem::effectiveConfidence).reversed());
        return out.stream().limit(Math.max(1, limit)).toList();
    }

    public double effectiveConfidence(ExperienceEntry entry) {
        if (entry == null) {
            return 0d;
        }
        double feedbackBoost = Math.min(0.20d, entry.successCount() * 0.05d);
        double failurePenalty = Math.min(0.45d, entry.failureCount() * 0.15d);
        double stalePenalty = stalePenalty(entry);
        return clamp(entry.confidence() + feedbackBoost - failurePenalty - stalePenalty, 0d, 1d);
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

    public Path usageTraceFile() {
        return usageTraceFile;
    }

    private List<UsageCount> topUsed(int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ExperienceUsageTrace trace : readUsageAll()) {
            counts.merge(trace.experienceId(), 1, Integer::sum);
        }
        List<UsageCount> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            ExperienceEntry experience = find(entry.getKey());
            if (experience != null) {
                out.add(new UsageCount(experience, entry.getValue()));
            }
        }
        out.sort(Comparator.comparingInt(UsageCount::count).reversed());
        return out.stream().limit(Math.max(1, limit)).toList();
    }

    private ExperienceEntry findSimilarCandidate(ExperienceEntry candidate) {
        ExperienceEntry sourceDuplicate = findSourceRefDuplicate(candidate);
        if (sourceDuplicate != null) {
            return sourceDuplicate;
        }
        String key = similarityKey(candidate);
        for (ExperienceEntry existing : listCandidates()) {
            if (similarityKey(existing).equals(key)) {
                return existing;
            }
        }
        return null;
    }

    private ExperienceEntry findSourceRefDuplicate(ExperienceEntry candidate) {
        if (candidate == null || candidate.sourceRef().isBlank()) {
            return null;
        }
        String sourceRef = normalize(candidate.sourceRef());
        String title = normalize(candidate.title());
        for (Path file : List.of(candidatesFile, verifiedFile, rejectedFile)) {
            for (ExperienceEntry existing : readAll(file)) {
                if (existing.type() == candidate.type()
                        && sourceRef.equals(normalize(existing.sourceRef()))
                        && title.equals(normalize(existing.title()))) {
                    return existing;
                }
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

    private void replaceInFile(Path file, ExperienceEntry updated) {
        List<ExperienceEntry> entries = readAll(file);
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
        writeAll(file, next);
    }

    private List<ExperienceEntry> readAll(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<ExperienceEntry> out = new ArrayList<>();
        try {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> row = MAPPER.readValue(line, MAP_TYPE);
                    ExperienceEntry entry = ExperienceEntry.fromMap(new LinkedHashMap<>(row));
                    if (entry != null) {
                        out.add(entry);
                    }
                } catch (Exception e) {
                    log.warn("skip invalid experience entry at {}:{}", file, lineNumber, e);
                }
            }
        } catch (IOException e) {
            log.warn("skip experience file due to read failure: {}", file, e);
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

    private void appendUsage(ExperienceUsageTrace trace) {
        ensureLayout();
        try {
            Files.writeString(
                    usageTraceFile,
                    MAPPER.writeValueAsString(trace.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new RuntimeException("write experience usage trace failed: " + usageTraceFile, e);
        }
    }

    private List<ExperienceUsageTrace> readUsageAll() {
        if (!Files.exists(usageTraceFile)) {
            return List.of();
        }
        List<ExperienceUsageTrace> out = new ArrayList<>();
        try {
            int lineNumber = 0;
            for (String line : Files.readAllLines(usageTraceFile, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> row = MAPPER.readValue(line, MAP_TYPE);
                    ExperienceUsageTrace trace = ExperienceUsageTrace.fromMap(new LinkedHashMap<>(row));
                    if (trace != null && !trace.experienceId().isBlank()) {
                        out.add(trace);
                    }
                } catch (Exception e) {
                    log.warn("skip invalid experience usage trace at {}:{}", usageTraceFile, lineNumber, e);
                }
            }
        } catch (IOException e) {
            log.warn("skip experience usage trace due to read failure: {}", usageTraceFile, e);
        }
        return out;
    }

    private void writeUsageAll(List<ExperienceUsageTrace> traces) {
        ensureLayout();
        try {
            StringBuilder sb = new StringBuilder();
            for (ExperienceUsageTrace trace : traces != null ? traces : List.<ExperienceUsageTrace>of()) {
                sb.append(MAPPER.writeValueAsString(trace.toMap())).append("\n");
            }
            Files.writeString(usageTraceFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("write experience usage trace failed: " + usageTraceFile, e);
        }
    }

    private void updateLatestUsageOutcome(String experienceId, ExperienceOutcome outcome) {
        List<ExperienceUsageTrace> traces = readUsageAll();
        for (int i = traces.size() - 1; i >= 0; i--) {
            ExperienceUsageTrace trace = traces.get(i);
            if (experienceId.equals(trace.experienceId()) && trace.outcome() == ExperienceOutcome.UNKNOWN) {
                traces.set(i, trace.withOutcome(outcome, "feedback " + outcome.name().toLowerCase(Locale.ROOT)));
                writeUsageAll(traces);
                return;
            }
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
            for (Path file : List.of(candidatesFile, verifiedFile, rejectedFile, usageTraceFile)) {
                if (!Files.exists(file)) {
                    Files.writeString(file, "", StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("create experience layout failed: " + experienceDir, e);
        }
    }

    private double textScore(ExperienceEntry entry, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0d;
        }
        Set<String> contentTokens = tokenize(entry.title() + " " + entry.content() + " "
                + entry.whenToApply() + " " + entry.evidence());
        if (contentTokens.isEmpty()) {
            return 0d;
        }
        long hits = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) hits / Math.max(1, queryTokens.size()) * 0.55d;
    }

    private double pathScore(List<String> entryFiles, List<String> relatedFiles) {
        if (entryFiles == null || entryFiles.isEmpty() || relatedFiles == null || relatedFiles.isEmpty()) {
            return 0d;
        }
        for (String entryFile : normalizeFiles(entryFiles)) {
            for (String relatedFile : relatedFiles) {
                if (entryFile.equals(relatedFile) || entryFile.contains(relatedFile) || relatedFile.contains(entryFile)
                        || sameTopModule(entryFile, relatedFile)) {
                    return 0.45d;
                }
            }
        }
        return 0d;
    }

    private String matchReason(ExperienceEntry entry, Set<String> queryTokens, List<String> relatedFiles) {
        List<String> reasons = new ArrayList<>();
        List<String> matched = matchedKeywords(entry, queryTokens);
        if (!matched.isEmpty()) {
            reasons.add("keywords=" + String.join(",", matched.stream().limit(5).toList()));
        }
        String file = matchedRelatedFile(entry.relatedFiles(), relatedFiles);
        if (!file.isBlank()) {
            reasons.add("relatedFile=" + file);
        }
        return reasons.isEmpty() ? "confidence" : String.join("; ", reasons);
    }

    private List<String> matchedKeywords(ExperienceEntry entry, Set<String> queryTokens) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return List.of();
        }
        Set<String> contentTokens = tokenize(entry.title() + " " + entry.content() + " "
                + entry.whenToApply() + " " + entry.evidence());
        List<String> out = new ArrayList<>();
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                out.add(token);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private String matchedRelatedFile(List<String> entryFiles, List<String> relatedFiles) {
        if (entryFiles == null || entryFiles.isEmpty() || relatedFiles == null || relatedFiles.isEmpty()) {
            return "";
        }
        for (String entryFile : normalizeFiles(entryFiles)) {
            for (String relatedFile : relatedFiles) {
                if (entryFile.equals(relatedFile) || entryFile.contains(relatedFile) || relatedFile.contains(entryFile)
                        || sameTopModule(entryFile, relatedFile)) {
                    return entryFile;
                }
            }
        }
        return "";
    }

    private boolean sameTopModule(String left, String right) {
        String l = modulePrefix(left);
        String r = modulePrefix(right);
        return !l.isBlank() && l.equals(r);
    }

    private String modulePrefix(String path) {
        String normalized = path != null ? path.replace('\\', '/').toLowerCase(Locale.ROOT) : "";
        int src = normalized.indexOf("src/main/java/");
        String value = src >= 0 ? normalized.substring(src + "src/main/java/".length()) : normalized;
        String[] parts = value.split("/");
        if (parts.length >= 4 && "ricbot".equals(parts[0])) {
            return parts[0] + "/" + parts[1] + "/" + parts[2];
        }
        return parts.length > 0 ? parts[0] : "";
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    private List<String> normalizeFiles(List<String> files) {
        List<String> out = new ArrayList<>();
        for (String file : files != null ? files : List.<String>of()) {
            if (file != null && !file.isBlank()) {
                out.add(file.replace('\\', '/').toLowerCase(Locale.ROOT).trim());
            }
        }
        return out;
    }

    private double stalePenalty(ExperienceEntry entry) {
        long days = staleDays(entry);
        if (days < staleThresholdDays()) {
            return 0d;
        }
        return Math.min(0.30d, 0.10d + ((days - staleThresholdDays()) / 30.0d) * 0.05d);
    }

    private long staleDays(ExperienceEntry entry) {
        String value = entry != null ? entry.lastUsedAt() : "";
        if (value == null || value.isBlank()) {
            value = entry != null ? entry.updatedAt() : "";
        }
        try {
            return Math.max(0L, Duration.between(Instant.parse(value), Instant.now()).toDays());
        } catch (Exception e) {
            return 0L;
        }
    }

    private String staleReason(ExperienceEntry entry, double effectiveConfidence) {
        List<String> reasons = new ArrayList<>();
        if (effectiveConfidence < 0.3d) {
            reasons.add("effectiveConfidence<0.30");
        }
        if (entry.failureCount() > entry.successCount()) {
            reasons.add("failureCount>successCount");
        }
        long days = staleDays(entry);
        if (days >= staleThresholdDays()) {
            reasons.add("staleDays=" + days);
        }
        return String.join("; ", reasons);
    }

    private int staleThresholdDays() {
        String raw = System.getenv("RICBOT_EXPERIENCE_STALE_DAYS");
        try {
            return raw != null && !raw.isBlank() ? Math.max(1, Integer.parseInt(raw)) : 90;
        } catch (Exception e) {
            return 90;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ScoredExperience(ExperienceEntry entry, double score, double effectiveConfidence, String reason) {
        public ScoredExperience {
            score = Math.max(0d, Math.min(1d, score));
            effectiveConfidence = Math.max(0d, Math.min(1d, effectiveConfidence));
            reason = reason != null ? reason : "";
        }
    }

    public record UsageCount(ExperienceEntry entry, int count) {
    }

    public record ReviewItem(ExperienceEntry entry, String reason, double effectiveConfidence) {
    }

    public record GovernanceStats(
            int candidates,
            int verified,
            int rejected,
            int archived,
            int promoted,
            List<UsageCount> topUsed,
            List<ReviewItem> needsReview
    ) {
    }
}
