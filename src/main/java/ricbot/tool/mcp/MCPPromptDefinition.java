package ricbot.tool.mcp;

import java.util.List;

public class MCPPromptDefinition {
    private String name;
    private String description;
    private List<MCPPromptArgument> arguments;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<MCPPromptArgument> getArguments() { return arguments; }
    public void setArguments(List<MCPPromptArgument> arguments) { this.arguments = arguments; }
}