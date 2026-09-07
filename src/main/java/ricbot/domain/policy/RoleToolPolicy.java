package ricbot.domain.policy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class RoleToolPolicy {
    private final Map<PolicyRole, RolePolicy> policies = new EnumMap<>(PolicyRole.class);
    private final List<PolicyRule> extraRules;
    private final String source;

    public RoleToolPolicy(Map<PolicyRole, RolePolicy> policies, List<PolicyRule> extraRules, String source) {
        if (policies != null) {
            this.policies.putAll(policies);
        }
        this.extraRules = extraRules != null ? List.copyOf(extraRules) : List.of();
        this.source = source != null && !source.isBlank() ? source : "default-policy";
    }

    public static RoleToolPolicy defaultPolicy() {
        Map<PolicyRole, RolePolicy> map = new EnumMap<>(PolicyRole.class);
        map.put(PolicyRole.EXPLORER, new RolePolicy(
                List.of("read_file", "grep", "glob", "workspace diff"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        map.put(PolicyRole.VERIFIER, new RolePolicy(
                List.of("read_file", "grep", "glob", "change diff", "trace", "summary", "workspace diff", "exec test"),
                List.of("exec"),
                List.of("write", "edit", "commit", "rollback", "delete")
        ));
        map.put(PolicyRole.TESTER, new RolePolicy(
                List.of("read_file", "grep", "glob", "exec test"),
                List.of("exec"),
                List.of("write", "edit", "commit", "rollback", "delete")
        ));
        map.put(PolicyRole.DEVELOPER, new RolePolicy(
                List.of("read_file", "grep", "glob", "edit_file", "write_file", "exec test"),
                List.of("edit_file", "write_file", "exec"),
                List.of("commit", "rollback", "git commit", "git reset")
        ));
        map.put(PolicyRole.LEADER, new RolePolicy(
                List.of("team", "context", "summary", "change status", "trace", "policy"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        map.put(PolicyRole.REVIEWER, new RolePolicy(
                List.of("read_file", "grep", "glob", "change diff", "trace", "summary", "workspace diff", "exec test"),
                List.of("exec"),
                List.of("write", "edit", "commit", "rollback", "delete")
        ));
        map.put(PolicyRole.SYNTHESIZER, new RolePolicy(
                List.of("read_file", "grep", "glob", "summary"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        map.put(PolicyRole.PLANNER, new RolePolicy(
                List.of("team", "context", "summary", "read_file", "grep", "glob"),
                List.of(),
                List.of("write", "edit", "exec", "commit", "rollback", "delete")
        ));
        return new RoleToolPolicy(map, List.of(), "default-policy");
    }

    public RolePolicy forRole(PolicyRole role) {
        return policies.getOrDefault(role, policies.getOrDefault(PolicyRole.LEADER, new RolePolicy(List.of(), List.of(), List.of("*"))));
    }

    public List<PolicyRule> extraRules() {
        return extraRules;
    }

    public String source() {
        return source;
    }

    public Map<PolicyRole, RolePolicy> policies() {
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
