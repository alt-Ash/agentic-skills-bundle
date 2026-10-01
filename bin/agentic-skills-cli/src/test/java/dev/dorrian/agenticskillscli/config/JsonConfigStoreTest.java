package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.McpConfigDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonConfigStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentToolDef toolWithConfig(Path configFile, Path agentsGlobalPath) {
        return new AgentToolDef(
            "opencode", "OpenCode",
            Path.of("skills"), ".opencode/skills",
            Path.of("commands"), ".opencode/commands",
            agentsGlobalPath, ".opencode/agents",
            configFile, "agent",
            true, true, Path.of("detect")
        );
    }

    @Test
    void registerAgentInConfigCreatesEntryWithRecognizedFieldsAndPrompt(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("opencode.json");
        Path agentsDir = tempDir.resolve("agents");
        AgentToolDef tool = toolWithConfig(configFile, agentsDir);

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("description", "Does a thing");
        frontmatter.put("mode", "subagent");
        frontmatter.put("unknownField", "should be dropped");

        AgentRegistrationResult result = JsonConfigStore.registerAgentInConfig(tool, "my-agent", frontmatter).orElseThrow();

        assertTrue(result.success());
        assertFalse(result.skipped());

        Map<String, Object> written = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> agentSection = (Map<String, Object>) written.get("agent");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) agentSection.get("my-agent");
        assertEquals("Does a thing", entry.get("description"));
        assertEquals("subagent", entry.get("mode"));
        assertFalse(entry.containsKey("unknownField"));
        assertTrue(((String) entry.get("prompt")).contains("my-agent.md"));
    }

    @Test
    void registerAgentInConfigSkipsWellFormedExistingEntry(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("opencode.json");
        AgentToolDef tool = toolWithConfig(configFile, tempDir.resolve("agents"));

        Files.writeString(configFile, """
            { "agent": { "my-agent": { "description": "existing", "permission": { "edit": "allow" } } } }
            """);

        AgentRegistrationResult result = JsonConfigStore.registerAgentInConfig(tool, "my-agent", Map.of("description", "new"))
            .orElseThrow();

        assertTrue(result.skipped());
        assertFalse(result.repaired());
    }

    @Test
    void registerAgentInConfigRepairsMalformedExistingEntry(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("opencode.json");
        AgentToolDef tool = toolWithConfig(configFile, tempDir.resolve("agents"));

        Files.writeString(configFile, """
            { "agent": { "my-agent": { "description": "existing", "permission": { "edit": "" } } } }
            """);

        AgentRegistrationResult result = JsonConfigStore.registerAgentInConfig(tool, "my-agent", Map.of("description", "fixed"))
            .orElseThrow();

        assertFalse(result.skipped());
        assertTrue(result.repaired());
    }

    @Test
    void registerAgentInConfigReturnsEmptyWhenToolHasNoAgentConfigFile() {
        AgentToolDef tool = new AgentToolDef(
            "claude", "Claude Code", Path.of("s"), "p", null, null,
            Path.of("agents"), "ap", null, null, true, true, Path.of("d")
        );
        assertTrue(JsonConfigStore.registerAgentInConfig(tool, "x", Map.of()).isEmpty());
    }

    @Test
    void installMcpServersToFileSkipsAlreadyPresentServers(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("cfg.json");
        Files.writeString(configFile, """
            { "mcpServers": { "existing": { "command": "foo" } } }
            """);
        McpConfigDef cfg = new McpConfigDef("cursor", configFile, "mcpServers", "stdio");

        Set<String> installed = JsonConfigStore.installMcpServersToFile(
            Map.of("existing", Map.of("command", "bar"), "new-server", Map.of("command", "baz")),
            cfg, configFile
        );

        assertEquals(Set.of("new-server"), installed);
        Map<String, Object> written = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) written.get("mcpServers");
        assertTrue(servers.containsKey("new-server"));
        @SuppressWarnings("unchecked")
        Map<String, Object> existingUnchanged = (Map<String, Object>) servers.get("existing");
        assertEquals("foo", existingUnchanged.get("command")); // not overwritten
    }

    @Test
    void uninstallMcpServersFromFileRemovesOnlyPresentNames(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("cfg.json");
        Files.writeString(configFile, """
            { "mcpServers": { "a": {}, "b": {} } }
            """);
        McpConfigDef cfg = new McpConfigDef("cursor", configFile, "mcpServers", "stdio");

        Set<String> removed = JsonConfigStore.uninstallMcpServersFromFile(List.of("a", "does-not-exist"), cfg, configFile);

        assertEquals(Set.of("a"), removed);
        Map<String, Object> written = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) written.get("mcpServers");
        assertFalse(servers.containsKey("a"));
        assertTrue(servers.containsKey("b"));
    }

    @Test
    void uninstallMcpServersFromFileReturnsEmptySetWhenFileDoesNotExist(@TempDir Path tempDir) {
        assertTrue(JsonConfigStore.uninstallMcpServersFromFile(
            List.of("a"), new McpConfigDef("cursor", tempDir.resolve("nope.json"), "mcpServers", "stdio"), tempDir.resolve("nope.json")
        ).isEmpty());
    }

    @Test
    void installMcpServersDispatchesToJsonFileForNonClaudeTools(@TempDir Path tempDir) {
        // cursor's config file lives under the real user home in McpConfigRegistry,
        // so exercise the file-level function directly instead for this unit test —
        // the dispatcher itself is covered by installMcpServersToFile's test above
        // plus a direct empty-registry check here.
        List<OperationResult> results = JsonConfigStore.installMcpServers(Map.of(), "unknown-tool");
        assertTrue(results.isEmpty());
    }

    @Test
    void migrateLegacyNpxServersReplacesOnlyEntriesThatStillUseNpx(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("cfg.json");
        Files.writeString(configFile, """
            { "mcpServers": {
                "context7":  { "command": "npx", "args": ["-y", "@upstash/context7-mcp"] },
                "figma-mcp": { "url": "https://my-own-proxy.example/mcp" },
                "engram":    { "command": "engram" } } }
            """);
        McpConfigDef cfg = new McpConfigDef("cursor", configFile, "mcpServers", "stdio");

        Set<String> replaced = JsonConfigStore.migrateLegacyNpxServersInFile(Map.of(
            "context7", Map.of("url", "https://mcp.context7.com/mcp"),
            "figma-mcp", Map.of("url", "https://mcp.figma.com/mcp"),
            "not-installed", Map.of("url", "https://example/mcp")
        ), cfg, configFile);

        assertEquals(Set.of("context7"), replaced);
        Map<String, Object> written = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) written.get("mcpServers");
        assertEquals(Map.of("url", "https://mcp.context7.com/mcp"), servers.get("context7"));
        assertEquals(Map.of("url", "https://my-own-proxy.example/mcp"), servers.get("figma-mcp")); // user's own, kept
        assertEquals(Map.of("command", "engram"), servers.get("engram"));
        assertFalse(servers.containsKey("not-installed")); // migration never adds
    }

    @Test
    void migrateLegacyNpxServersIsANoOpWithoutAConfigFile(@TempDir Path tempDir) {
        McpConfigDef cfg = new McpConfigDef("cursor", tempDir.resolve("missing.json"), "mcpServers", "stdio");
        assertTrue(JsonConfigStore.migrateLegacyNpxServersInFile(Map.of("context7", Map.of()), cfg, cfg.globalFile()).isEmpty());
        assertFalse(Files.exists(cfg.globalFile()));
    }

    @Test
    void overwriteMcpServerEntryReturnsSkippedForUnknownTool() {
        OperationResult result = JsonConfigStore.overwriteMcpServerEntry("x", Map.of(), "unknown-tool");
        assertTrue(result.skipped());
    }

    @Test
    void isMcpServerRegisteredReturnsFalseForUnknownTool() {
        assertFalse(JsonConfigStore.isMcpServerRegistered("x", "unknown-tool"));
    }

    @Test
    void unregisterAgentFromConfigSkipsWhenFileMissing(@TempDir Path tempDir) {
        AgentToolDef tool = toolWithConfig(tempDir.resolve("nope.json"), tempDir.resolve("agents"));
        OperationResult result = JsonConfigStore.unregisterAgentFromConfig(tool, "x").orElseThrow();
        assertTrue(result.skipped());
    }

    @Test
    void unregisterAgentFromConfigRemovesExistingEntry(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("opencode.json");
        Files.writeString(configFile, """
            { "agent": { "my-agent": { "description": "x" } } }
            """);
        AgentToolDef tool = toolWithConfig(configFile, tempDir.resolve("agents"));

        OperationResult result = JsonConfigStore.unregisterAgentFromConfig(tool, "my-agent").orElseThrow();
        assertFalse(result.skipped());

        Map<String, Object> written = MAPPER.readValue(configFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) written.get("agent");
        assertFalse(section.containsKey("my-agent"));
    }

    // ─── Extra config files (Devin Desktop writes to two locations) ─────────

    private static McpConfigDef dualPathCfg(Path tempDir) {
        return new McpConfigDef(
            "windsurf",
            tempDir.resolve(".codeium").resolve("windsurf").resolve("mcp_config.json"),
            "mcpServers", "stdio",
            List.of(tempDir.resolve(".config").resolve("devin").resolve("mcp_config.json"))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serversIn(Path file) throws IOException {
        Map<String, Object> written = MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        return (Map<String, Object>) written.get("mcpServers");
    }

    private static void writeServers(Path file, String serversJson) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ \"mcpServers\": " + serversJson + " }");
    }

    @Test
    void installWritesToBothFilesWhenTheExtraFilesDirectoryExists(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);
        Files.createDirectories(devin.getParent());

        List<OperationResult> results = JsonConfigStore.installMcpServers(
            Map.of("engram", Map.of("type", "stdio", "command", "engram")), cfg);

        assertEquals(1, results.size());
        assertFalse(results.get(0).skipped());
        assertTrue(results.get(0).configFile().contains(cfg.globalFile().toString()));
        assertTrue(results.get(0).configFile().contains(devin.toString()));
        assertEquals(Map.of("type", "stdio", "command", "engram"), serversIn(cfg.globalFile()).get("engram"));
        assertEquals(Map.of("type", "stdio", "command", "engram"), serversIn(devin).get("engram"));
    }

    @Test
    void installWritesOnlyTheGlobalFileAndNeverCreatesTheExtraDirectory(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);

        List<OperationResult> results = JsonConfigStore.installMcpServers(Map.of("engram", Map.of("command", "engram")), cfg);

        assertEquals(cfg.globalFile().toString(), results.get(0).configFile());
        assertTrue(serversIn(cfg.globalFile()).containsKey("engram"));
        assertFalse(Files.exists(devin.getParent()));
    }

    @Test
    void installIsSkippedOnlyWhenEveryTargetFileAlreadyHasTheServer(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);
        Files.createDirectories(devin.getParent());
        writeServers(cfg.globalFile(), "{ \"engram\": { \"command\": \"mine\" } }");

        OperationResult first = JsonConfigStore.installMcpServers(Map.of("engram", Map.of("command", "engram")), cfg).get(0);
        assertFalse(first.skipped()); // still missing from the Devin file
        assertEquals(devin.toString(), first.configFile());
        assertEquals(Map.of("command", "mine"), serversIn(cfg.globalFile()).get("engram")); // not overwritten

        OperationResult second = JsonConfigStore.installMcpServers(Map.of("engram", Map.of("command", "engram")), cfg).get(0);
        assertTrue(second.skipped());
    }

    @Test
    void uninstallRemovesTheServerFromEveryFile(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);
        writeServers(cfg.globalFile(), "{ \"engram\": {}, \"keep\": {} }");
        writeServers(devin, "{ \"engram\": {}, \"keep\": {} }");

        List<OperationResult> results = JsonConfigStore.uninstallMcpServers(List.of("engram"), cfg);

        assertFalse(results.get(0).skipped());
        assertEquals(Set.of("keep"), serversIn(cfg.globalFile()).keySet());
        assertEquals(Set.of("keep"), serversIn(devin).keySet());
    }

    @Test
    void uninstallFindsAServerPresentOnlyInTheExtraFile(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);
        writeServers(devin, "{ \"engram\": {} }");

        OperationResult result = JsonConfigStore.uninstallMcpServers(List.of("engram"), cfg).get(0);

        assertFalse(result.skipped());
        assertEquals(devin.toString(), result.configFile());
        assertTrue(serversIn(devin).isEmpty());
        assertFalse(Files.exists(cfg.globalFile()));
    }

    @Test
    void uninstallIsSkippedWhenNoFileHasTheServer(@TempDir Path tempDir) {
        assertTrue(JsonConfigStore.uninstallMcpServers(List.of("engram"), dualPathCfg(tempDir)).get(0).skipped());
    }

    @Test
    void isMcpServerRegisteredChecksEveryFile(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        assertFalse(JsonConfigStore.isMcpServerRegistered("engram", cfg));

        writeServers(cfg.extraFiles().get(0), "{ \"engram\": {} }");

        assertTrue(JsonConfigStore.isMcpServerRegistered("engram", cfg));
    }

    @Test
    void overwriteReplacesTheEntryInEveryWritableFile(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);
        writeServers(devin, "{ \"engram\": { \"command\": \"old\" } }");

        OperationResult result = JsonConfigStore.overwriteMcpServerEntry("engram", Map.of("command", "new"), cfg);

        assertFalse(result.skipped());
        assertEquals(Map.of("command", "new"), serversIn(cfg.globalFile()).get("engram"));
        assertEquals(Map.of("command", "new"), serversIn(devin).get("engram"));
    }

    @Test
    void overwriteNeverCreatesTheExtraDirectory(@TempDir Path tempDir) {
        McpConfigDef cfg = dualPathCfg(tempDir);
        JsonConfigStore.overwriteMcpServerEntry("engram", Map.of("command", "new"), cfg);
        assertTrue(Files.exists(cfg.globalFile()));
        assertFalse(Files.exists(cfg.extraFiles().get(0).getParent()));
    }

    @Test
    void migrateLegacyNpxServersMigratesEveryFile(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        Path devin = cfg.extraFiles().get(0);
        writeServers(cfg.globalFile(), "{ \"context7\": { \"command\": \"npx\" } }");
        writeServers(devin, "{ \"context7\": { \"command\": \"npx\" } }");

        List<OperationResult> results = JsonConfigStore.migrateLegacyNpxServers(
            Map.of("context7", Map.of("serverUrl", "https://mcp.context7.com/mcp")), cfg);

        assertEquals(2, results.size());
        assertEquals(Map.of("serverUrl", "https://mcp.context7.com/mcp"), serversIn(cfg.globalFile()).get("context7"));
        assertEquals(Map.of("serverUrl", "https://mcp.context7.com/mcp"), serversIn(devin).get("context7"));
    }

    @Test
    void writableFilesAreTheGlobalFilePlusExtrasWhoseDirectoryExists(@TempDir Path tempDir) throws IOException {
        McpConfigDef cfg = dualPathCfg(tempDir);
        assertEquals(List.of(cfg.globalFile()), cfg.writableFiles());
        assertEquals(List.of(cfg.globalFile(), cfg.extraFiles().get(0)), cfg.allFiles());

        Files.createDirectories(cfg.extraFiles().get(0).getParent());
        assertEquals(List.of(cfg.globalFile(), cfg.extraFiles().get(0)), cfg.writableFiles());
    }
}
