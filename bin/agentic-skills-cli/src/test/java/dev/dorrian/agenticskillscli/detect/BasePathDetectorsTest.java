package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The explicit-base-path overloads used by {@code upgrade --project}. */
class BasePathDetectorsTest {

    @TempDir Path tmp;

    @Test
    void skillDetectorFindsOnlySkillsUnderTheGivenDirectory() throws IOException {
        Files.createDirectories(tmp.resolve("present"));
        List<SkillDescriptor> skills = List.of(
            new SkillDescriptor("c", "present", tmp), new SkillDescriptor("c", "absent", tmp));

        assertEquals(Set.of("present"), InstalledSkillDetector.detect(skills, tmp));
        assertTrue(InstalledSkillDetector.detect(skills, (Path) null).isEmpty());
    }

    @Test
    void agentDetectorUsesTheToolsFileNaming() throws IOException {
        Files.writeString(tmp.resolve("some-agent.md"), "x");
        List<AgentDescriptor> agents = List.of(
            new AgentDescriptor("some-agent", tmp.resolve("a.md"), Map.of()),
            new AgentDescriptor("other-agent", tmp.resolve("b.md"), Map.of()));

        assertEquals(Set.of("some-agent"), InstalledAgentDetector.detect(agents, tmp, "claude"));
        assertTrue(InstalledAgentDetector.detect(agents, null, "claude").isEmpty());
    }

    @Test
    void commandDetectorFindsMarkdownFilesUnderTheGivenDirectory() throws IOException {
        Files.writeString(tmp.resolve("pr-check.md"), "x");

        assertEquals(Set.of("pr-check"), InstalledCommandDetector.detect(List.of("pr-check", "nope"), tmp));
        assertTrue(InstalledCommandDetector.detect(List.of("pr-check"), (Path) null).isEmpty());
    }
}
