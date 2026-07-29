package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.MessageBus;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.runtime.SqliteSessionManager;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgentLoopToolCallTest {

    @AfterEach
    void closeRuntimeResources() {
        ricbot.app.bootstrap.RuntimeStoreRegistry.closeAll();
    }

    @Test
    void toolCall_isExecuted_andToolFailureIsRecorded(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SessionManager sessions = new SqliteSessionManager(new SqliteRuntimeStore(workspace));

        AtomicInteger call = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
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
                int n = call.incrementAndGet();
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("call tool")
                            .setToolCalls(List.of(new ToolCallRequest("call_1", "list_dir", Map.of("path", "."))))
                            .setFinishReason("tool_calls");
                }
                if (n == 2) {
                    assertEquals("tool", String.valueOf(messages.get(messages.size() - 1).get("role")));
                    return new LLMResponse().setContent("ok").setFinishReason("stop");
                }
                return new LLMResponse().setContent("unexpected").setFinishReason("stop");
            }
        };
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                200000,
                50,
                10_000,
                "standard",exec,true,
                sessions,
                "UTC",
                false,0
        );

        String sessionKey = "cli:direct";
        loop.processDirect("please list", sessionKey);

        Session s = sessions.getOrCreate(sessionKey);
        boolean sawTool = s.getMessages().stream().anyMatch(m -> "tool".equals(m.get("role")) && "list_dir".equals(m.get("name")));
        assertTrue(sawTool, s.getMessages().toString());

        AtomicInteger call2 = new AtomicInteger(0);
        LLMProvider provider2 = new LLMProvider("k", "http://localhost") {
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
                int n = call2.incrementAndGet();
                if (n == 1) {
                    return new LLMResponse()
                            .setContent("call missing tool")
                            .setToolCalls(List.of(new ToolCallRequest("call_2", "missing_tool", Map.of())))
                            .setFinishReason("tool_calls");
                }
                return new LLMResponse().setContent("ok").setFinishReason("stop");
            }
        };

        AgentLoop loop2 = new AgentLoop(
                new MessageBus(),
                provider2,
                workspace,
                "gpt-4o-mini",
                5,
                200000,
                50,
                10_000,
                "standard",exec,true,
                sessions,
                "UTC",
                false,0
        );

        String sessionKey2 = "cli:direct2";
        loop2.processDirect("please run missing tool", sessionKey2);

        Session s2 = sessions.getOrCreate(sessionKey2);
        boolean sawErrorTool = s2.getMessages().stream().anyMatch(m ->
                "tool".equals(m.get("role"))
                        && "missing_tool".equals(m.get("name"))
                        && String.valueOf(m.get("content")).contains("Tool 'missing_tool' not found")
        );
        assertTrue(sawErrorTool);
    }

    @Test
    void multiTurn_historyIsProvidedToProvider(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SessionManager sessions = new SqliteSessionManager(new SqliteRuntimeStore(workspace));

        AtomicInteger call = new AtomicInteger(0);
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
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
                int n = call.incrementAndGet();
                if (n == 1) {
                    return new LLMResponse().setContent("first").setFinishReason("stop");
                }
                boolean sawFirst = messages.stream().anyMatch(m -> "assistant".equals(m.get("role")) && "first".equals(m.get("content")));
                assertTrue(sawFirst);
                return new LLMResponse().setContent("second").setFinishReason("stop");
            }
        };
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                200000,
                50,
                10_000,
                "standard",exec,true,
                sessions,
                "UTC",
                false,0
        );

        String sessionKey = "cli:direct";
        loop.processDirect("hi", sessionKey);
        loop.processDirect("hi again", sessionKey);
        Session s = sessions.getOrCreate(sessionKey);
        assertTrue(s.getMessages().stream().anyMatch(m -> "assistant".equals(m.get("role")) && "second".equals(m.get("content"))));
    }

    @Test
    void statusCommand_readsTaskState(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SessionManager sessions = new SqliteSessionManager(new SqliteRuntimeStore(workspace));
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
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
                return new LLMResponse().setContent("done").setFinishReason("stop");
            }
        };
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                200000,
                50,
                10_000,
                "standard",exec,true,
                sessions,
                "UTC",
                false,0
        );

        String sessionKey = "cli:direct";
        loop.processDirect("帮我整理任务状态", sessionKey);
        var out = loop.processDirect("/status", sessionKey);

        assertTrue(out.getContent().contains("goal: 帮我整理任务状态"));
        assertTrue(out.getContent().contains("status: completed"));
        assertTrue(out.getContent().contains("context_usage"));
        assertTrue(out.getContent().contains("budget_usage_rate"));
    }

    @Test
    void contextCommand_readsLastContextTrace(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SessionManager sessions = new SqliteSessionManager(new SqliteRuntimeStore(workspace));
        LLMProvider provider = new LLMProvider("k", "http://localhost") {
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
                return new LLMResponse().setContent("done").setFinishReason("stop");
            }
        };
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                200000,
                50,
                10_000,
                "standard",exec,true,
                sessions,
                "UTC",
                false,0
        );

        String sessionKey = "cli:direct";
        loop.processDirect("帮我查看上下文报告", sessionKey);
        var summary = loop.processDirect("/context", sessionKey);
        assertTrue(summary.getContent().contains("ricbot context"), summary.getContent());
        assertTrue(summary.getContent().contains("total_tokens"), summary.getContent());
        assertTrue(summary.getContent().contains("sections"), summary.getContent());
        assertTrue(summary.getContent().contains("task_state"), summary.getContent());

        var detail = loop.processDirect("/context --detail", sessionKey);
        assertTrue(detail.getContent().contains("top sources"), detail.getContent());
        assertTrue(detail.getContent().contains("max_chars"), detail.getContent());

        var sources = loop.processDirect("/context --sources", sessionKey);
        assertTrue(sources.getContent().contains("top sources"), sources.getContent());
        assertFalse(sources.getContent().contains("sections\n"), sources.getContent());
    }
}
