package ricbot.tool.process;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.infra.config.RuntimePaths;
import ricbot.infra.security.NetworkSecurity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
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

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private static final int MAX_TIMEOUT = 600;
    private static final int MAX_OUTPUT = 10_000;

    private final int timeout;
    private final String workingDir;
    private final List<String> denyPatterns;
    private final List<String> allowPatterns;
    private final boolean restrictToWorkspace;
    private final String sandbox;
    private final String pathAppend;
    private final List<String> allowedEnvKeys;

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
        this.timeout = timeout > 0 ? timeout : 60;
        this.workingDir = workingDir;
        this.sandbox = sandbox != null ? sandbox : "";
        this.denyPatterns = denyPatterns != null ? denyPatterns : defaultDenyPatterns();
        this.allowPatterns = allowPatterns != null ? allowPatterns : new ArrayList<>();
        this.restrictToWorkspace = restrictToWorkspace;
        this.pathAppend = pathAppend != null ? pathAppend : "";
        this.allowedEnvKeys = allowedEnvKeys != null ? allowedEnvKeys : new ArrayList<>();
    }

    @Override
    public String getName() {
        return "exec";
    }

    @Override
    public String getDescription() {
        return "Execute a shell command and return its output. Output is truncated at 10 000 chars; timeout defaults to 60s.";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("command", "string", "Shell command to execute", true),
                ToolParam.of("working_dir", "string", "Working directory (defaults to workspace)", false),
                ToolParam.of("timeout", "integer", "Timeout in seconds (max 600)", false)
                        .setExtraSchema(Map.of("minimum", 1, "maximum", MAX_TIMEOUT))
        );
    }

    @Override
    public boolean isExclusive() {
        return true;
    }

    @Override
    public Object execute(Map<String, Object> kwargs) throws Exception {
        if (kwargs == null) {
            return "Error: missing parameters";
        }
        Object cmdObj = kwargs.get("command");
        if (!(cmdObj instanceof String cmd) || cmd.isBlank()) {
            return "Error: missing required parameter 'command'";
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

        return execute(cmd, workingDirOverride, timeoutOverride);
    }

    public String execute(String command, String workingDirOverride, Integer timeoutOverride) {
        String cwd = firstNonBlank(workingDirOverride, this.workingDir, System.getProperty("user.dir"));

        // restrict_to_workspace + working_dir 越界保护
        if (restrictToWorkspace && this.workingDir != null && !this.workingDir.isBlank()) {
            try {
                Path requested = Path.of(cwd).toAbsolutePath().normalize();
                Path workspaceRoot = Path.of(this.workingDir).toAbsolutePath().normalize();

                if (!requested.equals(workspaceRoot) && !requested.startsWith(workspaceRoot)) {
                    return "Error: working_dir is outside the configured workspace";
                }
            } catch (Exception e) {
                return "Error: working_dir could not be resolved";
            }
        }

        String guardError = guardCommand(command, cwd);
        if (guardError != null) {
            return guardError;
        }

        String effectiveCommand = command;
        String effectiveCwd = cwd;

        // sandbox 占位
        if (sandbox != null && !sandbox.isBlank()) {
            if (IS_WINDOWS) {
                // 与 Python 一致：Windows 下不支持 sandbox 时降级
            } else {
                // 这里先保留 hook，后续你再接 wrap_command
                effectiveCommand = wrapSandboxCommand(sandbox, command, this.workingDir != null ? this.workingDir : cwd, cwd);
                effectiveCwd = Path.of(this.workingDir != null ? this.workingDir : cwd).toAbsolutePath().normalize().toString();
            }
        }

        int effectiveTimeout = Math.min(
                timeoutOverride != null ? timeoutOverride : this.timeout,
                MAX_TIMEOUT
        );

        Map<String, String> env = buildEnv();

        try {
            ProcessBuilder pb = buildProcess(effectiveCommand, effectiveCwd, env);
            Process process = pb.start();

            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!finished) {
                killProcess(process);
                return "Error: Command timed out after " + effectiveTimeout + " seconds";
            }

            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());

            StringBuilder output = new StringBuilder();

            if (stdout != null && !stdout.isBlank()) {
                output.append(stdout);
            }
            if (stderr != null && !stderr.isBlank()) {
                if (!output.isEmpty()) {
                    output.append("\n");
                }
                output.append(stderr);
            }

            String result = output.toString().trim();
            if (result.isBlank()) {
                result = "(no output)";
            }

            if (result.length() > MAX_OUTPUT) {
                result = result.substring(0, MAX_OUTPUT) + "\n... (truncated)";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "[exit " + exitCode + "]\n" + result;
            }
            return result;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private Map<String, String> buildEnv() {
        Map<String, String> env = new LinkedHashMap<>();

        // 只保留尽可能少的默认变量
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("HOME", System.getenv().getOrDefault("HOME", ""));
        env.put("USER", System.getenv().getOrDefault("USER", ""));
        env.put("LANG", System.getenv().getOrDefault("LANG", "C.UTF-8"));
        env.put("TERM", System.getenv().getOrDefault("TERM", "dumb"));

        for (String key : allowedEnvKeys) {
            String val = System.getenv(key);
            if (val != null) {
                env.put(key, val);
            }
        }

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
     */
    private String guardCommand(String command, String cwd) {
        String cmd = command != null ? command.trim() : "";
        String lower = cmd.toLowerCase(Locale.ROOT);

        for (String pattern : denyPatterns) {
            if (Pattern.compile(pattern).matcher(lower).find()) {
                return "Error: Command blocked by safety guard (dangerous pattern detected)";
            }
        }

        if (allowPatterns != null && !allowPatterns.isEmpty()) {
            boolean allowed = false;
            for (String pattern : allowPatterns) {
                if (Pattern.compile(pattern).matcher(lower).find()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return "Error: Command blocked by safety guard (not in allowlist)";
            }
        }

        if (NetworkSecurity.containsInternalUrl(cmd)) {
            return "Error: Command blocked by safety guard (internal/private URL detected)";
        }

        if (restrictToWorkspace) {
            if (cmd.contains("..\\") || cmd.contains("../")) {
                return "Error: Command blocked by safety guard (path traversal detected)";
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
                        return "Error: Command blocked by safety guard (path outside working dir)";
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    /**
     * 对应 Python: _extract_absolute_paths(...)
     */
    private List<String> extractAbsolutePaths(String command) {
        List<String> result = new ArrayList<>();

        Matcher win = Pattern.compile("[A-Za-z]:\\\\[^\\s\"'|><;]*").matcher(command);
        while (win.find()) {
            result.add(win.group());
        }

        Matcher posix = Pattern.compile("(?:^|[\\s|>'\"])(/[^\\s\"'>;|<]+)").matcher(command);
        while (posix.find()) {
            result.add(posix.group(1));
        }

        Matcher home = Pattern.compile("(?:^|[\\s|>'\"])(~[^\\s\"'>;|<]*)").matcher(command);
        while (home.find()) {
            result.add(home.group(1));
        }

        return result;
    }

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

    private void killProcess(Process process) {
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private String readAll(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            input.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * sandbox hook，占位。
     */
    private String wrapSandboxCommand(String sandbox, String command, String workspace, String cwd) {
        // 后面如果继续做更严格的 sandbox，可以把命令包装逻辑集中到单独模块
        // 就把这里替换掉。
        return command;
    }

    private String expandEnv(String raw) {
        String result = raw;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            result = result.replace("$" + entry.getKey(), entry.getValue());
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

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
