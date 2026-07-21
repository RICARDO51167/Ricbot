package ricbot.domain.eval;

import ricbot.infra.config.Config;

import java.nio.file.Path;

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
        config.getTools().getExec().setEnable(false);
        config.getTools().getExec().setApprovalEnabled(false);
        return config;
    }
}
