package ricbot.tool.api;

public enum ToolGroup {
    BASIC, CODING, GIT, VERIFICATION, ADMIN;
    public static ToolGroup parse(String value) {
        return ToolGroup.valueOf(value != null ? value.trim().toUpperCase(java.util.Locale.ROOT) : "");
    }
}
