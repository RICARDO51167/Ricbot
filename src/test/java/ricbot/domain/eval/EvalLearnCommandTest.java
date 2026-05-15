package ricbot.domain.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.app.cli.CliCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalLearnCommandTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void evalLearnRunGeneratesCandidatesAndSkipsDuplicates(@TempDir Path workspace) throws Exception {
        Path runDir = workspace.resolve("artifacts").resolve("run-123");
        Files.createDirectories(runDir);
        writeSummary(runDir);
        writeCases(runDir, List.of(caseResult("assertion-case", "fail", "assertion_failed")));

        String first = runCli("eval", "learn", "--run", runDir.toString(), "--workspace", workspace.toString());

        assertTrue(first.contains("ricbot eval learn"), first);
        assertTrue(first.contains("candidates_generated: 1"), first);
        assertTrue(first.contains("added: 1"), first);
        assertTrue(first.contains("sourceRef=eval:run-123:assertion-case"), first);
        assertEquals(1, candidateLines(workspace));

        String second = runCli("eval", "learn", "--run", runDir.toString(), "--workspace", workspace.toString());

        assertTrue(second.contains("skipped_duplicate: 1"), second);
        assertTrue(second.contains("skipped duplicate"), second);
        assertEquals(1, candidateLines(workspace));
    }

    private static String runCli(String... args) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            CliCommands.main(args);
        } finally {
            System.setOut(previous);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path runDir) throws Exception {
        EvalRunSummary summary = new EvalRunSummary()
                .setRunId("run-123")
                .setTotal(1)
                .setFailed(1)
                .setArtifactDir(runDir.toString());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("summary.json").toFile(), summary);
    }

    private static void writeCases(Path runDir, List<EvalCaseResult> cases) throws Exception {
        StringBuilder out = new StringBuilder();
        for (EvalCaseResult result : cases) {
            out.append(MAPPER.writeValueAsString(result)).append('\n');
        }
        Files.writeString(runDir.resolve("cases.jsonl"), out.toString());
    }

    private static EvalCaseResult caseResult(String id, String status, String failureKind) {
        return new EvalCaseResult()
                .setId(id)
                .setStatus(status)
                .setFailureKind(failureKind)
                .setFailureDetail("detail for " + failureKind)
                .setArtifactPath("/tmp/" + id + ".json")
                .setAssertionErrors(List.of("expected content missing"));
    }

    private static long candidateLines(Path workspace) throws Exception {
        Path candidates = workspace.resolve("experience").resolve("candidates.jsonl");
        return Files.readAllLines(candidates, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .count();
    }
}
