package ricbot.tool.filesystem;

import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.tool.api.Tool;
import ricbot.tool.api.Tool.ToolExecutionContext;
import ricbot.tool.api.ToolParam;
import ricbot.tool.api.ToolRiskDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件编辑工具类
 */
public class EditFileTool extends Tool {
    @Override public ricbot.tool.api.ToolEffectPolicy effectPolicy() {
        return ricbot.tool.api.ToolEffectPolicy.atMostOnce(java.time.Duration.ofMinutes(2),
                ricbot.tool.api.ToolEffectPolicy.Concurrency.SERIAL_PER_RUN,
                ricbot.tool.api.ToolEffectPolicy.Approval.RISK_BASED);
    }

    private final Path workspace;

    private final Path allowedDir;
    private final CommandRiskAnalyzer riskAnalyzer;
    private final ApprovalService approvalService;
    private final DiffReviewService diffReviewService;

    public EditFileTool(Path workspace, Path allowedDir) {
        this(workspace, allowedDir, null, null);
    }

    public EditFileTool(Path workspace, Path allowedDir, CommandRiskAnalyzer riskAnalyzer, ApprovalService approvalService) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
        this.riskAnalyzer = riskAnalyzer;
        this.approvalService = approvalService;
        this.diffReviewService = new DiffReviewService(workspace);
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "通过用 new_text 替换 old_text 来编辑文本文件。编辑前必须先读取文件。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "要编辑的文件路径", true),
                ToolParam.of("old_text", "string", "要被替换的文本", true),
                ToolParam.of("new_text", "string", "替换后的文本", true),
                ToolParam.of("replace_all", "boolean", "是否替换所有匹配项", false).setDefaultValue(false)
        );
    }

    private String edit(String path, String oldText, String newText, Boolean replaceAll, boolean approved) {
        try {
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());
            String riskGate = approved ? null : riskGate(target, path, oldText, newText, replaceAll);
            if (riskGate != null) {
                return riskGate;
            }

            if (!Files.exists(target)) {
                return "错误：文件不存在：" + target;
            }
            if (Files.isDirectory(target)) {
                return "错误：该路径是目录而非文件：" + target;
            }
            if (FileToolSupport.isBinary(target)) {
                return "错误：该文件疑似为二进制文件，无法按文本编辑。";
            }

            String warning = FileReadState.checkRead(target);
            if (warning != null) {
                return warning;
            }

            String content = FileToolSupport.readText(target);
            if (oldText == null || oldText.isEmpty()) {
                return "错误：待替换文本不能为空。";
            }

            boolean replaceAllFlag = replaceAll != null && replaceAll;
            String updated;

            if (!content.contains(oldText)) {
                return "错误：在文件中未找到待替换文本。";
            }

            if (replaceAllFlag) {
                updated = content.replace(oldText, newText != null ? newText : "");
            } else {
                updated = content.replaceFirst(
                        java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText != null ? newText : "")
                );
            }

            FileToolSupport.writeText(target, updated);
            FileReadState.recordWrite(target);

            DiffReview review = diffReviewService.reviewEditFile(target.toString(), content, updated, CommandRiskLevel.MEDIUM);
            return "Success: edited file " + target + diffReviewService.renderMarkdown(review);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Override
    public Object execute(Map<String, Object> params) {
        return execute(params, ToolExecutionContext.normal());
    }

    @Override
    public Object execute(Map<String, Object> params, ToolExecutionContext context) {
        String path = params != null ? (String) params.get("path") : null;
        String oldText = params != null ? (String) params.get("old_text") : null;
        String newText = params != null ? (String) params.get("new_text") : null;
        Boolean replaceAll = params != null ? (Boolean) params.get("replace_all") : null;
        return edit(path, oldText, newText, replaceAll, context != null && context.approved());
    }

    @Override
    public ToolRiskDecision assessRisk(Map<String, Object> params) {
        if (riskAnalyzer == null) return ToolRiskDecision.allow();
        try {
            String path = params != null ? String.valueOf(params.get("path")) : "";
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());
            return ToolRiskDecision.from(riskAnalyzer.analyzeTool(getName(), target.toString()));
        } catch (Exception e) {
            return new ToolRiskDecision(ToolRiskDecision.Decision.DENY, null, e.getMessage());
        }
    }

    private String riskGate(Path target, String path, String oldText, String newText, Boolean replaceAll) {
        if (riskAnalyzer == null) {
            return null;
        }
        RiskAssessment assessment = riskAnalyzer.analyzeTool("edit_file", target.toString());
        if (assessment.blocked()) {
            return "错误：文件编辑被风险策略拒绝。\n" + assessment.render();
        }
        if (assessment.requiresApproval()) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("path", path);
            arguments.put("old_text", oldText);
            arguments.put("new_text", newText);
            if (replaceAll != null) {
                arguments.put("replace_all", replaceAll);
            }
            ApprovalRequest request = approvalService != null
                    ? approvalService.createRequest(assessment, getName(), arguments, null)
                    : null;
            String requestId = request != null ? request.requestId() : "approval_unavailable";
            return "需要审批后才能执行。\nrequestId: " + requestId + "\n"
                    + assessment.render()
                    + "\n请使用 /approve " + requestId + " 或 /reject " + requestId + "。";
        }
        return null;
    }
}
