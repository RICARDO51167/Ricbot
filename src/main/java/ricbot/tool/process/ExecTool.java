package ricbot.tool.process;

import ricbot.tool.api.Tool;
import ricbot.tool.api.Tool.ToolExecutionContext;
import ricbot.tool.api.ToolParam;
import ricbot.infra.config.RuntimePaths;
import ricbot.infra.security.NetworkSecurity;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.infra.execution.ExecutionBackend;
import ricbot.infra.execution.ExecutionRequest;
import ricbot.infra.execution.ExecutionResult;
import ricbot.infra.execution.LocalExecutionBackend;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应 Python: ExecTool
 *
 * 主要目标：
 * 1. 安全执行 shell 命令
 * 2. 阻止危险命令
 * 3. 阻止内部 URL SSRF
 * 4. 限制 working_dir 越界
 * 5. 控制 timeout / output 长度
 */
public class ExecTool extends Tool {

    // 判断当前操作系统是否为 Windows
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    // 最大超时时间（秒）
    private static final int MAX_TIMEOUT = 600;
    // 最大输出字符数
    private static final int MAX_OUTPUT = 10_000;
    private static final int MAX_CAPTURE_BYTES = 64 * 1024;

    // 默认超时时间
    private final int timeout;
    // 默认工作目录
    private final String workingDir;
    // 禁止的命令正则模式列表
    private final List<String> denyPatterns;
    // 允许的命令正则模式列表
    private final List<String> allowPatterns;
    // 是否限制在工作区范围内
    private final boolean restrictToWorkspace;
    // 沙箱配置（当前未完全实现）
    private final String sandbox;
    // PATH 环境变量追加内容
    private final String pathAppend;
    // 允许传递的环境变量键名列表
    private final List<String> allowedEnvKeys;
    private final CommandRiskAnalyzer riskAnalyzer;
    private final ApprovalService approvalService;
    private final ExecutionBackend executionBackend;

    /**
     * 构造函数
     *
     * @param timeout           超时时间（秒），如果小于等于0则默认为60
     * @param workingDir        工作目录
     * @param denyPatterns      禁止的命令模式列表，如果为null则使用默认列表
     * @param allowPatterns     允许的命令模式列表，如果为null则初始化为空列表
     * @param restrictToWorkspace 是否限制工作目录在工作区内
     * @param sandbox           沙箱配置字符串
     * @param pathAppend        需要追加到 PATH 的路径
     * @param allowedEnvKeys    允许保留的环境变量键名列表
     */
    public ExecTool(
            int timeout,
            String workingDir,
            List<String> denyPatterns,
            List<String> allowPatterns,
            boolean restrictToWorkspace,
            String sandbox,
            String pathAppend,
            List<String> allowedEnvKeys
    ) {
        this(timeout, workingDir, denyPatterns, allowPatterns, restrictToWorkspace, sandbox,
                pathAppend, allowedEnvKeys, null, null, new LocalExecutionBackend());
    }

    public ExecTool(
            int timeout,
            String workingDir,
            List<String> denyPatterns,
            List<String> allowPatterns,
            boolean restrictToWorkspace,
            String sandbox,
            String pathAppend,
            List<String> allowedEnvKeys,
            CommandRiskAnalyzer riskAnalyzer,
            ApprovalService approvalService
    ) {
        this(timeout, workingDir, denyPatterns, allowPatterns, restrictToWorkspace, sandbox,
                pathAppend, allowedEnvKeys, riskAnalyzer, approvalService, new LocalExecutionBackend());
    }

    public ExecTool(
            int timeout,
            String workingDir,
            List<String> denyPatterns,
            List<String> allowPatterns,
            boolean restrictToWorkspace,
            String sandbox,
            String pathAppend,
            List<String> allowedEnvKeys,
            CommandRiskAnalyzer riskAnalyzer,
            ApprovalService approvalService,
            ExecutionBackend executionBackend
    ) {
        this.timeout = timeout > 0 ? timeout : 60;
        this.workingDir = workingDir;
        this.sandbox = sandbox != null ? sandbox : "";
        this.denyPatterns = denyPatterns != null ? denyPatterns : defaultDenyPatterns();
        this.allowPatterns = allowPatterns != null ? allowPatterns : new ArrayList<>();
        this.restrictToWorkspace = restrictToWorkspace;
        this.pathAppend = pathAppend != null ? pathAppend : "";
        this.allowedEnvKeys = allowedEnvKeys != null ? allowedEnvKeys : new ArrayList<>();
        this.riskAnalyzer = riskAnalyzer;
        this.approvalService = approvalService;
        this.executionBackend = executionBackend != null ? executionBackend : new LocalExecutionBackend();
    }

