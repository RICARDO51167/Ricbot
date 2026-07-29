package ricbot.domain.agent.hint;

import java.time.Instant;
import java.util.Map;

public sealed interface RuntimeHint permits RuntimeHint.TimeHint, RuntimeHint.BudgetHint,
        RuntimeHint.ContextHint, RuntimeHint.TaskHint, RuntimeHint.WorkspaceHint {
    String kind();
    record TimeHint(Instant time, String timezone) implements RuntimeHint { public String kind() { return "time"; } }
    record BudgetHint(Map<String, Object> snapshot) implements RuntimeHint { public String kind() { return "budget"; } }
    record ContextHint(double utilization, int artifacts, int compactions) implements RuntimeHint { public String kind() { return "context"; } }
    record TaskHint(int pending, int running, int completed) implements RuntimeHint { public String kind() { return "tasks"; } }
    record WorkspaceHint(String path, String mode, String state) implements RuntimeHint { public String kind() { return "workspace"; } }
}
