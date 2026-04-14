package ricbot.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.core.message.InboundMessage;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;
import ricbot.core.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AgentLoopTest {

    @Test
    // 端到端测试：验证从 CLI 输入到会话持久化的完整流程
    void endToEnd_cliToSessionPersistence(@TempDir Path workspace) throws Exception {
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

        // 使用 start() 启动 AgentLoop（会自动启动 cronService 等）
        loop.start();
        try {
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
}

