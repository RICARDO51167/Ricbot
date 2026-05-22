package ricbot.integration.api.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConsoleActionAuditService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path auditFile;

    public ConsoleActionAuditService(Path workspace) {
        Path root = (workspace != null ? workspace : Path.of(".")).toAbsolutePath().normalize();
        this.auditFile = root.resolve(".ricbot").resolve("console-actions.jsonl");
    }

    public List<String> append(ConsoleActionAuditRecord record) {
        if (record == null) {
            return List.of();
        }
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(
                    auditFile,
                    MAPPER.writeValueAsString(record.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    Files.exists(auditFile)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE
            );
            return List.of();
        } catch (Exception e) {
            return List.of("console action audit write failed: " + message(e));
        }
    }

    public List<ConsoleActionAuditRecord> recent(int limit) {
        if (!Files.isRegularFile(auditFile)) {
            return List.of();
        }
        List<ConsoleActionAuditRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(auditFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                try {
                    ConsoleActionAuditRecord record = ConsoleActionAuditRecord.fromMap(MAPPER.readValue(trimmed, MAP_TYPE));
                    if (record != null) {
                        records.add(record);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
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
}
