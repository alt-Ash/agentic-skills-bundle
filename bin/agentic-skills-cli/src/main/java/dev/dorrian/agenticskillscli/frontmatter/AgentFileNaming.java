package dev.dorrian.agenticskillscli.frontmatter;

/**
 * Java port of {@code bin/install.js}'s {@code agentFileName()} — the
 * correct output filename for an agent given the target tool: {@code
 * <name>.md} for OpenCode/Claude/Gemini, {@code <name>.agent.md} for VS Code
 * Copilot, {@code <name>.toml} for Codex (standalone custom-agent TOML files).
 */
public final class AgentFileNaming {

    private AgentFileNaming() {
    }

    public static String fileName(String name, String toolKey) {
        if ("vscode".equals(toolKey)) return name + ".agent.md";
        if ("codex".equals(toolKey)) return name + ".toml";
        return name + ".md";
    }

    /** File names earlier installer versions wrote for this agent that the current one no longer does. */
    public static java.util.List<String> legacyFileNames(String name, String toolKey) {
        // Codex agents were copied as Markdown before 2.x switched them to TOML.
        return "codex".equals(toolKey) ? java.util.List.of(name + ".md") : java.util.List.of();
    }
}
