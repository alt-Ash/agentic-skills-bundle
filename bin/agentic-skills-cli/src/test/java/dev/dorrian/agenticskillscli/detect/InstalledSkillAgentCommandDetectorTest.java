package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InstalledSkillDetector}/{@link InstalledAgentDetector}/{@link
 * InstalledCommandDetector} all resolve paths via {@code
 * AgentToolRegistry}'s {@code HomeDir}-relative paths. The negative-path
 * and empty-input cases below need no isolation. The positive-path cases
 * write real files under {@link HomeDir#resolve()}, which this module's
 * Surefire config (see pom.xml) redirects at a scratch {@code
 * target/test-home} directory for the whole test JVM — never the real
 * developer machine's {@code $HOME}.
 */
class InstalledSkillAgentCommandDetectorTest {

    @Test
    void skillDetectorReturnsEmptyForAnEmptyToolKeyList() {
        SkillDescriptor skill = new SkillDescriptor("frontend", "some-skill", Path.of("/tmp/some-skill"));
        assertTrue(InstalledSkillDetector.detect(List.of(skill), List.of()).isEmpty());
    }

    @Test
    void skillDetectorReturnsEmptyWhenSkillIsNotInstalledForAnyGivenTool() {
        SkillDescriptor skill = new SkillDescriptor("frontend", "definitely-not-installed-xyz", Path.of("/tmp/x"));
        assertTrue(InstalledSkillDetector.detect(List.of(skill), List.of("windsurf", "zed")).isEmpty());
    }

    @Test
    void agentDetectorReturnsEmptyForAnEmptyAgentList() {
        assertTrue(InstalledAgentDetector.detect(List.of(), List.of("claude")).isEmpty());
    }

    @Test
    void agentDetectorSkipsToolsWithNoAgentsGlobalPath() {
        AgentDescriptor agent = new AgentDescriptor("definitely-not-installed-xyz", Path.of("/tmp/x.md"), Map.of());
        // windsurf and zed both have agentsGlobalPath == null in the registry.
        assertTrue(InstalledAgentDetector.detect(List.of(agent), List.of("windsurf", "zed")).isEmpty());
    }

    @Test
    void commandDetectorReturnsEmptyForAnEmptyCommandList() {
        assertTrue(InstalledCommandDetector.detect(List.of(), List.of("claude")).isEmpty());
    }

    @Test
    void commandDetectorSkipsToolsWithNoCommandsGlobalPath() {
        // cursor/antigravity/codex/vscode/windsurf/zed all have commandsGlobalPath == null.
        assertTrue(InstalledCommandDetector.detect(
            List.of("definitely-not-installed-xyz"), List.of("cursor", "windsurf", "zed")
        ).isEmpty());
    }

    @Test
    void skillDetectorFindsAGenuinelyInstalledSkillUnderTheOverriddenHome() throws IOException {
        Path claudeSkills = HomeDir.resolve().resolve(".claude").resolve("skills");
        Files.createDirectories(claudeSkills.resolve("pr-review-checklist"));

        SkillDescriptor skill = new SkillDescriptor("quality", "pr-review-checklist", Path.of("/tmp/src"));
        Set<String> installed = InstalledSkillDetector.detect(List.of(skill), List.of("claude", "windsurf"));

        assertEquals(Set.of("pr-review-checklist"), installed);
    }

    @Test
    void agentDetectorFindsAGenuinelyInstalledAgentUnderTheOverriddenHome() throws IOException {
        Path claudeAgents = HomeDir.resolve().resolve(".claude").resolve("agents");
        Files.createDirectories(claudeAgents);
        Files.writeString(claudeAgents.resolve("debugger.md"), "---\ndescription: test\n---\n");

        AgentDescriptor agent = new AgentDescriptor("debugger", Path.of("/tmp/debugger.md"), Map.of());
        Set<String> installed = InstalledAgentDetector.detect(List.of(agent), List.of("claude", "windsurf"));

        assertEquals(Set.of("debugger"), installed);
    }

    @Test
    void commandDetectorFindsAGenuinelyInstalledCommandUnderTheOverriddenHome() throws IOException {
        Path claudeCommands = HomeDir.resolve().resolve(".claude").resolve("commands");
        Files.createDirectories(claudeCommands);
        Files.writeString(claudeCommands.resolve("pr-check.md"), "---\ndescription: test\n---\n");

        Set<String> installed = InstalledCommandDetector.detect(List.of("pr-check"), List.of("claude", "cursor"));

        assertEquals(Set.of("pr-check"), installed);
    }
}
