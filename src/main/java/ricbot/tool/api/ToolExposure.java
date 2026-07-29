package ricbot.tool.api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Durable declaration of which groups a Run may use and currently exposes. */
public record ToolExposure(Set<ToolGroup> allowedGroups, Set<ToolGroup> activeGroups) {
    public ToolExposure {
        Set<ToolGroup> allowed = new LinkedHashSet<>(allowedGroups != null ? allowedGroups : Set.of(ToolGroup.BASIC));
        allowed.add(ToolGroup.BASIC);
        Set<ToolGroup> active = new LinkedHashSet<>(activeGroups != null ? activeGroups : Set.of(ToolGroup.BASIC));
        active.add(ToolGroup.BASIC);
        if (!allowed.containsAll(active)) throw new IllegalArgumentException("active tool groups must be allowed");
        allowedGroups = Set.copyOf(allowed);
        activeGroups = Set.copyOf(active);
    }
    public static ToolExposure all() { return new ToolExposure(Set.of(ToolGroup.values()), Set.of(ToolGroup.values())); }
    public ToolExposure activate(Collection<ToolGroup> groups) {
        Set<ToolGroup> next = new LinkedHashSet<>(activeGroups);
        for (ToolGroup group : groups) {
            if (!allowedGroups.contains(group)) throw new SecurityException("tool group is not allowed: " + group);
            next.add(group);
        }
        return new ToolExposure(allowedGroups, next);
    }
    public ToolExposure deactivate(Collection<ToolGroup> groups) {
        Set<ToolGroup> next = new LinkedHashSet<>(activeGroups);
        next.removeAll(groups);
        next.add(ToolGroup.BASIC);
        return new ToolExposure(allowedGroups, next);
    }
}