    @Override
    public String getName() {
        return "exec";
    }

    @Override
    public String getDescription() {
        return "执行一条 shell 命令并返回输出。输出最多保留 10,000 字符；默认超时 60 秒。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("command", "string", "要执行的 shell 命令", true),
                ToolParam.of("working_dir", "string", "工作目录（默认工作区）", false),
                ToolParam.of("timeout", "integer", "超时秒数（最大 600）", false)
                        .setExtraSchema(Map.of("minimum", 1, "maximum", MAX_TIMEOUT))
        );
    }

    @Override
    public boolean isExclusive() {
        return true;
    }

    /**
     * 执行入口方法，处理参数映射
     *
     * @param kwargs 参数字典
     * @return 执行结果或错误信息
     * @throws Exception 异常
     */
    @Override
    public Object execute(Map<String, Object> kwargs) throws Exception {
        return execute(kwargs, ToolExecutionContext.normal());
    }

    @Override
    public Object execute(Map<String, Object> kwargs, ToolExecutionContext context) throws Exception {
        if (kwargs == null) {
            return "错误：缺少参数";
        }
        Object cmdObj = kwargs.get("command");
        if (!(cmdObj instanceof String cmd) || cmd.isBlank()) {
            return "错误：缺少必填参数 'command'";
        }

        String workingDirOverride = null;
        Object wd = kwargs.get("working_dir");
        if (wd != null) {
            workingDirOverride = String.valueOf(wd);
        }

        Integer timeoutOverride = null;
        Object t = kwargs.get("timeout");
        if (t instanceof Number n) {
            timeoutOverride = n.intValue();
        }

        return execute(cmd, workingDirOverride, timeoutOverride, context != null && context.approved());
    }

    /**
     * 核心执行逻辑
     *
     * @param command          要执行的命令
     * @param workingDirOverride 覆盖的工作目录
     * @param timeoutOverride  覆盖的超时时间
     * @return 执行结果字符串
     */
    public String execute(String command, String workingDirOverride, Integer timeoutOverride) {
        return execute(command, workingDirOverride, timeoutOverride, false);
    }

    private String execute(String command, String workingDirOverride, Integer timeoutOverride, boolean approved) {
        // 确定最终的工作目录：优先使用传入的覆盖值，其次是配置的工作目录，最后是系统用户目录
        String cwd = firstNonBlank(workingDirOverride, this.workingDir, System.getProperty("user.dir"));

        // restrict_to_workspace + working_dir 越界保护
        if (restrictToWorkspace && this.workingDir != null && !this.workingDir.isBlank()) {
            try {
                Path requested = Path.of(cwd).toAbsolutePath().normalize();
                Path workspaceRoot = Path.of(this.workingDir).toAbsolutePath().normalize();

                if (!requested.equals(workspaceRoot) && !requested.startsWith(workspaceRoot)) {
                    return "错误：working_dir 超出配置的工作区范围";
                }
            } catch (Exception e) {
                return "错误：无法解析 working_dir";
            }
        }

        String riskGate = approved ? null : riskGate(command, cwd, workingDirOverride, timeoutOverride);
        if (riskGate != null) {
            return riskGate;
        }

        // 检查命令安全性
        String guardError = guardCommand(command, cwd);
        if (guardError != null) {
            return guardError;
        }

        String effectiveCommand = command;
        String effectiveCwd = cwd;

        if (sandbox != null && !sandbox.isBlank()) {
            try {
                effectiveCommand = wrapSandboxCommand(sandbox, effectiveCommand, this.workingDir, effectiveCwd);
            } catch (Exception e) {
                return "错误：sandbox 配置不可用：" + e.getMessage();
            }
        }

        // 计算有效超时时间，不超过最大值
        int effectiveTimeout = Math.min(
                timeoutOverride != null ? timeoutOverride : this.timeout,
                MAX_TIMEOUT
        );

        // 构建环境变量
        Map<String, String> env = buildEnv();

        try {
            ExecutionResult execution = executionBackend.execute(new ExecutionRequest(
                    effectiveCommand,
                    Path.of(effectiveCwd),
                    env,
                    java.time.Duration.ofSeconds(effectiveTimeout),
                    MAX_CAPTURE_BYTES
            ));
            if (execution.timedOut()) {
                return "错误：命令执行超时（" + effectiveTimeout + " 秒，backend="
                        + execution.backend() + "）";
            }
            StringBuilder output = new StringBuilder();
            if (!execution.stdout().isBlank()) output.append(execution.stdout());
            if (!execution.stderr().isBlank()) {
                if (!output.isEmpty()) output.append('\n');
                output.append(execution.stderr());
            }
            if (execution.truncated()) {
                if (!output.isEmpty()) output.append('\n');
                output.append("...（输出过长，已停止继续保留完整内容）");
            }
            String rendered = output.toString().trim();
            if (rendered.isBlank()) rendered = "（无输出）";
            if (rendered.length() > MAX_OUTPUT) rendered = rendered.substring(0, MAX_OUTPUT) + "\n...（已截断）";
            return execution.exitCode() != 0
                    ? "[退出码 " + execution.exitCode() + ", backend=" + execution.backend() + "]\n" + rendered
                    : rendered;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    private String riskGate(String command, String cwd, String workingDirOverride, Integer timeoutOverride) {
        if (riskAnalyzer == null) {
            return null;
        }
        RiskAssessment assessment = riskAnalyzer.analyzeExec(command, cwd);
        if (assessment.blocked()) {
            return "错误：命令被风险策略拒绝。\n" + assessment.render();
        }
        if (assessment.riskLevel() == CommandRiskLevel.MEDIUM || assessment.riskLevel() == CommandRiskLevel.HIGH) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("command", command);
            if (workingDirOverride != null) {
                arguments.put("working_dir", workingDirOverride);
            }
            if (timeoutOverride != null) {
                arguments.put("timeout", timeoutOverride);
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

    /**
     * 构建执行进程的环境变量
     *
     * @return 环境变量映射
     */
    private Map<String, String> buildEnv() {
        Map<String, String> env = new LinkedHashMap<>();

        // 只保留尽可能少的默认变量
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("HOME", System.getenv().getOrDefault("HOME", ""));
        env.put("USER", System.getenv().getOrDefault("USER", ""));
        env.put("LANG", System.getenv().getOrDefault("LANG", "C.UTF-8"));
        env.put("TERM", System.getenv().getOrDefault("TERM", "dumb"));

        // 添加用户允许的环境变量
        for (String key : allowedEnvKeys) {
            String val = System.getenv(key);
            if (val != null) {
                env.put(key, val);
            }
        }

        // 如果需要，追加额外的路径到 PATH
        if (pathAppend != null && !pathAppend.isBlank()) {
            if (IS_WINDOWS) {
                env.put("PATH", env.getOrDefault("PATH", "") + ";" + pathAppend);
            } else {
                env.put("PATH", env.getOrDefault("PATH", "") + ":" + pathAppend);
            }
        }

        return env;
    }

    /**
     * 对应 Python: _guard_command(...)
     * 检查命令是否安全
     *
     * @param command 命令字符串
     * @param cwd     当前工作目录
     * @return 如果不安全返回错误信息，否则返回 null
     */
    private String guardCommand(String command, String cwd) {
        String cmd = command != null ? command.trim() : "";
        String lower = cmd.toLowerCase(Locale.ROOT);

        // 检查禁止模式
        for (String pattern : denyPatterns) {
            if (Pattern.compile(pattern).matcher(lower).find()) {
                return "错误：命令被安全防护拦截（检测到危险模式）";
            }
        }

        // 检查允许模式（如果配置了允许列表）
        if (allowPatterns != null && !allowPatterns.isEmpty()) {
            boolean allowed = false;
            for (String pattern : allowPatterns) {
                if (Pattern.compile(pattern).matcher(lower).find()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return "错误：命令被安全防护拦截（不在允许列表中）";
            }
        }

        // 检查是否包含内网 URL
        if (NetworkSecurity.containsInternalUrl(cmd)) {
            return "错误：命令被安全防护拦截（检测到内网/私有 URL）";
        }

        // 如果限制在工作区，检查路径穿越和绝对路径访问
        if (restrictToWorkspace) {
            if (cmd.contains("..\\") || cmd.contains("../")) {
                return "错误：命令被安全防护拦截（检测到路径穿越）";
            }

            Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
            Path mediaPath = RuntimePaths.getMediaDir().toAbsolutePath().normalize();

            for (String raw : extractAbsolutePaths(cmd)) {
                try {
                    String expanded = expandEnv(raw.trim());
                    Path p = Path.of(expanded.replaceFirst("^~", System.getProperty("user.home")))
                            .toAbsolutePath()
                            .normalize();

                    if (p.isAbsolute()
                            && !p.equals(cwdPath)
                            && !p.startsWith(cwdPath)
                            && !p.equals(mediaPath)
                            && !p.startsWith(mediaPath)) {
                        return "错误：命令被安全防护拦截（路径超出工作目录范围）";
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    /**
     * 对应 Python: _extract_absolute_paths(...)
     * 从命令中提取绝对路径
     *
     * @param command 命令字符串
     * @return 提取到的路径列表
     */
    private List<String> extractAbsolutePaths(String command) {
        List<String> result = new ArrayList<>();

        // 匹配 Windows 绝对路径 (例如 C:\...)
        Matcher win = Pattern.compile("[A-Za-z]:\\\\[^\\s\"'|><;]*").matcher(command);
        while (win.find()) {
            result.add(win.group());
        }

        // 匹配 Unix 绝对路径 (例如 /usr/bin)
        Matcher posix = Pattern.compile("(?:^|[\\s|>'\"])(/[^\\s\"'>;|<]+)").matcher(command);
        while (posix.find()) {
            result.add(posix.group(1));
        }

        // 匹配家目录路径 (例如 ~/...)
        Matcher home = Pattern.compile("(?:^|[\\s|>'\"])(~[^\\s\"'>;|<]*)").matcher(command);
        while (home.find()) {
            result.add(home.group(1));
        }

        return result;
    }

    /**
     * 构建 ProcessBuilder
     *
     * @param command 命令
     * @param cwd     工作目录
     * @param env     环境变量
     * @return ProcessBuilder 实例
     */
    private ProcessBuilder buildProcess(String command, String cwd, Map<String, String> env) {
        List<String> cmd;
        if (IS_WINDOWS) {
            cmd = List.of("cmd.exe", "/c", command);
        } else {
            cmd = List.of("/bin/sh", "-lc", command);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(cwd).toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        return pb;
    }

    /**
     * 强制杀死进程
     *
     * @param process 进程对象
     */
    private void killProcess(Process process) {
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    /**
     * 读取输入流所有内容
     *
     * @param in 输入流
     * @return 字符串内容
     * @throws Exception 异常
     */
    private StreamOutput readAll(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int stored = 0;
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (stored < MAX_CAPTURE_BYTES) {
                    int keep = Math.min(read, MAX_CAPTURE_BYTES - stored);
                    out.write(buffer, 0, keep);
                    stored += keep;
                    if (keep < read) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }
            return new StreamOutput(out.toString(StandardCharsets.UTF_8), truncated);
        }
    }

    private void awaitDrain(Future<?> future, long timeout, TimeUnit unit) {
        if (future == null) {
            return;
        }
        try {
            future.get(timeout, unit);
        } catch (Exception ignored) {
            future.cancel(true);
        }
    }

    private record StreamOutput(String text, boolean truncated) {
    }

    private String wrapSandboxCommand(String sandbox, String command, String workspace, String cwd) {
        String mode = sandbox != null ? sandbox.trim().toLowerCase(Locale.ROOT) : "";
        if (mode.isBlank() || "off".equals(mode) || "none".equals(mode)) {
            return command;
        }
        if (IS_WINDOWS) {
            throw new IllegalStateException("Windows 平台暂未支持 sandbox 执行");
        }

        if ("sandbox".equals(mode) || "auto".equals(mode)) {
            if (commandExists("sandbox-exec")) {
                return wrapWithMacSandbox(command, workspace, cwd);
            }
            if (commandExists("bwrap")) {
                return wrapWithBubblewrap(command, workspace, cwd);
            }
            throw new IllegalStateException("未找到可用的 sandbox 工具（需要 sandbox-exec 或 bwrap）");
        }

        if (mode.contains("sandbox-exec") || mode.contains("seatbelt") || mode.contains("mac")) {
            if (!commandExists("sandbox-exec")) {
                throw new IllegalStateException("sandbox-exec 不可用");
            }
            return wrapWithMacSandbox(command, workspace, cwd);
        }

        if (mode.contains("bwrap") || mode.contains("bubblewrap")) {
            if (!commandExists("bwrap")) {
                throw new IllegalStateException("bwrap 不可用");
            }
            return wrapWithBubblewrap(command, workspace, cwd);
        }

        throw new IllegalStateException("未知 sandbox 模式：" + sandbox);
    }

    private String wrapWithMacSandbox(String command, String workspace, String cwd) {
        try {
            Path profile = Files.createTempFile("ricbot-sandbox-", ".sb");
            profile.toFile().deleteOnExit();
            String writableRoot = firstNonBlank(cwd, workspace, System.getProperty("user.dir"));
            StringBuilder policy = new StringBuilder();
            policy.append("(version 1)\n");
            policy.append("(deny default)\n");
            policy.append("(import \"system.sb\")\n");
            policy.append("(allow process-exec)\n");
            policy.append("(allow process-fork)\n");
            policy.append("(allow file-read*)\n");
            policy.append("(allow file-write* (subpath ").append(escapeSandboxPath(writableRoot)).append("))\n");
            policy.append("(deny network*)\n");
            Files.writeString(profile, policy.toString(), StandardCharsets.UTF_8);
            return "sandbox-exec -f " + shellQuote(profile.toString()) + " /bin/sh -lc " + shellQuote(command);
        } catch (Exception e) {
            throw new IllegalStateException("生成 macOS sandbox 配置失败: " + e.getMessage(), e);
        }
    }

    private String wrapWithBubblewrap(String command, String workspace, String cwd) {
        String root = firstNonBlank(workspace, cwd, System.getProperty("user.dir"));
        String effectiveCwd = firstNonBlank(cwd, root);
        return "bwrap"
                + " --die-with-parent"
                + " --unshare-net"
                + " --ro-bind /usr /usr"
                + " --ro-bind /bin /bin"
                + " --ro-bind /lib /lib"
                + " --ro-bind /lib64 /lib64"
                + " --ro-bind /etc /etc"
                + " --proc /proc"
                + " --dev /dev"
                + " --bind " + shellQuote(root) + " " + shellQuote(root)
                + " --chdir " + shellQuote(effectiveCwd)
                + " /bin/sh -lc " + shellQuote(command);
    }

    private boolean commandExists(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String path = System.getenv().getOrDefault("PATH", "");
        if (pathAppend != null && !pathAppend.isBlank()) {
            path = path + (IS_WINDOWS ? ";" : ":") + pathAppend;
        }
        String sep = IS_WINDOWS ? ";" : ":";
        for (String dir : path.split(Pattern.quote(sep))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, command);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String escapeSandboxPath(String value) {
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String shellQuote(String value) {
        String s = value != null ? value : "";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 展开环境变量引用
     *
     * @param raw 原始字符串
     * @return 展开后的字符串
     */
    private String expandEnv(String raw) {
        String result = raw;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            result = result.replace("$" + entry.getKey(), entry.getValue());
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 获取第一个非空字符串
     *
     * @param values 字符串数组
     * @return 第一个非空字符串，如果没有则返回空字符串
     */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 获取默认的禁止命令模式列表
     *
     * @return 禁止模式列表
     */
    private static List<String> defaultDenyPatterns() {
        return List.of(
                "\\brm\\s+-[rf]{1,2}\\b",
                "\\bdel\\s+/[fq]\\b",
                "\\brmdir\\s+/s\\b",
                "(?:^|[;&|]\\s*)format\\b",
                "\\b(mkfs|diskpart)\\b",
                "\\bdd\\s+if=",
                ">\\s*/dev/sd",
                "\\b(shutdown|reboot|poweroff)\\b",
                ":\\(\\)\\s*\\{.*\\};\\s*:",
                ">>?\\s*\\S*(?:history\\.jsonl|\\.dream_cursor)",
                "\\btee\\b[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)",
                "\\b(?:cp|mv)\\b(?:\\s+[^\\s|;&<>]+)+\\s+\\S*(?:history\\.jsonl|\\.dream_cursor)",
                "\\bdd\\b[^|;&<>]*\\bof=\\S*(?:history\\.jsonl|\\.dream_cursor)",
                "\\bsed\\s+-i[^|;&<>]*(?:history\\.jsonl|\\.dream_cursor)"
        );
    }
}
