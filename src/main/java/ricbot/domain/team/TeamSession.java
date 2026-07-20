package ricbot.domain.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TeamSession(
        String id,
        String goal,
        TeamTaskState state,
        List<TeamTask> tasks,
        String createdAt,
        String updatedAt
) {
    public TeamSession {
        id = id != null && !id.isBlank() ? id : newId();
        goal = goal != null ? goal.trim() : "";
        state = state != null ? state : TeamTaskState.PLANNING;
        tasks = tasks != null ? List.copyOf(tasks) : List.of();
        String now = Instant.now().toString();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : now;
        updatedAt = updatedAt != null && !updatedAt.isBlank() ? updatedAt : now;
    }

    public TeamSession withState(TeamTaskState next) {
        return new TeamSession(id, goal, next, tasks, createdAt, Instant.now().toString());
    }

    public TeamSession withTasks(List<TeamTask> nextTasks) {
        return new TeamSession(id, goal, state, nextTasks, createdAt, Instant.now().toString());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("goal", goal);
        out.put("state", state.name());
        out.put("tasks", tasks.stream().map(TeamTask::toMap).toList());
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        return out;
    }

    public static TeamSession fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new TeamSession(
                string(raw.get("id")),
                string(raw.get("goal")),
                parseState(raw.get("state")),
                tasks(raw.get("tasks")),
                string(raw.get("createdAt")),
                string(raw.get("updatedAt"))
        );
    }

    private static List<TeamTask> tasks(Object raw) {
        List<TeamTask> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    TeamTask task = TeamTask.fromMap(map);
                    if (task != null) {
                        out.add(task);
                    }
                }
            }
        }
        return out;
    }

    private static TeamTaskState parseState(Object raw) {
        try {
            return raw != null ? TeamTaskState.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : TeamTaskState.PLANNING;
        } catch (Exception e) {
            return TeamTaskState.PLANNING;
        }
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String newId() {
        return "team_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
