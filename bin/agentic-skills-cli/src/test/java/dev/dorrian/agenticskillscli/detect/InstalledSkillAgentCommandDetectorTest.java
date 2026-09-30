package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InstalledSkillDetector}/{@link InstalledAgentDetector}/{@link
 * InstalledCommandDetector} all resolve paths via {@code
 * AgentToolRegistry}'s hardcoded, real-{@code $HOME}-relative paths — same
 * as the original JS {@code AGENTS} object, which has no dependency-
 * injection seam either. These tests therefore only cover the deterministic
 * negative path (a skill/agent/command that provably does not exist) and
 * empty-input handling; true positive-path coverage comes from the
 * black-box end-to-end smoke test planned for Phase C.
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
        // cursor/gemini/codex/vscode/windsurf/zed all have commandsGlobalPath == null.
        assertTrue(InstalledCommandDetector.detect(
            List.of("definitely-not-installed-xyz"), List.of("cursor", "windsurf", "zed")
        ).isEmpty());
    }
}
