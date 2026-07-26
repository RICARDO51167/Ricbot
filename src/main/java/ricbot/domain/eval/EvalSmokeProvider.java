package ricbot.domain.eval;

import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMFailureKind;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class EvalSmokeProvider extends LLMProvider {
    private final AtomicInteger toolCallIds = new AtomicInteger();

    public EvalSmokeProvider() {
        super("smoke", "smoke://local");
        setDefaultModel("smoke-model");
    }

    @Override
    public LLMResponse chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice
    ) throws Exception {
        String user = lastUserMessage(messages);
        String lower = user.toLowerCase(java.util.Locale.ROOT);

        if (lastToolResultContains(messages, "错误：")) {
            return response("tool failed as expected");
        }
        if (hasToolResult(messages, "write_file") && lower.contains("regression test file")
                && lower.contains("parsertest.java")) {
            return response("done: parser regression test added");
        }
        if (hasToolResult(messages, "write_file") && lower.contains("fix src/main/java/demo/calculator.java")) {
            return response("done: calculator add fixed");
        }
        if (hasToolResult(messages, "read_file") && lower.contains("fix src/main/java/demo/calculator.java")) {
            return toolCall("write_file", Map.of(
                    "path", "src/main/java/demo/Calculator.java",
                    "content", "package demo;\n\npublic class Calculator {\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n"
            ));
        }
        if (hasToolResult(messages, "read_file") && lower.contains("inspect src/main/java/demo/calculator.java")) {
            return response("The add method bug is that add currently subtracts b from a instead of returning the sum.");
        }
        if (hasToolResult(messages, "read_file")) {
            return response("The key phrase is deterministic harness coverage.");
        }
        if (hasToolResult(messages, "write_file")) {
            return response("done: harness report complete");
        }
        if (hasToolResult(messages, "list_dir")) {
            return response("fixture.txt is present.");
        }

        if (lower.contains("remember code alpha")) {
            return response("stored alpha");
        }
        if (lower.contains("what code did i give you")) {
            boolean remembered = messages.stream()
                    .map(msg -> String.valueOf(msg.get("content")).toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(content -> content.contains("remember code alpha"));
            return response(remembered ? "The code was alpha." : "I do not know the code.");
        }
        if (lower.contains("return compact json status")) {
            return response("{\"status\":\"ok\",\"checks\":[{\"name\":\"harness\",\"passed\":true}],\"debug\":null}");
        }
        if (lower.contains("simulate model api failure")) {
            throw new LLMFailureException(LLMFailureKind.TRANSIENT,
                    "model api failure injected by eval smoke provider", 503, null, null);
        }
        if (lower.contains("call read_file without path")) {
            return toolCall("read_file", Map.of());
        }
        if (lower.contains("read missing file")) {
            return toolCall("read_file", Map.of("path", "missing.txt"));
        }
        if (lower.contains("inspect src/main/java/demo/calculator.java")) {
            return toolCall("read_file", Map.of("path", "src/main/java/demo/Calculator.java"));
        }
        if (lower.contains("fix src/main/java/demo/calculator.java")) {
            return toolCall("read_file", Map.of("path", "src/main/java/demo/Calculator.java"));
        }
        if (lower.contains("add a regression test file")
                && lower.contains("src/test/java/demo/parsertest.java")) {
            return toolCall("write_file", Map.of(
                    "path", "src/test/java/demo/ParserTest.java",
                    "content", "package demo;\n\nimport org.junit.jupiter.api.Test;\n\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nclass ParserTest {\n    @Test\n    void blankInputReturnsEmptyList() {\n        Parser parser = new Parser();\n        assertTrue(parser.parse(\"   \").isEmpty());\n    }\n}\n"
            ));
        }
        if (lower.contains("read docs/input.txt")) {
            return toolCall("read_file", Map.of("path", "docs/input.txt"));
        }
        if (lower.contains("write a report file")) {
            return toolCall("write_file", Map.of(
                    "path", "reports/summary.txt",
                    "content", "harness report complete\n"
            ));
        }
        if (lower.contains("list the workspace root")) {
            return toolCall("list_dir", Map.of("path", "."));
        }
        String content = lower.contains("harness")
                ? "An eval harness checks agent behavior with repeatable scenarios."
                : "Hello from the golden eval.";
        return response(content);
    }

    private String lastUserMessage(List<Map<String, Object>> messages) {
        return messages.stream()
                .filter(msg -> "user".equals(String.valueOf(msg.get("role"))))
                .map(msg -> String.valueOf(msg.get("content")))
                .reduce((first, second) -> second)
                .orElse("");
    }

    private boolean hasToolResult(List<Map<String, Object>> messages, String toolName) {
        return messages.stream()
                .filter(msg -> "tool".equals(String.valueOf(msg.get("role"))))
                .anyMatch(msg -> String.valueOf(msg.get("name")).contains(toolName));
    }

    private boolean lastToolResultContains(List<Map<String, Object>> messages, String needle) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("tool".equals(String.valueOf(msg.get("role")))) {
                return String.valueOf(msg.get("content")).contains(needle);
            }
        }
        return false;
    }

    private LLMResponse toolCall(String name, Map<String, Object> arguments) {
        return new LLMResponse()
                .setContent("")
                .setFinishReason("tool_calls")
                .setToolCalls(List.of(new ToolCallRequest("smoke_call_" + toolCallIds.incrementAndGet(), name, arguments)))
                .setUsage(usage());
    }

    private LLMResponse response(String content) {
        return new LLMResponse(content)
                .setFinishReason("stop")
                .setUsage(usage());
    }

    private Map<String, Integer> usage() {
        return Map.of("prompt_tokens", 10, "completion_tokens", 6, "total_tokens", 16);
    }
}
