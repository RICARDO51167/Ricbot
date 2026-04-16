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
 * 安全执行 Shell 命令的工具类。
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

    @Override
    public Object execute(Map<String, Object> kwargs) throws Exception {
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

        return execute(cmd, workingDirOverride, timeoutOverride);
    }

    public String execute(String command, String workingDirOverride, Integer timeoutOverride) {
        String cwd = firstNonBlank(workingDirOverride, this.workingDir, System.getProperty("user.dir"));

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

        String guardError = guardCommand(command, cwd);
        if (guardError != null) {
            return guardError;
        }

        String effectiveCommand = command;
        String effectiveCwd = cwd;

        if (sandbox != null && !sandbox.isBlank()) {
            return "错误：sandbox 已启用，但当前版本未实现可验证的隔离执行。请关闭 sandbox，或仅启用 restrict_to_workspace。";
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
                return "错误：命令执行超时（" + effectiveTimeout + " 秒）";
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
                result = "（无输出）";
            }

            if (result.length() > MAX_OUTPUT) {
                result = result.substring(0, MAX_OUTPUT) + "\n...（已截断）";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "[退出码 " + exitCode + "]\n" + result;
            }
            return result;

        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    private Map<String, String> buildEnv() {
        Map<String, String> env = new LinkedHashMap<>();

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

    private String guardCommand(String command, String cwd) {
        String cmd = command != null ? command.trim() : "";
        String lower = cmd.toLowerCase(Locale.ROOT);

        for (String pattern : denyPatterns) {
            if (Pattern.compile(pattern).matcher(lower).find()) {
                return "错误：命令被安全防护拦截（检测到危险模式）";
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
                return "错误：命令被安全防护拦截（不在允许列表中）";
            }
        }

        if (NetworkSecurity.containsInternalUrl(cmd)) {
            return "错误：命令被安全防护拦截（检测到内网/私有 URL）";
        }

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

    private String wrapSandboxCommand(String sandbox, String command, String workspace, String cwd) {
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
