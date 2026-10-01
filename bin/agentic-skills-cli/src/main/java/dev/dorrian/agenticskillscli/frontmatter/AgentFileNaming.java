package dev.dorrian.agenticskillscli.frontmatter;

/**
 * Java port of {@code bin/install.js}'s {@code agentFileName()} — the
 * correct output filename for an agent given the target tool: {@code
 * <name>.md} for OpenCode/Claude, {@code <name>.agent.md} for VS Code
 * Copilot.
 */
public final class AgentFileNaming {

    private AgentFileNaming() {
    }

    public static String fileName(String name, String toolKey) {
        return "vscode".equals(toolKey) ? name + ".agent.md" : name + ".md";
    }
}
