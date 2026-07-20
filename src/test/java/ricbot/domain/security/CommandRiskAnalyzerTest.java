package ricbot.domain.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CommandRiskAnalyzerTest {

    @Test
    void analyzeExec_classifiesSafeLowMediumHighAndBlocked(@TempDir Path workspace) {
        CommandRiskAnalyzer analyzer = new CommandRiskAnalyzer(workspace);

        assertEquals(CommandRiskLevel.SAFE, analyzer.analyzeExec("git status", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.SAFE, analyzer.analyzeExec("grep -R hello src", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.LOW, analyzer.analyzeExec("./mvnw -q test", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.MEDIUM, analyzer.analyzeExec("mkdir reports", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.MEDIUM, analyzer.analyzeExec("git checkout feature", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.HIGH, analyzer.analyzeExec("rm build.log", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.HIGH, analyzer.analyzeExec("chmod 777 build.log", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.BLOCKED, analyzer.analyzeExec("sudo rm file", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.BLOCKED, analyzer.analyzeExec("rm -rf /", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.BLOCKED, analyzer.analyzeExec("cat /etc/passwd", workspace.toString()).riskLevel());
        assertEquals(CommandRiskLevel.BLOCKED, analyzer.analyzeExec("curl http://169.254.169.254/latest", workspace.toString()).riskLevel());
    }

    @Test
    void analyzeTool_classifiesFileTools(@TempDir Path workspace) {
        CommandRiskAnalyzer analyzer = new CommandRiskAnalyzer(workspace);

        RiskAssessment write = analyzer.analyzeTool("write_file", workspace.resolve("a.txt").toString());
        assertEquals(CommandRiskLevel.MEDIUM, write.riskLevel());
        assertTrue(write.requiresApproval());

        RiskAssessment edit = analyzer.analyzeTool("edit_file", workspace.resolve("a.txt").toString());
        assertEquals(CommandRiskLevel.MEDIUM, edit.riskLevel());

        RiskAssessment blocked = analyzer.analyzeTool("write_file", Path.of("/etc/passwd").toString());
        assertEquals(CommandRiskLevel.BLOCKED, blocked.riskLevel());
    }
}
