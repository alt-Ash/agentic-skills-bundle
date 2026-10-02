package dev.dorrian.agenticskillscli.registry;

import dev.dorrian.agenticskillscli.HomeDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Java port of {@code bin/install.js}'s {@code AGENTS} object: one entry per
 * supported AI tool, in the exact order the original file declares them
 * (opencode, claude, cursor, antigravity, codex, vscode, windsurf, zed). Ported
 * field-for-field from the source read directly on 2026-09-30 — some tools
 * genuinely lack fields the others have (e.g. vscode has {@code
 * agentsGlobalPath} but no {@code agentsProjectFolder}; cursor/antigravity/codex
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
        Path p = HomeDir.resolve();
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
            Path base = appData != null ? Paths.get(appData) : HomeDir.resolve();
            return base.resolve("Code").resolve("User");
        }
        return home(".config", "Code", "User");
    }

    /**
     * Devin Desktop's (formerly Windsurf) per-user config dir as given on its
     * MCP docs page: {@code $XDG_CONFIG_HOME/devin} if set, else {@code
     * ~/.config/devin}; {@code %APPDATA%\devin} on Windows.
     */
    public static Path devinConfigDir() {
        return devinConfigDir(System::getenv, HomeDir.resolve(), System.getProperty("os.name", ""));
    }

    /**
     * Testable form of {@link #devinConfigDir()}. Under {@link
     * HomeDir#OVERRIDE_ENV_VAR} (tests, the smoke test) {@code XDG_CONFIG_HOME}
     * and {@code APPDATA} are ignored so a sandboxed run can never be pointed
     * back at the real config dirs.
     */
    public static Path devinConfigDir(Function<String, String> env, Path home, String osName) {
        if (!isBlank(env.apply(HomeDir.OVERRIDE_ENV_VAR))) {
            return home.resolve(".config").resolve("devin");
        }
        if (osName.toLowerCase().contains("win")) {
            String appData = env.apply("APPDATA");
            Path base = isBlank(appData) ? home.resolve("AppData").resolve("Roaming") : Paths.get(appData);
            return base.resolve("devin");
        }
        String xdg = env.apply("XDG_CONFIG_HOME");
        Path base = isBlank(xdg) ? home.resolve(".config") : Paths.get(xdg);
        return base.resolve("devin");
    }

    /**
     * Every path whose existence means the tool is installed: its {@code
     * detectPath}, plus Devin Desktop's own config dir for {@code windsurf}.
     */
    public static List<Path> detectPaths(String key) {
        AgentToolDef def = get(key);
        List<Path> paths = new ArrayList<>();
        if (def.detectPath() != null) {
            paths.add(def.detectPath());
        }
        if ("windsurf".equals(key)) {
            paths.add(devinConfigDir());
        }
        return List.copyOf(paths);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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

        // Antigravity (CLI and desktop IDE): one global customization root, ~/.gemini/config/, and one per-project
        // root, .agents/. Skills are skills/<name>/SKILL.md (they are also its slash commands, so there is no separate
        // commands install); agents are agents/<name>/agent.md. Both products document the same layout.
        m.put("antigravity", new AgentToolDef(
            "antigravity", "Antigravity (CLI + desktop IDE)",
            home(".gemini", "config", "skills"), ".agents/skills",
            null, null,
            home(".gemini", "config", "agents"), ".agents/agents",
            null, null,
            false, true,
            home(".gemini")
        ));

        m.put("codex", new AgentToolDef(
            "codex", "OpenAI Codex CLI",
            // Codex reads skills from $HOME/.agents/skills (user) and .agents/skills (repo), not
            // ~/.codex/skills — learn.chatgpt.com Codex skills docs. SkillInstaller.remove also
            // cleans the old .codex/skills location written by earlier versions.
            home(".agents", "skills"), ".agents/skills",
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
            // Devin Desktop (formerly Windsurf). Global skills stay under ~/.codeium/windsurf;
            // workspace skills: .devin/skills preferred, .windsurf/skills read as a fallback (and by
            // the Devin CLI and older Windsurf). Earlier versions wrongly used .windsurf/rules — see
            // install/LegacySkillLocations for the uninstall-side cleanup.
            "windsurf", "Devin Desktop (Windsurf)",
            home(".codeium", "windsurf", "skills"), ".windsurf/skills",
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
