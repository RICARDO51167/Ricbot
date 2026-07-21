package ricbot.domain.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StepAuditService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path teamRoot;

    public StepAuditService(Path workspace) {
        this.teamRoot = workspace.toAbsolutePath().normalize().resolve(".team");
    }

    public StepAuditRecord append(StepAuditRecord record) {
        if (record == null || record.teamSessionId().isBlank()) {
            return record;
        }
        if (!record.eventType().durableOutcome()) {
            return record;
        }
        try {
            Path file = auditFile(record.teamSessionId());
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(record) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            warn("step audit append failed: " + e.getMessage());
        }
        return record;
    }

    public List<StepAuditRecord> listByStep(String stepId) {
        String id = stepId != null ? stepId.trim() : "";
        if (id.isBlank()) {
            return List.of();
        }
        return loadAll().stream()
                .filter(record -> id.equals(record.stepId()))
                .sorted(Comparator.comparing(StepAuditRecord::createdAt))
                .toList();
    }

    public List<StepAuditRecord> listByTask(String taskId) {
        String id = taskId != null ? taskId.trim() : "";
        if (id.isBlank()) {
            return List.of();
        }
        return loadAll().stream()
                .filter(record -> id.equals(record.taskId()))
                .sorted(Comparator.comparing(StepAuditRecord::createdAt))
                .toList();
    }

    public StepAuditSummary summarizeTask(String taskId) {
        StepLoadResult stepLoad = loadStepsForTaskResult(taskId);
        List<PendingImplementationStep> steps = stepLoad.steps();
        List<StepAuditRecord> linked = linkedRecords(taskId, steps);
        return new StepAuditCompactor().compact(taskId, steps, linked).withWarnings(stepLoad.warnings());
    }

    public String renderStepTimeline(String stepId) {
        List<StepAuditRecord> records = listByStep(stepId);
        if (records.isEmpty()) {
            return "No step audit records for step: " + stepId;
        }
        StringBuilder sb = new StringBuilder("step timeline\n");
        for (StepAuditRecord record : records) {
            sb.append("- ").append(record.createdAt())
                    .append(" ").append(record.eventType())
                    .append(" ").append(record.beforeStatus()).append(" -> ").append(record.afterStatus())
                    .append(record.message().isBlank() ? "" : " | " + record.message())
                    .append(record.approvalRequestId().isBlank() ? "" : " | approval=" + record.approvalRequestId())
                    .append(record.toolName().isBlank() ? "" : " | tool=" + record.toolName())
                    .append(record.changeSetId().isBlank() ? "" : " | changeSet=" + record.changeSetId())
                    .append(record.verificationStatus().isBlank() ? "" : " | verifier=" + record.verificationStatus())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderTaskAudit(String taskId) {
        return renderTaskAudit(taskId, false);
    }

    public String renderTaskAudit(String taskId, boolean compact) {
        if (compact) {
            return renderCompactTaskAudit(taskId);
        }
        List<PendingImplementationStep> steps = loadStepsForTask(taskId);
        List<StepAuditRecord> records = linkedRecords(taskId, steps);
        if (records.isEmpty()) {
            return "No step audit records for task: " + taskId;
        }
        StepAuditRecord latest = records.stream()
                .max(Comparator.comparing(StepAuditRecord::createdAt))
                .orElse(null);
        StringBuilder sb = new StringBuilder("task step audit\n");
        sb.append("task=").append(taskId)
                .append(" totalAuditRecords=").append(records.size())
                .append(" latest=").append(latest != null ? latest.eventType() : "none")
                .append(" step=").append(latest != null ? latest.stepId() : "none")
                .append(" status=").append(latest != null && !latest.afterStatus().isBlank() ? latest.afterStatus() : "none")
                .append("\n");
        for (StepAuditRecord record : records) {
            sb.append("- step=").append(record.stepId())
                    .append(" event=").append(record.eventType())
                    .append(" status=").append(record.beforeStatus()).append("->").append(record.afterStatus())
                    .append(record.approvalRequestId().isBlank() ? "" : " approval=" + record.approvalRequestId())
                    .append(record.changeSetId().isBlank() ? "" : " changeSet=" + record.changeSetId())
                    .append(record.verificationStatus().isBlank() ? "" : " verifier=" + record.verificationStatus())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderCompactTaskAudit(String taskId) {
        return "compact step audit\n" + summarizeTask(taskId).renderCompact();
    }

    public String renderJsonCompactTaskAudit(String taskId) {
        try {
            return MAPPER.writeValueAsString(summarizeTask(taskId).toMap());
        } catch (Exception e) {
            warn("step audit compact json render failed: " + e.getMessage());
            return "{\"error\":\"step audit compact json render failed\"}";
        }
    }

    public String renderJsonTaskAudit(String taskId) {
        try {
            StepAuditSummary summary = summarizeTask(taskId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", summary.toMap());
            out.put("records", linkedRecords(taskId, loadStepsForTask(taskId)).stream().map(StepAuditRecord::toMap).toList());
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            warn("step audit json render failed: " + e.getMessage());
            return "{\"error\":\"step audit json render failed\"}";
        }
    }

    public List<StepAuditRecord> listUnresolved(String taskId) {
        return linkedRecords(taskId, loadStepsForTask(taskId)).stream()
                .filter(record -> record.eventType() == StepAuditEventType.STEP_BLOCKED || record.eventType() == StepAuditEventType.STEP_FAILED)
                .toList();
    }

    public Path auditFile(String teamSessionId) {
        return teamRoot.resolve(teamSessionId != null && !teamSessionId.isBlank() ? teamSessionId : "team_unknown")
                .resolve("step_audit.jsonl");
    }

    private List<StepAuditRecord> loadAll() {
        if (!Files.isDirectory(teamRoot)) {
            return List.of();
        }
        List<StepAuditRecord> out = new ArrayList<>();
        try (var stream = Files.list(teamRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                out.addAll(readAuditFile(dir.resolve("step_audit.jsonl")));
            }
        } catch (Exception e) {
            warn("step audit list failed: " + e.getMessage());
        }
        return out;
    }

    private List<StepAuditRecord> linkedRecords(String taskId, List<PendingImplementationStep> steps) {
        List<StepAuditRecord> records = listByTask(taskId);
        return new StepAuditLinker().link(steps, records);
    }

    private List<PendingImplementationStep> loadStepsForTask(String taskId) {
        return loadStepsForTaskResult(taskId).steps();
    }

    private StepLoadResult loadStepsForTaskResult(String taskId) {
        String id = taskId != null ? taskId.trim() : "";
        if (id.isBlank() || !Files.isDirectory(teamRoot)) {
            return new StepLoadResult(List.of(), List.of("no implementation steps found"));
        }
        List<PendingImplementationStep> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean sawStepFile = false;
        try (var stream = Files.list(teamRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path stepFile = dir.resolve("implementation_steps.jsonl");
                if (Files.exists(stepFile)) {
                    sawStepFile = true;
                }
                JsonLines lines = readJsonLines(stepFile);
                warnings.addAll(lines.warnings());
                for (Map<String, Object> row : lines.rows()) {
                    PendingImplementationStep step = PendingImplementationStep.fromMap(row);
                    if (step != null && id.equals(step.taskId())) {
                        out.add(step);
                    }
                }
            }
        } catch (Exception e) {
            String warning = "step audit load steps failed: " + e.getMessage();
            warnings.add(warning);
            warn(warning);
        }
        if (!sawStepFile || out.isEmpty()) {
            warnings.add("no implementation steps found");
        }
        return new StepLoadResult(out, warnings);
    }

    private JsonLines readJsonLines(Path file) {
        if (!Files.exists(file)) {
            return new JsonLines(List.of(), List.of());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            int lineNo = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNo++;
                if (line != null && !line.isBlank()) {
                    try {
                        out.add(MAPPER.readValue(line, MAP_TYPE));
                    } catch (Exception e) {
                        String warning = "skipped invalid jsonl line " + file.getFileName() + ":" + lineNo;
                        warnings.add(warning);
                        warn(warning + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            String warning = "step audit jsonl read failed: " + e.getMessage();
            warnings.add(warning);
            warn(warning);
        }
        return new JsonLines(out, warnings);
    }

    private List<StepAuditRecord> readAuditFile(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<StepAuditRecord> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                StepAuditRecord record = StepAuditRecord.fromMap(MAPPER.readValue(line, MAP_TYPE));
                if (record != null) {
                    out.add(record);
                }
            }
        } catch (Exception e) {
            warn("step audit read failed: " + e.getMessage());
        }
        return out;
    }

    private void warn(String message) {
        System.err.println("warning: " + message);
    }

    private record StepLoadResult(List<PendingImplementationStep> steps, List<String> warnings) {
    }

    private record JsonLines(List<Map<String, Object>> rows, List<String> warnings) {
    }
}
