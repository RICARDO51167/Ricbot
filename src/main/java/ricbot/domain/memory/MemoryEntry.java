package ricbot.domain.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class MemoryEntry {

    public static final String TYPE_PREFERENCE = "preference";
    public static final String TYPE_FACT = "fact";
    public static final String TYPE_WORKFLOW = "workflow";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_PERSON = "person";

    public static final String SCOPE_SHORT_TERM = "short_term";
    public static final String SCOPE_LONG_TERM = "long_term";
    public static final String SCOPE_DISCARDABLE = "discardable";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_MERGED = "merged";
    public static final String STATUS_DISCARDED = "discarded";

    public static final String SENSITIVITY_NORMAL = "normal";
    public static final String SENSITIVITY_SENSITIVE = "sensitive";

    public static final String APPROVAL_APPROVED = "approved";
    public static final String APPROVAL_PENDING = "pending";
    public static final String APPROVAL_REJECTED = "rejected";

    private String id = UUID.randomUUID().toString();
    private String type = TYPE_FACT;
    private MemoryType memoryType;
    private String scope = SCOPE_SHORT_TERM;
    private String summary = "";
    private String details = "";
    private double importance = 0.5d;
    private double confidence = 0.5d;
    private String lastUsedAt;
    private int accessCount = 0;
    private String createdAt = Instant.now().toString();
    private String updatedAt = Instant.now().toString();
    private String source = "";
    private String sourceDetail = "";
    private String expiresAt;
    private String sensitivity = SENSITIVITY_NORMAL;
    private String approvalStatus = APPROVAL_APPROVED;
    private String status = STATUS_ACTIVE;
    private List<String> aliases = new ArrayList<>();
    private List<String> tags = new ArrayList<>();

    public static MemoryEntry fromMap(Map<String, Object> raw) {
        MemoryEntry entry = new MemoryEntry();
        if (raw == null) {
            return entry;
        }
        entry.id = stringValue(raw.get("id"), entry.id);
        entry.type = normalizeType(stringValue(raw.get("type"), entry.type));
        Object memoryTypeRaw = raw.containsKey("memory_type") ? raw.get("memory_type") : raw.get("memoryType");
        entry.memoryType = MemoryType.fromString(stringValue(memoryTypeRaw, null));
        entry.scope = normalizeScope(stringValue(raw.get("scope"), entry.scope));
        entry.summary = stringValue(raw.get("summary"), "");
        entry.details = stringValue(raw.get("details"), "");
        entry.importance = normalizeScore(raw.get("importance"), 0.5d);
        entry.confidence = normalizeScore(raw.get("confidence"), 0.5d);
        entry.lastUsedAt = blankToNull(stringValue(raw.get("last_used_at"), null));
        entry.accessCount = normalizeCount(raw.get("access_count"));
        entry.createdAt = stringValue(raw.get("created_at"), entry.createdAt);
        entry.updatedAt = stringValue(raw.get("updated_at"), entry.updatedAt);
        entry.source = stringValue(raw.get("source"), "");
        entry.sourceDetail = stringValue(raw.get("source_detail"), "");
        entry.expiresAt = blankToNull(stringValue(raw.get("expires_at"), null));
        entry.sensitivity = normalizeSensitivity(stringValue(raw.get("sensitivity"), entry.sensitivity));
        entry.approvalStatus = normalizeApprovalStatus(stringValue(raw.get("approval_status"), entry.approvalStatus));
        entry.status = normalizeStatus(stringValue(raw.get("status"), entry.status));
        entry.aliases = toStringList(raw.get("aliases"));
        entry.tags = toStringList(raw.get("tags"));
        if (entry.memoryType == null) {
            entry.memoryType = MemoryType.infer(entry.type, entry.scope, entry.source, entry.tags);
        }
        return entry;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type);
        out.put("memory_type", getMemoryType().name().toLowerCase());
        out.put("scope", scope);
        out.put("summary", summary);
        out.put("details", details);
        out.put("importance", importance);
        out.put("confidence", confidence);
        out.put("last_used_at", lastUsedAt);
        out.put("access_count", accessCount);
        out.put("created_at", createdAt);
        out.put("updated_at", updatedAt);
        out.put("source", source);
        out.put("source_detail", sourceDetail);
        out.put("expires_at", expiresAt);
        out.put("sensitivity", sensitivity);
        out.put("approval_status", approvalStatus);
        out.put("status", status);
        out.put("aliases", aliases != null ? aliases : List.of());
        out.put("tags", tags != null ? tags : List.of());
        return out;
    }

    public void touch() {
        this.updatedAt = Instant.now().toString();
    }

    public void markUsed() {
        this.lastUsedAt = Instant.now().toString();
        this.accessCount = Math.max(0, accessCount) + 1;
        touch();
    }

    public String dedupeKey() {
        return normalizeText(summary) + "|" + normalizeType(type);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isRecallable() {
        if (!isActive()) {
            return false;
        }
        if (isExpired()) {
            return false;
        }
        if (!APPROVAL_APPROVED.equals(approvalStatus)) {
            return false;
        }
        if (SCOPE_DISCARDABLE.equals(scope)) {
            return false;
        }
        return SCOPE_LONG_TERM.equals(scope) || importance >= 0.65d || confidence >= 0.70d;
    }

    public boolean isUserProfile() {
        return TYPE_PREFERENCE.equals(type) || TYPE_PERSON.equals(type);
    }

    public boolean isWorkingMemory() {
        return getMemoryType() == MemoryType.WORKING;
    }

    public boolean isEpisodicMemory() {
        return getMemoryType() == MemoryType.EPISODIC;
    }

    public boolean isSemanticMemory() {
        return getMemoryType() == MemoryType.SEMANTIC;
    }

    public boolean isPerceptualMemory() {
        return getMemoryType() == MemoryType.PERCEPTUAL;
    }

    public boolean isExpired() {
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(expiresAt).isBefore(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean requiresApproval() {
        return SENSITIVITY_SENSITIVE.equals(sensitivity) || APPROVAL_PENDING.equals(approvalStatus);
    }

    public boolean isSoulEntry() {
        return "soul".equalsIgnoreCase(source) || tags.contains("agent") || tags.contains("persona");
    }

    public String renderLine() {
        StringBuilder sb = new StringBuilder("- ").append(summary);
        if (details != null && !details.isBlank() && !Objects.equals(details.trim(), summary != null ? summary.trim() : "")) {
            sb.append(" — ").append(details.trim());
        }
        return sb.toString();
    }

    public static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? fallback : s;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static double normalizeScore(Object raw, double fallback) {
        double value = fallback;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else if (raw != null) {
            try {
                value = Double.parseDouble(String.valueOf(raw));
            } catch (Exception ignored) {
                value = fallback;
            }
        }
        if (value < 0d) {
            return 0d;
        }
        if (value > 1d) {
            return 1d;
        }
        return value;
    }

    private static int normalizeCount(Object raw) {
        if (raw instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (raw != null) {
            try {
                return Math.max(0, Integer.parseInt(String.valueOf(raw)));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static List<String> toStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isBlank() && !out.contains(text)) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    public static String normalizeType(String type) {
        if (TYPE_PREFERENCE.equals(type) || TYPE_FACT.equals(type) || TYPE_WORKFLOW.equals(type)
                || TYPE_PROJECT.equals(type) || TYPE_PERSON.equals(type)) {
            return type;
        }
        return TYPE_FACT;
    }

    public static String normalizeScope(String scope) {
        if (SCOPE_SHORT_TERM.equals(scope) || SCOPE_LONG_TERM.equals(scope) || SCOPE_DISCARDABLE.equals(scope)) {
            return scope;
        }
        return SCOPE_SHORT_TERM;
    }

    public static String normalizeStatus(String status) {
        if (STATUS_ACTIVE.equals(status) || STATUS_MERGED.equals(status) || STATUS_DISCARDED.equals(status)) {
            return status;
        }
        return STATUS_ACTIVE;
    }

    public static String normalizeSensitivity(String sensitivity) {
        if (SENSITIVITY_SENSITIVE.equals(sensitivity) || SENSITIVITY_NORMAL.equals(sensitivity)) {
            return sensitivity;
        }
        return SENSITIVITY_NORMAL;
    }

    public static String normalizeApprovalStatus(String status) {
        if (APPROVAL_APPROVED.equals(status) || APPROVAL_PENDING.equals(status) || APPROVAL_REJECTED.equals(status)) {
            return status;
        }
        return APPROVAL_APPROVED;
    }

    public String getId() {
        return id;
    }

    public MemoryEntry setId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id;
        }
        return this;
    }

    public String getType() {
        return type;
    }

    public MemoryEntry setType(String type) {
        this.type = normalizeType(type);
        return this;
    }

    public MemoryType getMemoryType() {
        if (memoryType == null) {
            memoryType = MemoryType.infer(type, scope, source, tags);
        }
        return memoryType;
    }

    public MemoryEntry setMemoryType(MemoryType memoryType) {
        this.memoryType = memoryType != null ? memoryType : MemoryType.infer(type, scope, source, tags);
        return this;
    }

    public MemoryEntry setMemoryType(String memoryType) {
        MemoryType parsed = MemoryType.fromString(memoryType);
        if (parsed != null) {
            this.memoryType = parsed;
        }
        return this;
    }

    public String getScope() {
        return scope;
    }

    public MemoryEntry setScope(String scope) {
        this.scope = normalizeScope(scope);
        return this;
    }

    public String getSummary() {
        return summary;
    }

    public MemoryEntry setSummary(String summary) {
        this.summary = summary != null ? summary : "";
        return this;
    }

    public String getDetails() {
        return details;
    }

    public MemoryEntry setDetails(String details) {
        this.details = details != null ? details : "";
        return this;
    }

    public double getImportance() {
        return importance;
    }

    public MemoryEntry setImportance(double importance) {
        this.importance = Math.max(0d, Math.min(1d, importance));
        return this;
    }

    public double getConfidence() {
        return confidence;
    }

    public MemoryEntry setConfidence(double confidence) {
        this.confidence = Math.max(0d, Math.min(1d, confidence));
        return this;
    }

    public String getLastUsedAt() {
        return lastUsedAt;
    }

    public MemoryEntry setLastUsedAt(String lastUsedAt) {
        this.lastUsedAt = blankToNull(lastUsedAt);
        return this;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public MemoryEntry setAccessCount(int accessCount) {
        this.accessCount = Math.max(0, accessCount);
        return this;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public MemoryEntry setCreatedAt(String createdAt) {
        if (createdAt != null && !createdAt.isBlank()) {
            this.createdAt = createdAt;
        }
        return this;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public MemoryEntry setUpdatedAt(String updatedAt) {
        if (updatedAt != null && !updatedAt.isBlank()) {
            this.updatedAt = updatedAt;
        }
        return this;
    }

    public String getSource() {
        return source;
    }

    public MemoryEntry setSource(String source) {
        this.source = source != null ? source : "";
        return this;
    }

    public String getSourceDetail() {
        return sourceDetail;
    }

    public MemoryEntry setSourceDetail(String sourceDetail) {
        this.sourceDetail = sourceDetail != null ? sourceDetail : "";
        return this;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public MemoryEntry setExpiresAt(String expiresAt) {
        this.expiresAt = blankToNull(expiresAt);
        return this;
    }

    public String getSensitivity() {
        return sensitivity;
    }

    public MemoryEntry setSensitivity(String sensitivity) {
        this.sensitivity = normalizeSensitivity(sensitivity);
        return this;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public MemoryEntry setApprovalStatus(String approvalStatus) {
        this.approvalStatus = normalizeApprovalStatus(approvalStatus);
        return this;
    }

    public String getStatus() {
        return status;
    }

    public MemoryEntry setStatus(String status) {
        this.status = normalizeStatus(status);
        return this;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public MemoryEntry setAliases(List<String> aliases) {
        this.aliases = aliases != null ? new ArrayList<>(aliases) : new ArrayList<>();
        return this;
    }

    public List<String> getTags() {
        return tags;
    }

    public MemoryEntry setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        return this;
    }
}
