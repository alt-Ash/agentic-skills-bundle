package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.TomlTestSupport;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.detect.InstalledAgentDetector;
import dev.dorrian.agenticskillscli.detect.InstalledSkillDetector;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Codex moved skills to ~/.agents/skills and agents to TOML: uninstall also cleans what older versions wrote. */
class CodexLegacyCleanupTest {

    private static final String AGENT_SRC = "---\ndescription: Reviews pull requests carefully\nmode: subagent\n"
        + "permission:\n  edit: deny\n  write: deny\n---\n## Role\nReview.\n";

    @Test
    void codexAgentInstallWritesParseableToml(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("pr-reviewer.md");
        Files.writeString(src, AGENT_SRC);
        Path target = tmp.resolve("agents");

        AgentInstaller.install(List.of(new AgentDescriptor("pr-reviewer", src, Map.of())), target, "codex", tmp);

        assertFalse(Files.exists(target.resolve("pr-reviewer.md")));
        Map<String, Object> toml = TomlTestSupport.parse(Files.readString(target.resolve("pr-reviewer.toml")));
        assertEquals("pr-reviewer", toml.get("name"));
        assertEquals("read-only", toml.get("sandbox_mode"));
    }

    @Test
    void codexAgentRemoveAlsoDeletesStaleMarkdownFromOlderInstalls(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pr-reviewer.toml"), "name = \"pr-reviewer\"\n");
        Files.writeString(tmp.resolve("pr-reviewer.md"), "old");
        AgentDescriptor agent = new AgentDescriptor("pr-reviewer", tmp.resolve("src.md"), Map.of());

        OperationResult r = AgentInstaller.remove(agent, tmp, "codex");
        assertTrue(r.success());
        assertFalse(r.skipped());
        assertFalse(Files.exists(tmp.resolve("pr-reviewer.toml")));
        assertFalse(Files.exists(tmp.resolve("pr-reviewer.md")));
    }

    @Test
    void codexAgentRemoveOfOnlyLegacyMarkdownIsNotSkipped(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pr-reviewer.md"), "old");
        OperationResult r = AgentInstaller.remove(new AgentDescriptor("pr-reviewer", tmp, Map.of()), tmp, "codex");
        assertFalse(r.skipped());
        assertFalse(Files.exists(tmp.resolve("pr-reviewer.md")));
    }

    @Test
    void nonCodexAgentRemoveLeavesOtherExtensionsAlone(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("x.toml"), "keep");
        AgentInstaller.remove(new AgentDescriptor("x", tmp, Map.of()), tmp, "claude");
        assertTrue(Files.exists(tmp.resolve("x.toml")));
    }

    @Test
    void codexSkillRemoveAlsoDeletesLegacyCodexSkillsCopy(@TempDir Path tmp) throws IOException {
        // Project-style layout: <proj>/.agents/skills (new) and <proj>/.codex/skills (old)
        Path current = tmp.resolve(".agents").resolve("skills");
        Path legacy = tmp.resolve(".codex").resolve("skills");
        Files.createDirectories(current.resolve("s1"));
        Files.createDirectories(legacy.resolve("s1"));
        Files.writeString(legacy.resolve("s1").resolve("SKILL.md"), "old");

        OperationResult r = SkillInstaller.remove("s1", current);
        assertFalse(r.skipped());
        assertFalse(Files.exists(current.resolve("s1")));
        assertFalse(Files.exists(legacy.resolve("s1")));
    }

    @Test
    void codexSkillRemoveOfOnlyLegacyCopyIsNotSkipped(@TempDir Path tmp) throws IOException {
        Path current = tmp.resolve(".agents").resolve("skills");
        Path legacy = tmp.resolve(".codex").resolve("skills");
        Files.createDirectories(legacy.resolve("s1"));
        Files.writeString(legacy.resolve("s1").resolve("SKILL.md"), "---\nname: s1\n---\n"); // a real installed skill

        OperationResult r = SkillInstaller.remove("s1", current);
        assertFalse(r.skipped());
        assertFalse(Files.exists(legacy.resolve("s1")));
    }

    @Test
    void otherToolsSkillRemoveDoesNotTouchCodexDirectory(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve(".codex").resolve("skills");
        Files.createDirectories(legacy.resolve("s1"));
        Files.writeString(legacy.resolve("s1").resolve("SKILL.md"), "---\nname: s1\n---\n"); // a real installed skill
        SkillInstaller.remove("s1", tmp.resolve(".gemini").resolve("config").resolve("skills"));
        assertTrue(Files.exists(legacy.resolve("s1")));
    }

    @Test
    void detectorsSeeLegacyCodexInstallsUnderTheOverriddenHome() throws IOException {
        Path home = HomeDir.resolve();
        assertTrue(home.toString().contains("test-home"));
        assertTrue(AgentToolRegistry.get("codex").globalPath().startsWith(home));

        Path legacySkill = home.resolve(".codex").resolve("skills").resolve("legacy-skill-xyz");
        Path legacyAgent = home.resolve(".codex").resolve("agents").resolve("legacy-agent-xyz.md");
        Files.createDirectories(legacySkill);
        Files.writeString(legacySkill.resolve("SKILL.md"), "---\nname: legacy-skill-xyz\n---\n"); // a real installed skill
        Files.createDirectories(legacyAgent.getParent());
        Files.writeString(legacyAgent, "old");
        try {
            assertEquals(Set.of("legacy-skill-xyz"), InstalledSkillDetector.detect(
                List.of(new SkillDescriptor("c", "legacy-skill-xyz", Path.of("/tmp"))), List.of("codex")));
            assertEquals(Set.of("legacy-agent-xyz"), InstalledAgentDetector.detect(
                List.of(new AgentDescriptor("legacy-agent-xyz", Path.of("/tmp"), Map.of())), List.of("codex")));
        } finally {
            Files.deleteIfExists(legacySkill.resolve("SKILL.md"));
            Files.deleteIfExists(legacySkill);
            Files.deleteIfExists(legacyAgent);
        }
    }
}
