package ricbot.domain.policy;

import ricbot.domain.task.TaskRole;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class RoleToolPolicy {
    private final Map<TaskRole, RolePolicy> policies = new EnumMap<>(TaskRole.class);
    private final List<PolicyRule> extraRules;
    private final String source;

    public RoleToolPolicy(Map<TaskRole, RolePolicy> policies, List<PolicyRule> extraRules, String source) {
        if (policies != null) {
            this.policies.putAll(policies);
        }
        this.extraRules = extraRules != null ? List.copyOf(extraRules) : List.of();
        this.source = source != null && !source.isBlank() ? source : "default-policy";
    }

    public static RoleToolPolicy defaultPolicy() {
        Map<TaskRole, RolePolicy> map = new EnumMap<>(TaskRole.class);
        map.put(TaskRole.EXPLORER, new RolePolicy(
                List.of("read_file", "grep", "glob", "workspace diff"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        map.put(TaskRole.VERIFIER, new RolePolicy(
                List.of("read_file", "grep", "glob", "change diff", "trace", "summary", "workspace diff", "exec test"),
                List.of("exec"),
                List.of("write", "edit", "commit", "rollback", "delete")
        ));
        map.put(TaskRole.TESTER, new RolePolicy(
                List.of("read_file", "grep", "glob", "exec test"),
                List.of("exec"),
                List.of("write", "edit", "commit", "rollback", "delete")
        ));
        map.put(TaskRole.DEVELOPER, new RolePolicy(
                List.of("read_file", "grep", "glob", "edit_file", "write_file", "exec test"),
                List.of("edit_file", "write_file", "exec"),
                List.of("commit", "rollback", "git commit", "git reset")
        ));
        map.put(TaskRole.LEADER, new RolePolicy(
                List.of("team", "context", "summary", "change status", "trace", "policy"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        map.put(TaskRole.REVIEWER, new RolePolicy(
                List.of("read_file", "grep", "glob", "change diff", "trace", "summary", "workspace diff", "exec test"),
                List.of("exec"),
                List.of("write", "edit", "commit", "rollback", "delete")
        ));
        map.put(TaskRole.SYNTHESIZER, new RolePolicy(
                List.of("read_file", "grep", "glob", "summary"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        map.put(TaskRole.PLANNER, new RolePolicy(
                List.of("team", "context", "summary", "read_file", "grep", "glob"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        return new RoleToolPolicy(map, List.of(), "default-policy");
    }

    public RolePolicy forRole(TaskRole role) {
        return policies.getOrDefault(role, policies.getOrDefault(TaskRole.LEADER, new RolePolicy(List.of(), List.of(), List.of("*"))));
    }

    public List<PolicyRule> extraRules() {
        return extraRules;
    }

    public String source() {
        return source;
    }

    public Map<TaskRole, RolePolicy> policies() {
        return Map.copyOf(policies);
    }

    public record RolePolicy(List<String> allow, List<String> approval, List<String> deny) {
        public RolePolicy {
            allow = normalize(allow);
            approval = normalize(approval);
            deny = normalize(deny);
        }

        private static List<String> normalize(List<String> raw) {
            List<String> out = new ArrayList<>();
            for (String value : raw != null ? raw : List.<String>of()) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
            return List.copyOf(out);
        }
    }
}
