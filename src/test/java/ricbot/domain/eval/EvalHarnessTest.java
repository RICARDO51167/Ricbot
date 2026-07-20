package ricbot.domain.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvalHarnessTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void run_writesArtifactsAndPassesMatchingScenario(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"hello","input":"say hello","expected_contains":["hello"],"expected_not_contains":["goodbye"]}
                """);

        EvalRecordingProvider recorder = new EvalRecordingProvider(provider("hello from eval"));
        AgentLoop loop = loop(workspace, recorder);
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace), recorder).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getTotal());
            assertEquals(1, summary.getPassed());
            assertEquals(0, summary.getFailed());

            Path artifactDir = Path.of(summary.getArtifactDir());
            assertTrue(Files.exists(artifactDir.resolve("manifest.json")));
            assertTrue(Files.exists(artifactDir.resolve("summary.json")));
            assertTrue(Files.exists(artifactDir.resolve("report.md")));
            assertTrue(Files.exists(artifactDir.resolve("cases.jsonl")));
            assertTrue(Files.exists(artifactDir.resolve("cases").resolve("hello.json")));

            Map<String, Object> manifest = MAPPER.readValue(
                    artifactDir.resolve("manifest.json").toFile(),
                    new TypeReference<>() {}
            );
            assertEquals("test-model", manifest.get("model"));
            assertTrue(String.valueOf(manifest.get("tool_schema_sha256")).length() >= 32);

            Map<String, Object> caseArtifact = MAPPER.readValue(
                    artifactDir.resolve("cases").resolve("hello.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> result = (Map<?, ?>) caseArtifact.get("result");
            List<?> modelCalls = (List<?>) result.get("model_calls");
            assertEquals(1, modelCalls.size());
            Map<?, ?> modelCall = (Map<?, ?>) modelCalls.get(0);
            assertEquals("ok", modelCall.get("status"));
            assertEquals("test-model", modelCall.get("model"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_filtersScenariosByTagsAndWritesReport(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"fast-case","input":"say hello","tags":["fast"],"expected_contains":["hello"]}
                {"id":"slow-case","input":"say goodbye","tags":["slow"],"expected_contains":["goodbye"]}
                """);

        AgentLoop loop = loop(workspace, provider("hello from eval"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out"))
                    .setIncludeTags(List.of("fast")));

            assertEquals(1, summary.getTotal());
            assertEquals(1, summary.getPassed());
            Path artifactDir = Path.of(summary.getArtifactDir());
            assertTrue(Files.exists(artifactDir.resolve("cases").resolve("fast-case.json")));
            assertFalse(Files.exists(artifactDir.resolve("cases").resolve("slow-case.json")));
            String report = Files.readString(artifactDir.resolve("report.md"));
            assertTrue(report.contains("All cases passed."));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_supportsSkipAndExpectedFailure(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"known-bad","input":"say hello","expected_contains":["absent"],"xfail":true,"xfail_reason":"known gap","expected_failure_kind":"assertion_failed"}
                {"id":"skipped-case","input":"say hello","skip":true,"skip_reason":"not ready","expected_contains":["hello"]}
                """);

        AgentLoop loop = loop(workspace, provider("hello from eval"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(2, summary.getTotal());
            assertEquals(0, summary.getPassed());
            assertEquals(0, summary.getFailed());
            assertEquals(1, summary.getExpectedFailed());
            assertEquals(1, summary.getSkipped());

            List<String> lines = Files.readAllLines(Path.of(summary.getArtifactDir()).resolve("cases.jsonl"));
            Map<String, Object> xfail = MAPPER.readValue(lines.get(0), new TypeReference<>() {});
            Map<String, Object> skipped = MAPPER.readValue(lines.get(1), new TypeReference<>() {});
            assertEquals("xfail", xfail.get("status"));
            assertEquals("expected_failure", xfail.get("expectation_status"));
            assertEquals("skipped", skipped.get("status"));
            assertEquals("skipped", skipped.get("expectation_status"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_failsWhenExpectedFailureUnexpectedlyPasses(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"fixed-bug","input":"say hello","expected_contains":["hello"],"xfail":true,"xfail_reason":"should now be removed"}
                """);

        AgentLoop loop = loop(workspace, provider("hello from eval"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getUnexpectedPassed());
            assertEquals(1, summary.getFailuresByKind().get("unexpected_pass"));
            List<String> lines = Files.readAllLines(Path.of(summary.getArtifactDir()).resolve("cases.jsonl"));
            Map<String, Object> result = MAPPER.readValue(lines.get(0), new TypeReference<>() {});
            assertEquals("xpass", result.get("status"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void compare_detectsRegressionsAndWritesReport(@TempDir Path workspace) throws Exception {
        Path baseline = workspace.resolve("baseline");
        Path candidate = workspace.resolve("candidate");
        Files.createDirectories(baseline);
        Files.createDirectories(candidate);
        writeSummary(baseline, 2, 1, 1);
        writeSummary(candidate, 3, 1, 2);
        writeCases(baseline, List.of(
                caseResult("stable", "pass", null),
                caseResult("fixed", "fail", "assertion_failed")
        ));
        writeCases(candidate, List.of(
                caseResult("stable", "fail", "assertion_failed"),
                caseResult("fixed", "pass", null),
                caseResult("new-bad", "fail", "side_effect_violation")
        ));

        EvalComparisonResult result = new EvalCompareRunner().compare(
                baseline,
                candidate,
                workspace.resolve("compare-out")
        );

        assertEquals("fail", result.getStatus());
        assertEquals(2, result.getRegressions());
        assertEquals(1, result.getImprovements());
        assertEquals(1, result.getNewCases());
        assertEquals(0, result.getMissingCases());
        assertTrue(Files.exists(Path.of(result.getArtifactDir()).resolve("comparison.json")));
        String report = Files.readString(Path.of(result.getArtifactDir()).resolve("comparison-report.md"));
        assertTrue(report.contains("stable"));
        assertTrue(report.contains("new-bad"));
        assertTrue(report.contains("regressions: 2"));
    }

    @Test
    void lint_passesValidGoldenScenarios(@TempDir Path workspace) throws Exception {
        EvalLintResult result = new EvalScenarioLinter().lint(
                Path.of("evals/golden.jsonl"),
                workspace.resolve("lint-out")
        );

        assertEquals("pass", result.getStatus());
        assertEquals(16, result.getTotalScenarios());
        assertEquals(0, result.getErrors());
        assertTrue(Files.exists(Path.of(result.getArtifactDir()).resolve("lint.json")));
        assertTrue(Files.readString(Path.of(result.getArtifactDir()).resolve("lint-report.md")).contains("No lint issues found."));
    }

    @Test
    void lint_failsInvalidScenarios(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("bad.jsonl");
        Files.writeString(scenarios, """
                {"id":"dup","input":"hello"}
                {"id":"dup","input":"hello","expected_regex":["["],"workspace_files":{"../escape.txt":"bad"},"max_model_calls":-1}
                {"id":"empty-turns","turns":[]}
                """);

        EvalLintResult result = new EvalScenarioLinter().lint(
                scenarios,
                workspace.resolve("lint-out")
        );

        assertEquals("fail", result.getStatus());
        assertTrue(result.getErrors() >= 5);
        String report = Files.readString(Path.of(result.getArtifactDir()).resolve("lint-report.md"));
        assertTrue(report.contains("duplicate_id"));
        assertTrue(report.contains("invalid_regex"));
        assertTrue(report.contains("path_escape"));
        assertTrue(report.contains("missing_assertions"));
    }

    @Test
    void run_classifiesExpectationFailures(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"missing","input":"say hello","expected_contains":["not present"]}
                """);

        AgentLoop loop = loop(workspace, provider("hello from eval"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getTotal());
            assertEquals(0, summary.getPassed());
            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getFailuresByKind().get("assertion_failed"));

            List<String> lines = Files.readAllLines(Path.of(summary.getArtifactDir()).resolve("cases.jsonl"));
            assertEquals(1, lines.size());
            Map<String, Object> result = MAPPER.readValue(lines.get(0), new TypeReference<>() {});
            assertEquals("fail", result.get("status"));
            assertEquals("assertion_failed", result.get("failure_kind"));
            String report = Files.readString(Path.of(summary.getArtifactDir()).resolve("report.md"));
            assertTrue(report.contains("missing"));
            assertTrue(report.contains("ricbot eval replay --case"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_classifiesSideEffectViolationsAndWritesWorkspaceDiff(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"write-blocked","input":"write a file","expected_contains":["done"],"allowed_side_effects":"none"}
                """);

        AgentLoop loop = loop(workspace, fileWritingProvider());
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getFailuresByKind().get("side_effect_violation"));
            assertFalse(Files.exists(workspace.resolve("probe.txt")));

            Map<String, Object> caseArtifact = MAPPER.readValue(
                    Path.of(summary.getArtifactDir()).resolve("cases").resolve("write-blocked.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> result = (Map<?, ?>) caseArtifact.get("result");
            assertEquals("side_effect_violation", result.get("failure_kind"));
            Map<?, ?> diff = (Map<?, ?>) result.get("workspace_diff");
            assertEquals(1, diff.get("change_count"));
            List<?> added = (List<?>) diff.get("added");
            assertEquals("probe.txt", ((Map<?, ?>) added.get(0)).get("path"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void replay_usesRecordedModelCallsWithoutRealProvider(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"hello","input":"say hello","expected_contains":["hello"]}
                """);

        EvalRecordingProvider recorder = new EvalRecordingProvider(provider("hello from eval"));
        AgentLoop loop = loop(workspace, recorder);
        EvalRunSummary original;
        try {
            original = new EvalHarness(loop, config(workspace), recorder).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));
        } finally {
            loop.stop();
        }

        Path caseArtifact = Path.of(original.getArtifactDir()).resolve("cases").resolve("hello.json");
        EvalReplayRunner replayRunner = new EvalReplayRunner(
                config(workspace),
                provider -> loop(workspace, provider)
        );

        EvalRunSummary replay = replayRunner.replay(
                List.of(caseArtifact),
                new EvalOptions().setOutputDir(workspace.resolve("replay-out"))
        );

        assertEquals(1, replay.getTotal());
        assertEquals(1, replay.getPassed());
        assertEquals(0, replay.getFailed());

        Map<String, Object> replayArtifact = MAPPER.readValue(
                Path.of(replay.getArtifactDir()).resolve("cases").resolve("hello.json").toFile(),
                new TypeReference<>() {}
        );
        Map<?, ?> result = (Map<?, ?>) replayArtifact.get("result");
        assertEquals("hello from eval", result.get("response"));
        assertTrue(((List<?>) result.get("model_calls")).size() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replay_classifiesResponseMismatch(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"hello","input":"say hello","expected_contains":["hello"]}
                """);

        EvalRecordingProvider recorder = new EvalRecordingProvider(provider("hello from eval"));
        AgentLoop loop = loop(workspace, recorder);
        EvalRunSummary original;
        try {
            original = new EvalHarness(loop, config(workspace), recorder).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));
        } finally {
            loop.stop();
        }

        Path caseArtifact = Path.of(original.getArtifactDir()).resolve("cases").resolve("hello.json");
        Map<String, Object> artifact = MAPPER.readValue(caseArtifact.toFile(), new TypeReference<>() {});
        Map<String, Object> result = new java.util.LinkedHashMap<>((Map<String, Object>) artifact.get("result"));
        result.put("response", "different original response");
        artifact.put("result", result);
        Path tampered = workspace.resolve("tampered-case.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tampered.toFile(), artifact);

        EvalReplayRunner replayRunner = new EvalReplayRunner(
                config(workspace),
                provider -> loop(workspace, provider)
        );

        EvalRunSummary replay = replayRunner.replay(
                List.of(tampered),
                new EvalOptions().setOutputDir(workspace.resolve("replay-out"))
        );

        assertEquals(1, replay.getFailed());
        assertEquals(1, replay.getFailuresByKind().get("replay_mismatch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replay_failsWhenRecordedRequestDiffers(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"hello","input":"say hello","expected_contains":["hello"]}
                """);

        EvalRecordingProvider recorder = new EvalRecordingProvider(provider("hello from eval"));
        AgentLoop loop = loop(workspace, recorder);
        EvalRunSummary original;
        try {
            original = new EvalHarness(loop, config(workspace), recorder).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));
        } finally {
            loop.stop();
        }

        Path caseArtifact = Path.of(original.getArtifactDir()).resolve("cases").resolve("hello.json");
        Map<String, Object> artifact = MAPPER.readValue(caseArtifact.toFile(), new TypeReference<>() {});
        Map<String, Object> result = new java.util.LinkedHashMap<>((Map<String, Object>) artifact.get("result"));
        List<Map<String, Object>> calls = new java.util.ArrayList<>((List<Map<String, Object>>) result.get("model_calls"));
        Map<String, Object> firstCall = new java.util.LinkedHashMap<>(calls.get(0));
        firstCall.put("messages", List.of());
        calls.set(0, firstCall);
        result.put("model_calls", calls);
        artifact.put("result", result);
        Path tampered = workspace.resolve("tampered-request-case.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tampered.toFile(), artifact);

        EvalReplayRunner replayRunner = new EvalReplayRunner(
                config(workspace),
                provider -> loop(workspace, provider)
        );

        EvalRunSummary replay = replayRunner.replay(
                List.of(tampered),
                new EvalOptions().setOutputDir(workspace.resolve("replay-out"))
        );

        assertEquals(1, replay.getFailed());
        assertEquals(1, replay.getFailuresByKind().get("replay_mismatch"));
        Map<String, Object> replayArtifact = MAPPER.readValue(
                Path.of(replay.getArtifactDir()).resolve("cases").resolve("hello.json").toFile(),
                new TypeReference<>() {}
        );
        Map<?, ?> replayResult = (Map<?, ?>) replayArtifact.get("result");
        List<?> replayErrors = (List<?>) replayResult.get("replay_errors");
        assertTrue(String.valueOf(replayErrors).contains("request messages mismatch"));
    }

    @Test
    void run_supportsMultiTurnScenarios(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"multi","turns":[{"input":"remember code alpha","expected_contains":["stored"]},{"input":"what code did I give you?","expected_contains":["alpha"]}],"expected_contains":["alpha"],"max_model_calls":2}
                """);

        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = loop(workspace, new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse(calls.incrementAndGet() == 1 ? "stored alpha" : "the code was alpha")
                        .setFinishReason("stop");
            }
        });
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getPassed());
            Map<String, Object> caseArtifact = MAPPER.readValue(
                    Path.of(summary.getArtifactDir()).resolve("cases").resolve("multi.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> result = (Map<?, ?>) caseArtifact.get("result");
            assertEquals(2, ((List<?>) result.get("turn_results")).size());
            assertEquals("the code was alpha", result.get("response"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void recordingProvider_redactsSensitiveKeys() throws Exception {
        EvalRecordingProvider recorder = new EvalRecordingProvider(provider("ok"));
        recorder.chat(
                List.of(Map.of("role", "user", "api_key", "secret-value", "content", "hello")),
                List.of(),
                "test-model",
                null,
                null,
                "none",
                null
        );

        List<Map<String, Object>> calls = recorder.drainCalls();
        List<?> messages = (List<?>) calls.get(0).get("messages");
        Map<?, ?> firstMessage = (Map<?, ?>) messages.get(0);
        assertEquals("[REDACTED]", firstMessage.get("api_key"));
    }

    @Test
    void run_refusesUnsafeWorkspaceClean() throws Exception {
        Path workspace = Path.of("unsafe-eval-workspace-test").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("stale.txt"), "must stay");
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"unsafe-clean","input":"ok","clean_workspace":true,"expected_contains":["ok"]}
                """);

        AgentLoop loop = loop(workspace, provider("ok"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(Path.of("target", "eval-artifacts", "unsafe-clean-test")));

            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getFailuresByKind().get("fixture_error"));
            assertTrue(Files.exists(workspace.resolve("stale.txt")));
        } finally {
            loop.stop();
            deleteRecursively(workspace);
        }
    }

    @Test
    void run_assertsJsonResponseFields(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"json-pass","input":"return json","expected_json_required":["status","items[0].name"],"expected_json_values":{"status":"ok","items[0].count":2},"expected_json_absent":["error"]}
                """);

        AgentLoop loop = loop(workspace, provider("{\"status\":\"ok\",\"items\":[{\"name\":\"alpha\",\"count\":2}]}"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getPassed());
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_failsWhenJsonResponseDoesNotMatch(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"json-fail","input":"return json","expected_json_values":{"status":"ok"}}
                """);

        AgentLoop loop = loop(workspace, provider("{\"status\":\"error\"}"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getFailuresByKind().get("assertion_failed"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_assertsToolUseAndCallBudgets(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"tool-policy","input":"write a file","expected_contains":["done"],"expected_tools":["write_file"],"forbidden_tools":["exec"],"max_model_calls":2,"max_tool_calls":1,"expected_stop_reason":"stop","allowed_side_effects":"files"}
                """);

        AgentLoop loop = loop(workspace, fileWritingProvider());
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getPassed());
            assertEquals(0, summary.getFailed());
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_failsWhenForbiddenToolIsUsed(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"forbidden-tool","input":"write a file","expected_contains":["done"],"forbidden_tools":["write_file"],"allowed_side_effects":"files"}
                """);

        AgentLoop loop = loop(workspace, fileWritingProvider());
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getFailuresByKind().get("assertion_failed"));

            Map<String, Object> caseArtifact = MAPPER.readValue(
                    Path.of(summary.getArtifactDir()).resolve("cases").resolve("forbidden-tool.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> result = (Map<?, ?>) caseArtifact.get("result");
            List<?> assertionErrors = (List<?>) result.get("assertion_errors");
            assertTrue(String.valueOf(assertionErrors.get(0)).contains("forbidden tool used"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_preparesCleanWorkspaceAndAssertsFileContent(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("stale.txt"), "old data");
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"fixture-file","input":"read the fixture","clean_workspace":true,"workspace_files":{"docs/input.txt":"seed value\\n"},"expected_contains":["done"],"expected_file_contains":{"docs/input.txt":["seed value"]},"expected_file_not_contains":{"docs/input.txt":["old data"]},"allowed_side_effects":"none","max_file_changes":0}
                """);

        AgentLoop loop = loop(workspace, provider("done"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getPassed());
            assertTrue(Files.exists(workspace.resolve("stale.txt")));
            assertFalse(Files.exists(workspace.resolve("docs").resolve("input.txt")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_restoresWorkspaceBetweenCasesByDefault(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"writer","input":"write a file","expected_contains":["done"],"expected_tools":["write_file"],"allowed_side_effects":"files","max_file_changes":1,"expected_file_contains":{"probe.txt":["changed"]}}
                {"id":"reader","input":"ok","expected_contains":["ok"],"expected_file_not_contains":{"probe.txt":["changed"]},"allowed_side_effects":"none","max_file_changes":0}
                """);

        AgentLoop loop = loop(workspace, fileWritingThenOkProvider());
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(2, summary.getPassed(), "failures=" + summary.getFailuresByKind());
            assertEquals(0, summary.getFailed());
            assertFalse(Files.exists(workspace.resolve("probe.txt")));
            Map<String, Object> writerArtifact = MAPPER.readValue(
                    Path.of(summary.getArtifactDir()).resolve("cases").resolve("writer.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> writerResult = (Map<?, ?>) writerArtifact.get("result");
            assertEquals(List.of(), writerResult.get("workspace_restore_errors"));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_canLeaveWorkspaceWhenRestoreDisabled(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"writer","input":"write a file","restore_workspace":false,"expected_contains":["done"],"expected_tools":["write_file"],"allowed_side_effects":"files","max_file_changes":1,"expected_file_contains":{"probe.txt":["changed"]}}
                """);

        AgentLoop loop = loop(workspace, fileWritingProvider());
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getPassed(), "failures=" + summary.getFailuresByKind());
            assertTrue(Files.exists(workspace.resolve("probe.txt")));
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_capturesSessionAndMemoryStateAndRestoresSession(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(workspace.resolve("memory").resolve("history.jsonl"), "{\"content\":\"seed\"}\n");
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"state","input":"say hello","session":"shared-eval-session","expected_contains":["hello"],"expected_session_message_count":2,"expected_session_role_counts":{"user":1,"assistant":1},"expected_session_contains":["say hello","hello"],"expected_memory_counts":{"history_count":1},"expected_memory_file_contains":{"memory/history.jsonl":["seed"]}}
                """);

        AgentLoop loop = loop(workspace, provider("hello from eval"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getPassed(), "failures=" + summary.getFailuresByKind());
            Map<String, Object> caseArtifact = MAPPER.readValue(
                    Path.of(summary.getArtifactDir()).resolve("cases").resolve("state.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> result = (Map<?, ?>) caseArtifact.get("result");
            Map<?, ?> sessionState = (Map<?, ?>) result.get("session_state");
            Map<?, ?> memoryState = (Map<?, ?>) result.get("memory_state");
            assertEquals(2, sessionState.get("message_count"));
            assertEquals(1, memoryState.get("history_count"));
            assertTrue(loop.getSessions().find("shared-eval-session").isEmpty());
        } finally {
            loop.stop();
        }
    }

    @Test
    void run_failsWhenExpectedFileTextIsMissing(@TempDir Path workspace) throws Exception {
        Path scenarios = workspace.resolve("scenarios.jsonl");
        Files.writeString(scenarios, """
                {"id":"missing-file-text","input":"check fixture","clean_workspace":true,"workspace_files":{"docs/input.txt":"seed value\\n"},"expected_contains":["done"],"expected_file_contains":{"docs/input.txt":["absent text"]}}
                """);

        AgentLoop loop = loop(workspace, provider("done"));
        try {
            EvalRunSummary summary = new EvalHarness(loop, config(workspace)).run(new EvalOptions()
                    .setScenariosPath(scenarios)
                    .setOutputDir(workspace.resolve("eval-out")));

            assertEquals(1, summary.getFailed());
            assertEquals(1, summary.getFailuresByKind().get("assertion_failed"));
            Map<String, Object> caseArtifact = MAPPER.readValue(
                    Path.of(summary.getArtifactDir()).resolve("cases").resolve("missing-file-text.json").toFile(),
                    new TypeReference<>() {}
            );
            Map<?, ?> result = (Map<?, ?>) caseArtifact.get("result");
            List<?> assertionErrors = (List<?>) result.get("assertion_errors");
            assertTrue(String.valueOf(assertionErrors.get(0)).contains("missing expected text"));
        } finally {
            loop.stop();
        }
    }

    private static AgentLoop loop(Path workspace, LLMProvider provider) {
        return new AgentLoop(
                new MessageBus(),
                provider,
                workspace,
                "test-model",
                4,
                8_000,
                24,
                4_000,
                "none",
                new Config.WebToolsConfig(),
                execDisabled(),
                Map.of(),
                true,
                null,
                "UTC",
                false,
                List.of(),
                0,
                dreamDisabled()
        );
    }

    private static Config config(Path workspace) {
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(workspace.toString());
        config.getAgents().getDefaults().setModel("test-model");
        config.getAgents().getDefaults().setDream(dreamDisabled());
        config.getTools().setExec(execDisabled());
        return config;
    }

    private static LLMProvider provider(String response) {
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse(response).setFinishReason("stop");
            }
        };
    }

    private static LLMProvider fileWritingProvider() {
        AtomicInteger calls = new AtomicInteger();
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                if (calls.incrementAndGet() == 1) {
                    return new LLMResponse()
                            .setContent("")
                            .setFinishReason("tool_calls")
                            .setToolCalls(List.of(new ToolCallRequest(
                                    "call_1",
                                    "write_file",
                                    Map.of("path", "probe.txt", "content", "changed\n")
                            )));
                }
                return new LLMResponse("done").setFinishReason("stop");
            }
        };
    }

    private static LLMProvider fileWritingThenOkProvider() {
        AtomicInteger calls = new AtomicInteger();
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return new LLMResponse()
                            .setContent("")
                            .setFinishReason("tool_calls")
                            .setToolCalls(List.of(new ToolCallRequest(
                                    "call_1",
                                    "write_file",
                                    Map.of("path", "probe.txt", "content", "changed\n")
                            )));
                }
                if (call == 2) {
                    return new LLMResponse("done").setFinishReason("stop");
                }
                return new LLMResponse("ok").setFinishReason("stop");
            }
        };
    }

    private static Config.ExecToolConfig execDisabled() {
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        exec.setApprovalEnabled(false);
        return exec;
    }

    private static Config.DreamConfig dreamDisabled() {
        Config.DreamConfig dream = new Config.DreamConfig();
        dream.setEnabled(false);
        return dream;
    }

    private static EvalRunSummary writeSummary(Path runDir, int total, int passed, int failed) throws Exception {
        EvalRunSummary summary = new EvalRunSummary()
                .setRunId(runDir.getFileName().toString())
                .setTotal(total)
                .setPassed(passed)
                .setFailed(failed)
                .setDurationP50Ms(10)
                .setDurationP95Ms(20)
                .setTotalModelCalls(total)
                .setTotalToolCalls(0)
                .setTotalWorkspaceChanges(0)
                .setArtifactDir(runDir.toString());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("summary.json").toFile(), summary);
        return summary;
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
                .setFailureDetail(failureKind != null ? "detail for " + failureKind : null)
                .setArtifactPath("/tmp/" + id + ".json")
                .setResponse(status)
                .setDurationMs(1);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
