package ricbot.integration.api.console;

import com.fasterxml.jackson.annotation.JsonValue;

public enum WorkspaceNodeType {
    DIRECTORY("directory"),
    FILE("file");

    private final String value;

    WorkspaceNodeType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
