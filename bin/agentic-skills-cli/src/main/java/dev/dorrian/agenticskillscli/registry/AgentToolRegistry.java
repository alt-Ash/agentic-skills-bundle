package dev.dorrian.agenticskillscli.registry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code AGENTS} object: one entry per
 * supported AI tool, in the exact order the original file declares them
 * (opencode, claude, cursor, gemini, codex, vscode, windsurf, zed). Ported
 * field-for-field from the source read directly on 2026-09-30 — some tools
 * genuinely lack fields the others have (e.g. vscode has {@code
 * agentsGlobalPath} but no {@code agentsProjectFolder}; cursor/gemini/codex
 * have no {@code agentConfigFile}) and that asymmetry is preserved exactly
 * rather than "filled in."
 */
public final class AgentToolRegistry {

    public static final Map<String, AgentToolDef> ALL = build();

    private AgentToolRegistry() {
    }

    public static AgentToolDef get(String key) {
        AgentToolDef def = ALL.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown AI tool key: " + key);
        }
        return def;
    }

    private static Path home(String... segments) {
        Path p = Paths.get(System.getProperty("user.home"));
        for (String s : segments) {
            p = p.resolve(s);
        }
        return p;
    }

    /** Platform-appropriate VS Code User data directory (matches getVSCodeUserDir() in install.js). */
    public static Path vsCodeUserDir() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            return home("Library", "Application Support", "Code", "User");
        }
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null ? Paths.get(appData) : Paths.get(System.getProperty("user.home"));
            return base.resolve("Code").resolve("User");
        }
        return home(".config", "Code", "User");
    }

    private static Map<String, AgentToolDef> build() {
        Map<String, AgentToolDef> m = new LinkedHashMap<>();

        m.put("opencode", new AgentToolDef(
            "opencode", "OpenCode",
            home(".config", "opencode", "skills"), ".opencode/skills",
            home(".config", "opencode", "commands"), ".opencode/commands",
            home(".config", "opencode", "agents"), ".opencode/agents",
            home(".config", "opencode", "opencode.json"), "agent",
            true, true,
            home(".config", "opencode")
        ));

        m.put("claude", new AgentToolDef(
            "claude", "Claude Code",
            home(".claude", "skills"), ".claude/skills",
            home(".claude", "commands"), ".claude/commands",
            home(".claude", "agents"), ".claude/agents",
            null, null, // Claude Code auto-discovers agents from .claude/agents/ — no JSON registration needed
            true, true,
            home(".claude")
        ));

        m.put("cursor", new AgentToolDef(
            "cursor", "Cursor",
            home(".cursor", "rules"), ".cursor/rules",
            null, null,
            home(".cursor", "agents"), ".cursor/agents",
            null, null,
            false, true,
            home(".cursor")
        ));

        m.put("gemini", new AgentToolDef(
            "gemini", "Gemini CLI",
            home(".gemini", "skills"), ".gemini/skills",
            null, null,
            home(".gemini", "agents"), ".gemini/agents",
            null, null,
            false, true,
            home(".gemini")
        ));

        m.put("codex", new AgentToolDef(
            "codex", "OpenAI Codex CLI",
            home(".codex", "skills"), ".codex/skills",
            null, null,
            home(".codex", "agents"), ".codex/agents",
            null, null,
            false, true,
            home(".codex")
        ));

        m.put("vscode", new AgentToolDef(
            "vscode", "VS Code (GitHub Copilot)",
            home(".vscode", "skills"), ".vscode/skills",
            null, null,
            home(".copilot", "agents"), null, // no agentsProjectFolder in the original either
            null, null,
            false, true,
            vsCodeUserDir()
        ));

        m.put("windsurf", new AgentToolDef(
            "windsurf", "Windsurf",
            home(".codeium", "windsurf", "skills"), ".windsurf/rules",
            null, null,
            null, null,
            null, null,
            false, false,
            home(".codeium", "windsurf")
        ));

        m.put("zed", new AgentToolDef(
            "zed", "Zed AI",
            home(".config", "zed", "skills"), ".zed/skills",
            null, null,
            null, null,
            null, null,
            false, false,
            home(".config", "zed")
        ));

        return m;
    }
}
