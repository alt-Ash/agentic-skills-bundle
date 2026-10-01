package dev.dorrian.agenticskillscli.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDiscoveryTest {

    @Test
    void discoversAgentsWithDescriptionAndSkipsDocFilesWithout(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("security-auditor.md"), """
            ---
            description: Audits code for security issues
            mode: subagent
            ---
            Body text.
            """);
        // A doc file with no frontmatter at all — must be skipped.
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Agents overview\n\nNo frontmatter here.");

        List<AgentDescriptor> agents = AgentDiscovery.discover(tempDir);

        assertEquals(1, agents.size());
        assertEquals("security-auditor", agents.get(0).name());
        assertEquals("Audits code for security issues", agents.get(0).frontmatter().get("description"));
    }

    @Test
    void ignoresNonMarkdownFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "not markdown");
        assertEquals(List.of(), AgentDiscovery.discover(tempDir));
    }

    @Test
    void returnsEmptyListWhenAgentsDirDoesNotExist(@TempDir Path tempDir) {
        assertEquals(List.of(), AgentDiscovery.discover(tempDir.resolve("nope")));
    }

    @Test
    void skipsFrontmatterWithoutDescriptionField(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("incomplete.md"), """
            ---
            mode: subagent
            ---
            No description field.
            """);
        assertTrue(AgentDiscovery.discover(tempDir).isEmpty());
    }
}
