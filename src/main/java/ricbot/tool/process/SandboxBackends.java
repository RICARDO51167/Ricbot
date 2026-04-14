package ricbot.tool.process;

import ricbot.infra.config.RuntimePaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sandbox backends for exec tool.
 */
public final class SandboxBackends {

    private SandboxBackends() {}

    public static String wrapCommand(String sandbox, String command, String workspace, String cwd) {
        return switch (sandbox) {
            case "bwrap" -> wrapBwrap(command, workspace, cwd);
            default -> throw new IllegalArgumentException(
                    "Unknown sandbox backend '" + sandbox + "'. Available: [bwrap]"
            );
        };
    }

    /**
     * bubblewrap 包装。
     */
    private static String wrapBwrap(String command, String workspace, String cwd) {
        Path ws = Path.of(workspace).toAbsolutePath().normalize();
        Path media = RuntimePaths.getMediaDir(null).toAbsolutePath().normalize();

        String sandboxCwd;
        try {
            sandboxCwd = ws.resolve(Path.of(cwd).toAbsolutePath().normalize().relativize(ws)).toString();
        } catch (Exception e) {
            sandboxCwd = ws.toString();
        }

        List<String> required = List.of("/usr");
        List<String> optional = List.of(
                "/bin", "/lib", "/lib64", "/etc/alternatives",
                "/etc/ssl/certs", "/etc/resolv.conf", "/etc/ld.so.cache"
        );

        List<String> args = new ArrayList<>();
        args.add("bwrap");
        args.add("--new-session");
        args.add("--die-with-parent");

        for (String p : required) {
            args.add("--ro-bind");
            args.add(p);
            args.add(p);
        }
        for (String p : optional) {
            args.add("--ro-bind-try");
            args.add(p);
            args.add(p);
        }

        args.addAll(List.of(
                "--proc", "/proc",
                "--dev", "/dev",
                "--tmpfs", "/tmp",
                "--tmpfs", ws.getParent().toString(),
                "--dir", ws.toString(),
                "--bind", ws.toString(), ws.toString(),
                "--ro-bind-try", media.toString(), media.toString(),
                "--chdir", sandboxCwd,
                "--", "sh", "-c", command
        ));

        return String.join(" ", args.stream().map(SandboxBackends::shellQuote).toList());
    }

    private static String shellQuote(String s) {
        if (s.matches("^[a-zA-Z0-9_./:-]+$")) return s;
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}