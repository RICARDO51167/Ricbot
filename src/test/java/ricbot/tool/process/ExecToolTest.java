package ricbot.tool.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExecToolTest {

    @Test
    void largeOutput_doesNotDeadlockOrFalseTimeout(@TempDir Path workspace) {
        ExecTool tool = new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of());

        String result = tool.execute("i=0; while [ \"$i\" -lt 5000 ]; do echo line-$i; i=$((i+1)); done", null, 5);

        assertFalse(result.startsWith("错误：命令执行超时"), result);
        assertTrue(result.contains("line-0"), result);
    }

    @Test
    void hugeOutput_isDrainedButNotFullyCaptured(@TempDir Path workspace) {
        ExecTool tool = new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of());

        String result = tool.execute("i=0; while [ \"$i\" -lt 20000 ]; do echo line-$i; i=$((i+1)); done", null, 5);

        assertFalse(result.startsWith("错误：命令执行超时"), result);
        assertTrue(result.contains("line-0"), result);
        assertTrue(result.contains("已截断") || result.contains("输出过长"), result);
    }

    @Test
    void longRunningCommand_stillTimesOut(@TempDir Path workspace) {
        ExecTool tool = new ExecTool(1, workspace.toString(), null, null, true, "", "", List.of());

        String result = tool.execute("sleep 2", null, 1);

        assertTrue(result.startsWith("错误：命令执行超时"), result);
    }
}
