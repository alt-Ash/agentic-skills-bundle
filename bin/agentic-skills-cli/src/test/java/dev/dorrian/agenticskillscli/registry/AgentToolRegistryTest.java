package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolRegistryTest {

    @Test
    void hasExactlyEightToolsInSourceOrder() {
        assertEquals(
            List.of("opencode", "claude", "cursor", "gemini", "codex", "vscode", "windsurf", "zed"),
            List.copyOf(AgentToolRegistry.ALL.keySet())
        );
    }

    @Test
    void opencodeSupportsCommandsAndAgentsAndHasAJsonAgentConfig() {
        AgentToolDef opencode = AgentToolRegistry.get("opencode");
        assertTrue(opencode.supportsCommands());
        assertTrue(opencode.supportsAgents());
        assertEquals("agent", opencode.agentConfigKey());
        assertTrue(opencode.agentConfigFile().toString().endsWith("opencode.json"));
    }

    @Test
    void claudeHasNoAgentConfigFile() {
        AgentToolDef claude = AgentToolRegistry.get("claude");
        assertNull(claude.agentConfigFile());
        assertNull(claude.agentConfigKey());
        assertTrue(claude.supportsAgents());
    }

    @Test
    void vscodeHasAgentsGlobalPathButNoAgentsProjectFolder() {
        AgentToolDef vscode = AgentToolRegistry.get("vscode");
        assertTrue(vscode.agentsGlobalPath().toString().contains(".copilot"));
        assertNull(vscode.agentsProjectFolder());
    }

    @Test
    void windsurfAndZedSupportNeitherCommandsNorAgents() {
        assertFalse(AgentToolRegistry.get("windsurf").supportsAgents());
        assertFalse(AgentToolRegistry.get("windsurf").supportsCommands());
        assertFalse(AgentToolRegistry.get("zed").supportsAgents());
        assertFalse(AgentToolRegistry.get("zed").supportsCommands());
    }

    @Test
    void codexSkillsUseTheCrossToolAgentsDirectory() {
        AgentToolDef codex = AgentToolRegistry.get("codex");
        assertTrue(codex.globalPath().endsWith(java.nio.file.Path.of(".agents", "skills")), codex.globalPath().toString());
        assertEquals(".agents/skills", codex.projectFolder());
        assertTrue(codex.agentsGlobalPath().endsWith(java.nio.file.Path.of(".codex", "agents")));
        assertEquals(".codex/agents", codex.agentsProjectFolder());
    }

    @Test
    void unknownToolKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.get("not-a-tool"));
    }

    // ─── Devin Desktop (formerly Windsurf) ──────────────────────────────────

    @Test
    void windsurfIsPresentedAsDevinDesktopButKeepsItsKey() {
        AgentToolDef windsurf = AgentToolRegistry.get("windsurf");
        assertEquals("windsurf", windsurf.key());
        assertEquals("Devin Desktop (Windsurf)", windsurf.name());
    }

    @Test
    void windsurfProjectSkillsGoToTheSkillsFolderNotRules() {
        AgentToolDef windsurf = AgentToolRegistry.get("windsurf");
        assertEquals(".windsurf/skills", windsurf.projectFolder());
        assertTrue(windsurf.globalPath().endsWith(Path.of(".codeium", "windsurf", "skills")));
    }

    @Test
    void devinConfigDirDefaultsToDotConfigUnderHome() {
        Path home = Path.of("/fake/home");
        assertEquals(home.resolve(".config").resolve("devin"),
            AgentToolRegistry.devinConfigDir(Map.<String, String>of()::get, home, "Mac OS X"));
    }

    @Test
    void devinConfigDirHonoursXdgConfigHome() {
        Path home = Path.of("/fake/home");
        assertEquals(Path.of("/xdg/cfg", "devin"),
            AgentToolRegistry.devinConfigDir(Map.of("XDG_CONFIG_HOME", "/xdg/cfg")::get, home, "Linux"));
    }

    @Test
    void devinConfigDirIgnoresBlankXdgConfigHome() {
        Path home = Path.of("/fake/home");
        assertEquals(home.resolve(".config").resolve("devin"),
            AgentToolRegistry.devinConfigDir(Map.of("XDG_CONFIG_HOME", " ")::get, home, "Linux"));
    }

    @Test
    void devinConfigDirUsesAppDataOnWindows() {
        Path home = Path.of("/fake/home");
        assertEquals(Path.of("/win/AppData/Roaming", "devin"),
            AgentToolRegistry.devinConfigDir(Map.of("APPDATA", "/win/AppData/Roaming")::get, home, "Windows 11"));
    }

    @Test
    void devinConfigDirFallsBackToRoamingUnderHomeOnWindowsWithoutAppData() {
        Path home = Path.of("/fake/home");
        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("devin"),
            AgentToolRegistry.devinConfigDir(Map.<String, String>of()::get, home, "Windows 11"));
    }

    @Test
    void devinConfigDirIgnoresXdgAndAppDataUnderTheHomeOverride() {
        // The test/smoke-test sandbox must never be redirected back to the real config dirs.
        Path home = Path.of("/sandbox");
        Map<String, String> env = Map.of(
            "AGENTIC_SKILLS_HOME_OVERRIDE", "/sandbox", "XDG_CONFIG_HOME", "/real/cfg", "APPDATA", "/real/appdata");
        assertEquals(home.resolve(".config").resolve("devin"), AgentToolRegistry.devinConfigDir(env::get, home, "Linux"));
        assertEquals(home.resolve(".config").resolve("devin"), AgentToolRegistry.devinConfigDir(env::get, home, "Windows 11"));
    }

    @Test
    void windsurfInstallDetectionAlsoRecognisesTheDevinConfigDir() {
        List<Path> paths = AgentToolRegistry.detectPaths("windsurf");
        assertEquals(AgentToolRegistry.get("windsurf").detectPath(), paths.get(0));
        assertTrue(paths.contains(AgentToolRegistry.devinConfigDir()));
    }

    @Test
    void otherToolsDetectOnlyByTheirOwnDetectPath() {
        assertEquals(List.of(AgentToolRegistry.get("cursor").detectPath()), AgentToolRegistry.detectPaths("cursor"));
    }
}
