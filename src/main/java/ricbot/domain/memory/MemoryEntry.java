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

    private String id = UUID.randomUUID().toString();
    private String type = TYPE_FACT;
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
        entry.status = normalizeStatus(stringValue(raw.get("status"), entry.status));
        entry.aliases = toStringList(raw.get("aliases"));
        entry.tags = toStringList(raw.get("tags"));
        return entry;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type);
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
        if (SCOPE_DISCARDABLE.equals(scope)) {
            return false;
        }
        return SCOPE_LONG_TERM.equals(scope) || importance >= 0.65d || confidence >= 0.70d;
    }

    public boolean isUserProfile() {
        return TYPE_PREFERENCE.equals(type) || TYPE_PERSON.equals(type);
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
