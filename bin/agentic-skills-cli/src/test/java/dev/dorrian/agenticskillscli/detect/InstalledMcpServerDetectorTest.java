package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.registry.McpConfigDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledMcpServerDetectorTest {

    @Test
    void returnsEmptyForAnUnknownToolKey() {
        assertTrue(InstalledMcpServerDetector.detect(List.of("some-server"), "unknown-tool").isEmpty());
    }

    @Test
    void claudeBranchNeverThrowsEvenWhenServerIsNotRegistered() {
        assertTrue(InstalledMcpServerDetector.detect(List.of("definitely-not-registered-xyz"), "claude").isEmpty());
    }

    @Test
    void findsServersInTheGlobalFileAndInEveryExtraFile(@TempDir Path tempDir) throws IOException {
        Path global = tempDir.resolve("codeium").resolve("mcp_config.json");
        Path devin = tempDir.resolve("devin").resolve("mcp_config.json");
        Files.createDirectories(global.getParent());
        Files.createDirectories(devin.getParent());
        Files.writeString(global, """
            { "mcpServers": { "a": {} } }
            """);
        Files.writeString(devin, """
            { "mcpServers": { "b": {} } }
            """);
        McpConfigDef cfg = new McpConfigDef("windsurf", global, "mcpServers", "stdio", List.of(devin));

        assertEquals(Set.of("a", "b"), InstalledMcpServerDetector.detect(List.of("a", "b", "c"), cfg));
    }

    @Test
    void findsAServerPresentOnlyInTheExtraFile(@TempDir Path tempDir) throws IOException {
        Path devin = tempDir.resolve("devin").resolve("mcp_config.json");
        Files.createDirectories(devin.getParent());
        Files.writeString(devin, """
            { "mcpServers": { "b": {} } }
            """);
        McpConfigDef cfg = new McpConfigDef("windsurf", tempDir.resolve("missing.json"), "mcpServers", "stdio", List.of(devin));

        assertEquals(Set.of("b"), InstalledMcpServerDetector.detect(List.of("b"), cfg));
    }
}
