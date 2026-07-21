package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgentLoopTest {

    @Test
    // 端到端测试：验证从 CLI 输入到会话持久化的完整流程
    void endToEnd_cliToSessionPersistence(@TempDir Path workspace) throws Exception {
        Path legacyExperience = Files.createDirectories(workspace.resolve("experience")).resolve("verified.jsonl");
        Files.writeString(legacyExperience, "{\"legacy\":true}\n");
        // 创建消息总线，用于组件间通信
        MessageBus bus = new MessageBus();
        // 创建会话管理器，指定工作空间路径
        SessionManager sessionManager = new SessionManager(workspace);

        // 创建一个模拟的 LLM 提供者，用于测试中返回固定的响应
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
                // 返回内容为 "pong" 且结束原因为 "stop" 的响应
                return new LLMResponse().setContent("pong").setFinishReason("stop");
            }
        };

        // 配置 Web 工具，设置为禁用状态
        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        // 配置执行工具，设置为禁用状态
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        // 初始化 AgentLoop，传入各种配置参数
        AgentLoop loop = new AgentLoop(
                bus,                // 消息总线
                provider,           // LLM 提供者
                workspace,          // 工作空间路径
                "gpt-4o-mini",      // 模型名称
                5,                  // 最大迭代次数
                2000,               // 上下文窗口大小
                50,                 // 上下文块限制
                10_000,             // 最大工具结果字符数
                "standard",         // 重试模式
                web,                // Web 工具配置
                exec,               // 执行工具配置
                Map.of(),           // MCP 服务器
                true,               // 限制在工作空间
                sessionManager,     // 会话管理器
                "UTC",              // 时区
                false,              // 统一会话
                List.of(),          // 禁用技能
                0                   // 会话 TTL
        );

        // 使用 start() 启动 AgentLoop 及必要的后台组件
        loop.start();
        try {
            assertEquals("{\"legacy\":true}\n", Files.readString(legacyExperience));
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .map(String::toLowerCase)
                    .noneMatch(name -> name.contains("dream")
                            || name.contains("cron")
                            || name.contains("heartbeat")));

            // 创建一条来自 CLI 用户的入站消息，内容为 "ping"
            InboundMessage inbound = new InboundMessage("cli", "user", "direct", "ping");
            // 将入站消息发布到消息总线
            bus.publishInbound(inbound);

            // 从消息总线轮询出站消息，超时时间为 3000 毫秒
            OutboundMessage out = bus.pollOutbound(3000);
            // 断言出站消息不为空
            assertNotNull(out);
            // 断言出站消息的内容为 "pong"
            assertEquals("pong", out.getContent());

            // 解析会话目录路径
            Path sessionsDir = workspace.resolve("sessions");
            // 断言会话目录存在
            assertTrue(Files.exists(sessionsDir));
            // 断言会话目录中存在包含 "cli_direct" 的文件，验证会话持久化
            assertTrue(Files.list(sessionsDir).anyMatch(p -> p.getFileName().toString().contains("cli_direct")));
        } finally {
            // 停止 AgentLoop
            loop.stop();
        }
    }

    @Test
    void slashCommands_areRoutedWithoutInvokingProvider(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(workspace);
        AtomicInteger modelCalls = new AtomicInteger(0);

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
                modelCalls.incrementAndGet();
                return new LLMResponse().setContent("pong").setFinishReason("stop");
            }
        };

        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                2000,
                50,
                10_000,
                "standard",
                web,
                exec,
                Map.of(),
                true,
                sessionManager,
                "UTC",
                false,
                List.of(),
                0
        );

        OutboundMessage help = loop.processDirect("/help", "cli:direct");
        assertTrue(help.getContent().contains("ricbot 命令"));
        assertEquals(0, modelCalls.get());

        OutboundMessage removedDreamCommand = loop.processDirect("/dream", "cli:direct");
        assertTrue(removedDreamCommand.getContent().contains("command error: unknown command"));
        assertEquals(0, modelCalls.get());

        OutboundMessage removedExperienceCommand = loop.processDirect("/experience", "cli:direct");
        assertTrue(removedExperienceCommand.getContent().contains("command error: unknown command"));
        assertEquals(0, modelCalls.get());

        OutboundMessage missingReport = loop.processDirect("/team report teamtask_missing", "cli:direct");
        assertTrue(missingReport.getContent().contains("team error:"), missingReport.getContent());
        assertEquals(0, modelCalls.get());

        OutboundMessage unknownSlash = loop.processDirect("/definitely-unknown", "cli:direct");
        assertTrue(unknownSlash.getContent().contains("command error: unknown command"), unknownSlash.getContent());
        assertEquals(0, modelCalls.get());

        loop.processDirect("hello", "cli:direct");
        assertEquals(1, modelCalls.get());
        assertFalse(sessionManager.getOrCreate("cli:direct").getMessages().isEmpty());

        OutboundMessage reset = loop.processDirect("/new", "cli:direct");
        assertEquals("已开始新的会话。", reset.getContent());
        assertTrue(sessionManager.getOrCreate("cli:direct").getMessages().isEmpty());
    }
}
