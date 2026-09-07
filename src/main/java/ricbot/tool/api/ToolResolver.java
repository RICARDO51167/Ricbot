package ricbot.tool.api;

import java.util.List;
import java.util.Map;

public final class ToolResolver {
    private final ToolCatalog catalog;
    public ToolResolver(ToolCatalog catalog) { this.catalog = java.util.Objects.requireNonNull(catalog); }
    public Tool resolveVisible(String name) { return catalog.visible(name) ? catalog.get(name) : null; }
    public List<Map<String, Object>> schemas() { return catalog.definitions(); }
}
