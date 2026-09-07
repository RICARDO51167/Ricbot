package ricbot.tool.filesystem;

import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolExecutionContext;
import ricbot.tool.api.BuiltinParameter;
import ricbot.tool.api.ToolRiskDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件编辑工具类
 */
public class EditFileTool extends ricbot.tool.api.BuiltinTool {
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
    public List<BuiltinParameter> getParams() {
        return List.of(
                BuiltinParameter.of("path", "string", "要编辑的文件路径", true).minLength(1),
                BuiltinParameter.of("old_text", "string", "要被替换的文本", true).minLength(1),
                BuiltinParameter.of("new_text", "string", "替换后的文本", true),
                BuiltinParameter.of("replace_all", "boolean", "是否替换所有匹配项", false).defaultValue(false)
        );
    }

    private String edit(String path, String oldText, String newText, Boolean replaceAll, ToolExecutionContext context) {
        try {
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());
            if (!Files.exists(target)) {
                return "错误：文件不存在：" + target;
            }
            if (Files.isDirectory(target)) {
                return "错误：该路径是目录而非文件：" + target;
            }
            if (FileToolSupport.isBinary(target)) {
                return "错误：该文件疑似为二进制文件，无法按文本编辑。";
            }

            String content = FileToolSupport.readText(target);
            String currentSha = FileToolSupport.sha256(target);
            if (oldText == null || oldText.isEmpty()) {
                return "错误：待替换文本不能为空。";
            }

            boolean replaceAllFlag = replaceAll != null && replaceAll;
            String updated;

            if (!content.contains(oldText)) {
                return "错误：在文件中未找到待替换文本。";
            }

            int matches = count(content, oldText);
            if (!replaceAllFlag && matches != 1) {
                return "错误：old_text 必须恰好匹配一次；当前匹配 " + matches + " 次。";
            }
            String logical = workspace.toAbsolutePath().normalize().relativize(target).toString();
            if (!hasReceipt(context, logical, currentSha, content, oldText, replaceAllFlag)) {
                return "错误：缺少覆盖目标内容且 SHA 匹配的 FileReadReceipt，请先读取文件。";
            }

            if (replaceAllFlag) {
                updated = content.replace(oldText, newText != null ? newText : "");
            } else {
                updated = content.replaceFirst(
                        java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText != null ? newText : "")
                );
            }

            FileToolSupport.compareAndWrite(target, currentSha, updated,
                    checked -> FileToolSupport.ensureAllowedForWrite(checked, allowedDir, List.of()));

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
        return edit(path, oldText, newText, replaceAll, context);
    }

    @Override public List<String> resourceKeys(ricbot.tool.api.ToolInvocation invocation, ToolExecutionContext context) {
        try { return List.of("file:" + context.workspaceId() + ":" +
                FileToolSupport.resolvePath(workspace, String.valueOf(invocation.arguments().get("path"))).normalize()); }
        catch (Exception ignored) { return List.of("workspace:" + context.workspaceId()); }
    }

    @Override public ricbot.tool.api.ToolResult execute(ricbot.tool.api.ToolInvocation invocation,
                                                        ToolExecutionContext context,
                                                        ricbot.tool.api.ToolChunkSink chunks) {
        Object value = execute(invocation.arguments(), context);
        if (value instanceof String text && text.startsWith("错误")) {
            return new ricbot.tool.api.ToolResult.Failure("EDIT_FAILED", text, false, List.of());
        }
        try {
            Path target = FileToolSupport.resolvePath(workspace, String.valueOf(invocation.arguments().get("path")));
            String logical = workspace.toAbsolutePath().normalize().relativize(target).toString();
            String digest = FileToolSupport.sha256(target);
            long lines; try (var stream = Files.lines(target)) { lines = stream.count(); }
            FileReadReceipt receipt = new FileReadReceipt(context.runId(), context.taskId(), context.workspaceId(),
                    logical, digest, 1, (int) Math.max(1, lines), true, Files.size(target), "edit_file", java.time.Instant.now());
            java.util.Set<String> invalid = context.fileReadReceipts().entrySet().stream()
                    .filter(entry -> { FileReadReceipt old = FileReadReceipt.from(entry.getValue()); return old != null && logical.equals(old.logicalPath()); })
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            return new ricbot.tool.api.ToolResult.Success(value, String.valueOf(value), List.of(
                    new ricbot.tool.api.ToolStateMutation.InvalidateFileReadReceipts(invalid, "file content changed"),
                    new ricbot.tool.api.ToolStateMutation.RecordFileReadReceipt(receipt.key(), receipt.toMap())), Map.of());
        } catch (Exception failure) { return new ricbot.tool.api.ToolResult.Failure("RECEIPT_FAILED", failure.getMessage(), false, List.of()); }
    }

    private static int count(String content, String needle) {
        int count = 0, from = 0; while ((from = content.indexOf(needle, from)) >= 0) { count++; from += needle.length(); } return count;
    }
    private static boolean hasReceipt(ToolExecutionContext context, String logical, String sha,
                                      String content, String needle, boolean replaceAll) {
        if (context == null) return false;
        if (Boolean.FALSE.equals(context.backendCapabilities().get("requireReadReceipt"))) return true;
        List<FileReadReceipt> receipts = context.fileReadReceipts().values().stream().map(FileReadReceipt::from)
                .filter(java.util.Objects::nonNull)
                .filter(receipt -> context.runId().equals(receipt.runId())
                        && context.workspaceId().equals(receipt.workspaceId())
                        && logical.equals(receipt.logicalPath()) && sha.equals(receipt.sha256())).toList();
        if (receipts.isEmpty()) return false;
        int from = 0;
        do {
            int match = content.indexOf(needle, from);
            if (match < 0) return from > 0;
            int startLine = 1 + countNewlines(content, 0, match);
            int endLine = startLine + countNewlines(content, match, match + needle.length());
            boolean covered = receipts.stream().anyMatch(receipt -> receipt.startLine() <= startLine
                    && receipt.endLine() >= endLine);
            if (!covered) return false;
            from = match + needle.length();
            if (!replaceAll) return true;
        } while (from <= content.length());
        return true;
    }

    private static int countNewlines(String value, int start, int end) {
        int count = 0;
        for (int index = Math.max(0, start); index < Math.min(value.length(), end); index++)
            if (value.charAt(index) == '\n') count++;
        return count;
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
