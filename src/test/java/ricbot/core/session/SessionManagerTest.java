package ricbot.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SessionManagerTest {

    @Test
    // 测试保存和重新加载会话的一致性
    void saveAndReload_isConsistent(@TempDir Path workspace) throws Exception {
        // 创建 SessionManager 实例，使用临时目录作为工作空间
        SessionManager sm = new SessionManager(workspace);

        // 获取或创建一个名为 "cli:test" 的会话
        Session s = sm.getOrCreate("cli:test");
        // 添加一条用户消息
        s.addMessage("user", "hi");
        // 添加一条助手消息
        s.addMessage("assistant", "hello");
        // 保存会话到磁盘
        sm.save(s);

        // 解析会话存储目录路径
        Path sessionsDir = workspace.resolve("sessions");
        // 断言会话目录存在
        assertTrue(Files.exists(sessionsDir));
        // 断言目录中至少有一个以 .jsonl 结尾的文件
        assertTrue(Files.list(sessionsDir).anyMatch(p -> p.getFileName().toString().endsWith(".jsonl")));

        // 使内存中的会话失效，强制下次获取时从磁盘重新加载
        sm.invalidate("cli:test");
        // 重新获取会话，此时应从磁盘加载
        Session reloaded = sm.getOrCreate("cli:test");
        // 断言重新加载的会话包含两条消息
        assertEquals(2, reloaded.getMessages().size());
        // 断言第一条消息的角色是 "user"
        assertEquals("user", String.valueOf(reloaded.getMessages().get(0).get("role")));
        // 断言第二条消息的角色是 "assistant"
        assertEquals("assistant", String.valueOf(reloaded.getMessages().get(1).get("role")));
    }
}

