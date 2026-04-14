package ricbot.tool.mcp;

public class MCPPromptArgument {
    private String name;
    private String description;
    private boolean required;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}