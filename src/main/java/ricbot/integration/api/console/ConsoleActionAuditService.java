package ricbot.integration.api.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.persistence.FileRuntimeFactJournal;
import ricbot.infra.persistence.RuntimeFactEvent;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.LinkedHashMap;

public class ConsoleActionAuditService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path auditFile;
    private final FileRuntimeFactJournal journal;

    public ConsoleActionAuditService(Path workspace) {
        Path root = (workspace != null ? workspace : Path.of(".")).toAbsolutePath().normalize();
        this.auditFile = root.resolve(".ricbot").resolve("console-actions.jsonl");
        this.journal = new FileRuntimeFactJournal(root);
    }

    public List<String> append(ConsoleActionAuditRecord record) {
        return append(record, "console");
    }

    public List<String> append(ConsoleActionAuditRecord record, String sessionKey) {
        if (record == null) {
            return List.of();
        }
        try {
            journal.append(
                    record.id(),
                    clean(sessionKey).isBlank() ? "console" : clean(sessionKey),
                    "console.action." + clean(record.action()).toLowerCase(java.util.Locale.ROOT),
                    record.operator(),
                    record.message(),
                    record.toMap(),
                    instant(record.timestamp())
            );
            return List.of();
        } catch (Exception e) {
            return List.of("console action audit write failed: " + message(e));
        }
    }

    public List<ConsoleActionAuditRecord> recent(int limit) {
        List<ConsoleActionAuditRecord> records = new ArrayList<>();
        if (Files.isRegularFile(auditFile)) {
            try (BufferedReader reader = Files.newBufferedReader(auditFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank()) continue;
                    try {
                        ConsoleActionAuditRecord record = ConsoleActionAuditRecord.fromMap(MAPPER.readValue(trimmed, MAP_TYPE));
                        if (record != null) records.add(record);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (RuntimeFactEvent event : journal.allEvents(50_000)) {
            if (!event.type().startsWith("console.action.")) continue;
            ConsoleActionAuditRecord record = ConsoleActionAuditRecord.fromMap(new LinkedHashMap<>(event.details()));
            if (record != null && records.stream().noneMatch(existing -> existing.id().equals(record.id()))) records.add(record);
        }
        records.sort(java.util.Comparator.comparing(ConsoleActionAuditRecord::timestamp));
        int max = limit > 0 ? limit : 20;
        int from = Math.max(0, records.size() - max);
        List<ConsoleActionAuditRecord> tail = records.subList(from, records.size());
        List<ConsoleActionAuditRecord> out = new ArrayList<>(tail);
        java.util.Collections.reverse(out);
        return out;
    }

    public Path auditFile() {
        return auditFile;
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(clean(value));
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
