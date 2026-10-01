package dev.dorrian.agenticskillscli.registry;

import java.nio.file.Path;

/**
 * Per-AI-tool capability/path definition — the Java equivalent of one entry
 * in {@code bin/install.js}'s {@code AGENTS} object. All paths are absolute
 * (home-directory-relative), matching how the original resolved them with
 * {@code os.homedir()} at module-load time.
 */
public record AgentToolDef(
    String key,
    String name,
    Path globalPath,
    String projectFolder,
    Path commandsGlobalPath,
    String commandsProjectFolder,
    Path agentsGlobalPath,
    String agentsProjectFolder,
    Path agentConfigFile,
    String agentConfigKey,
    boolean supportsCommands,
    boolean supportsAgents,
    Path detectPath
) {
}
