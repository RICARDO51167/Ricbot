package ricbot.domain.eval;

import ricbot.infra.config.Config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvalSmokeRuntime {
    public static final String PROVIDER_MODE = "smoke";
    public static final String MODEL = "smoke-model";
    public static final String DEFAULT_WORKSPACE = "target/eval-smoke-workspace";
    public static final Path DEFAULT_ARTIFACTS = Path.of("target", "eval-smoke-artifacts");

    private EvalSmokeRuntime() {
    }

    public static Config config(String workspace) {
        Config config = new Config();
        config.getAgents().getDefaults().setWorkspace(
                workspace != null && !workspace.isBlank() ? workspace : DEFAULT_WORKSPACE
        );
        config.getAgents().getDefaults().setModel(MODEL);
        config.getAgents().getDefaults().getDream().setEnabled(false);
        config.getTools().getExec().setEnable(false);
        config.getTools().getExec().setApprovalEnabled(false);
        config.getTools().getWeb().setEnable(false);
        config.getTools().setMcpServers(fakeMcpServers());
        return config;
    }

    public static Map<String, Object> fakeMcpServers() {
        Map<String, Object> demo = new LinkedHashMap<>();
        demo.put("type", "stdio");
        demo.put("command", "java");
        demo.put("args", List.of(
                "-cp",
                System.getProperty("java.class.path"),
                EvalFakeMcpStdioServer.class.getName()
        ));
        demo.put("enabled_tools", List.of("*"));
        demo.put("tool_timeout", 1);
        return Map.of("demo", demo);
    }
}
