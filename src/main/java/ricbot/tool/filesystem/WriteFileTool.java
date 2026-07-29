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
 * 对应 Python: WriteFileTool
 *
 * 主要目标：
 * 1. 写入文件（覆盖写）
 * 2. 自动创建父目录
 * 3. 写完后更新 read state
 */
public class WriteFileTool extends Tool {
    @Override public ricbot.tool.api.ToolEffectPolicy effectPolicy() {
        return ricbot.tool.api.ToolEffectPolicy.idempotent(java.time.Duration.ofMinutes(2),
                ricbot.tool.api.ToolEffectPolicy.Approval.RISK_BASED);
    }

    /**
     * 工作空间根路径
     */
    private final Path workspace;

    /**
     * 允许访问的目录路径，用于安全校验
     */
    private final Path allowedDir;
    private final CommandRiskAnalyzer riskAnalyzer;
    private final ApprovalService approvalService;
    private final DiffReviewService diffReviewService;

    /**
     * 构造函数
     *
     * @param workspace 工作空间根路径
     * @param allowedDir 允许访问的目录路径，若为 null 则不进行特定目录限制
     */
    public WriteFileTool(Path workspace, Path allowedDir) {
        this(workspace, allowedDir, null, null);
    }

    public WriteFileTool(Path workspace, Path allowedDir, CommandRiskAnalyzer riskAnalyzer, ApprovalService approvalService) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
        this.riskAnalyzer = riskAnalyzer;
        this.approvalService = approvalService;
        this.diffReviewService = new DiffReviewService(workspace);
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称 "write_file"
     */
    @Override
    public String getName() {
        return "write_file";
    }

    /**
     * 获取工具描述
     *
     * @return 工具功能描述
     */
    @Override
    public String getDescription() {
        return "将内容写入文件（覆盖已有内容）。";
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数列表，包含文件路径和文件内容
     */
    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "要写入的文件路径", true),
                ToolParam.of("content", "string", "文件内容", true)
        );
    }

    private String write(String path, String content, boolean approved) {
        try {
            // 解析并规范化目标路径
            Path target = FileToolSupport.resolvePath(workspace, path);
            // 校验路径是否在允许范围内
            FileToolSupport.ensureAllowedForWrite(target, allowedDir, List.of());
            String riskGate = approved ? null : riskGate(target, path, content);
            if (riskGate != null) {
                return riskGate;
            }
            boolean existedBefore = Files.exists(target);
            String before = existedBefore && Files.isRegularFile(target) && !FileToolSupport.isBinary(target)
                    ? FileToolSupport.readText(target)
                    : "";

            // 写入文件内容
            FileToolSupport.writeText(target, content);
            // 记录写入状态，以便后续读取操作能感知到变更
            FileReadState.recordWrite(target);

            DiffReview review = diffReviewService.reviewWriteFile(
                    target.toString(),
                    before,
                    content != null ? content : "",
                    existedBefore,
                    CommandRiskLevel.MEDIUM
            );
            return "文件已写入：" + target + diffReviewService.renderMarkdown(review);
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
        String content = params != null ? (String) params.get("content") : null;
        return write(path, content, context != null && context.approved());
    }

    @Override
    public ToolRiskDecision assessRisk(Map<String, Object> params) {
        if (riskAnalyzer == null) return ToolRiskDecision.allow();
        try {
            String path = params != null ? String.valueOf(params.get("path")) : "";
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowedForWrite(target, allowedDir, List.of());
            return ToolRiskDecision.from(riskAnalyzer.analyzeTool(getName(), target.toString()));
        } catch (Exception e) {
            return new ToolRiskDecision(ToolRiskDecision.Decision.DENY, null, e.getMessage());
        }
    }

    private String riskGate(Path target, String path, String content) {
        if (riskAnalyzer == null) {
            return null;
        }
        RiskAssessment assessment = riskAnalyzer.analyzeTool("write_file", target.toString());
        if (assessment.blocked()) {
            return "错误：文件写入被风险策略拒绝。\n" + assessment.render();
        }
        if (assessment.requiresApproval()) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("path", path);
            arguments.put("content", content);
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
