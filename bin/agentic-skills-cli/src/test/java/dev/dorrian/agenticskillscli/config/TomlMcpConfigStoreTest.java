package dev.dorrian.agenticskillscli.config;

import dev.dorrian.agenticskillscli.TomlTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;
import static dev.dorrian.agenticskillscli.registry.McpServerConfig.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlMcpConfigStoreTest {

    private static final String KEY = "mcp_servers";

    /** A realistic user config: root keys, comments everywhere, a profile table, a user MCP with a sub-table. */
    private static final String USER_CONFIG = """
        # My Codex config — keep this comment!
        model = "gpt-5"   # trailing comment
        approval_policy = "on-request"

        [profiles.fast]
        model = "gpt-5-mini"

        # user's own server
        [mcp_servers.mine]
        command = "my-server"
        args = [
          "--flag", # inline comment inside array
          "x",
        ]

        [mcp_servers.mine.env]
        TOKEN = "keep-me"

        [tui]
        notifications = true
        """;

    private static Map<String, Object> engram() {
        return of("command", "engram", "args", list("mcp"));
    }

    private static Map<String, Object> javaServer() {
        return of("command", "java", "args", list("-jar", "/home/u/x.jar"),
            "env_vars", list("AZURE_DEVOPS_ACCOUNTS_B64", "GITHUB_ACCOUNTS_B64"));
    }

    private static Map<String, Object> remote() {
        return of("url", "https://mcp.context7.com/mcp", "http_headers", of("Authorization", "Bearer k\"ey\\"));
    }

    private static Map<String, Object> servers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engram", engram());
        m.put("context7", remote());
        m.put("issue-tickets", javaServer());
        return m;
    }

    @Test
    void installCreatesFileAndDirectoryWhenMissing(@TempDir Path tmp) {
        Path file = tmp.resolve(".codex/config.toml");
        Set<String> installed = TomlMcpConfigStore.installIfAbsent(KEY, servers(), file);
        assertEquals(Set.of("engram", "context7", "issue-tickets"), installed);

        Map<String, Object> parsed = TomlTestSupport.parse(read(file));
        assertEquals(Map.of("command", "engram", "args", List.of("mcp")), TomlTestSupport.table(parsed, KEY, "engram"));
        assertEquals(Map.of("url", "https://mcp.context7.com/mcp",
                "http_headers", Map.of("Authorization", "Bearer k\"ey\\")),
            TomlTestSupport.table(parsed, KEY, "context7"));
        assertEquals(Map.of("command", "java", "args", List.of("-jar", "/home/u/x.jar"),
                "env_vars", List.of("AZURE_DEVOPS_ACCOUNTS_B64", "GITHUB_ACCOUNTS_B64")),
            TomlTestSupport.table(parsed, KEY, "issue-tickets"));
    }

    @Test
    void readOnlyAndUninstallOperationsNeverCreateTheFile(@TempDir Path tmp) {
        Path file = tmp.resolve(".codex/config.toml");
        assertFalse(TomlMcpConfigStore.isRegistered(KEY, "engram", file));
        assertTrue(TomlMcpConfigStore.serverNames(KEY, file).isEmpty());
        assertTrue(TomlMcpConfigStore.uninstall(KEY, List.of("engram"), file).isEmpty());
        assertTrue(TomlMcpConfigStore.migrateLegacyNpx(KEY, servers(), file).isEmpty());
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void appendPreservesExistingBytesAndIsIdempotent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, USER_CONFIG);

        TomlMcpConfigStore.installIfAbsent(KEY, servers(), file);
        String after = read(file);
        assertTrue(after.startsWith(USER_CONFIG), after);
        TomlTestSupport.parse(after);

        Set<String> second = TomlMcpConfigStore.installIfAbsent(KEY, servers(), file);
        assertTrue(second.isEmpty());
        assertEquals(after, read(file));
    }

    @Test
    void installSkipsServerTheUserAlreadyDefined(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, USER_CONFIG);
        Set<String> installed = TomlMcpConfigStore.installIfAbsent(KEY, Map.of("mine", engram()), file);
        assertTrue(installed.isEmpty());
        assertEquals(USER_CONFIG, read(file));
    }

    @Test
    void appendToFileWithoutTrailingNewlineStaysValid(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "model = \"gpt-5\"");
        TomlMcpConfigStore.installIfAbsent(KEY, Map.of("engram", engram()), file);
        Map<String, Object> parsed = TomlTestSupport.parse(read(file));
        assertEquals("gpt-5", parsed.get("model"));
        assertTrue(read(file).startsWith("model = \"gpt-5\""));
    }

    @Test
    void installThenUninstallRestoresOriginalByteForByte(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, USER_CONFIG);
        TomlMcpConfigStore.installIfAbsent(KEY, servers(), file);

        Set<String> removed = TomlMcpConfigStore.uninstall(KEY, List.of("engram", "context7", "issue-tickets", "absent"), file);
        assertEquals(Set.of("engram", "context7", "issue-tickets"), removed);
        assertEquals(USER_CONFIG, read(file));
    }

    @Test
    void uninstallRemovesSubTablesAndKeepsSurroundingComments(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, USER_CONFIG);

        assertEquals(Set.of("mine"), TomlMcpConfigStore.uninstall(KEY, List.of("mine"), file));
        String after = read(file);
        assertEquals("""
            # My Codex config — keep this comment!
            model = "gpt-5"   # trailing comment
            approval_policy = "on-request"

            [profiles.fast]
            model = "gpt-5-mini"

            # user's own server

            [tui]
            notifications = true
            """, after);
        Map<String, Object> parsed = TomlTestSupport.parse(after);
        assertTrue(parsed.get(KEY) == null);
    }

    @Test
    void overwriteReplacesInPlaceIncludingSubTables(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, USER_CONFIG);

        TomlMcpConfigStore.overwrite(KEY, "mine", engram(), file);
        String after = read(file);
        Map<String, Object> parsed = TomlTestSupport.parse(after);
        assertEquals(Map.of("command", "engram", "args", List.of("mcp")), TomlTestSupport.table(parsed, KEY, "mine"));
        // Everything outside the replaced table is untouched, and the table kept its position.
        assertTrue(after.startsWith(USER_CONFIG.substring(0, USER_CONFIG.indexOf("[mcp_servers.mine]"))), after);
        assertTrue(after.endsWith("\n[tui]\nnotifications = true\n"), after);
        assertFalse(after.contains("keep-me"));
    }

    @Test
    void overwriteAppendsWhenAbsent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, USER_CONFIG);
        TomlMcpConfigStore.overwrite(KEY, "engram", engram(), file);
        assertTrue(read(file).startsWith(USER_CONFIG));
        assertTrue(TomlMcpConfigStore.isRegistered(KEY, "engram", file));
    }

    @Test
    void handlesQuotedTableNamesAndOtherDefinitionStyles(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        String content = """
            [mcp_servers]
            inline = { command = "c", args = [
              "x",
            ] }
            dotted.command = "a"

            [mcp_servers."figma-mcp"]
            url = "http://127.0.0.1:3845/mcp"

            [ mcp_servers . 'weird name' ] # comment
            command = "b"
            """;
        Files.writeString(file, content);
        TomlTestSupport.parse(content);

        assertEquals(Set.of("dotted", "figma-mcp", "weird name", "inline"), TomlMcpConfigStore.serverNames(KEY, file));
        assertTrue(TomlMcpConfigStore.isRegistered(KEY, "figma-mcp", file));

        assertEquals(Set.of("figma-mcp", "dotted", "inline", "weird name"),
            TomlMcpConfigStore.uninstall(KEY, List.of("figma-mcp", "dotted", "inline", "weird name"), file));
        String after = read(file);
        TomlTestSupport.parse(after);
        assertTrue(TomlMcpConfigStore.serverNames(KEY, file).isEmpty(), after);
        assertTrue(after.contains("[mcp_servers]"), after);
    }

    @Test
    void nameNeedingQuotesIsEmittedQuoted(@TempDir Path tmp) {
        Path file = tmp.resolve("config.toml");
        TomlMcpConfigStore.installIfAbsent(KEY, Map.of("my server.v2", engram()), file);
        assertTrue(read(file).contains("[mcp_servers.\"my server.v2\"]"), read(file));
        assertEquals(Set.of("my server.v2"), TomlMcpConfigStore.serverNames(KEY, file));
        assertTrue(TomlTestSupport.table(TomlTestSupport.parse(read(file)), KEY, "my server.v2") != null);
    }

    @Test
    void headerLikeLinesInsideMultilineStringsAreNotTables(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        String content = """
            instructions = '''
            [mcp_servers.fake]
            command = "nope"
            '''
            """;
        Files.writeString(file, content);
        assertTrue(TomlMcpConfigStore.serverNames(KEY, file).isEmpty());
        TomlMcpConfigStore.installIfAbsent(KEY, Map.of("fake", engram()), file);
        Map<String, Object> parsed = TomlTestSupport.parse(read(file));
        assertEquals("engram", TomlTestSupport.table(parsed, KEY, "fake").get("command"));
    }

    @Test
    void migrateLegacyNpxReplacesOnlyNpxEntries(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, """
            [mcp_servers.context7]
            command = "npx"
            args = ["-y", "@upstash/context7-mcp"]

            [mcp_servers.figma-mcp]
            url = "https://custom.example/mcp"
            """);
        Map<String, Object> wanted = new LinkedHashMap<>();
        wanted.put("context7", remote());
        wanted.put("figma-mcp", of("url", "https://mcp.figma.com/mcp"));
        wanted.put("engram", engram());

        assertEquals(Set.of("context7"), TomlMcpConfigStore.migrateLegacyNpx(KEY, wanted, file));
        Map<String, Object> parsed = TomlTestSupport.parse(read(file));
        assertEquals("https://mcp.context7.com/mcp", TomlTestSupport.table(parsed, KEY, "context7").get("url"));
        assertEquals("https://custom.example/mcp", TomlTestSupport.table(parsed, KEY, "figma-mcp").get("url"));
        assertTrue(TomlTestSupport.table(parsed, KEY, "engram") == null);
    }

    @Test
    void renderEmitsBooleansAndEscapesControlCharacters() {
        String toml = TomlMcpConfigStore.render(KEY, "x", of("enabled", true, "cmd", "a\tb\nc\u0001"));
        Map<String, Object> parsed = TomlTestSupport.parse(toml);
        assertEquals(true, TomlTestSupport.table(parsed, KEY, "x").get("enabled"));
        assertEquals("a\tb\nc\u0001", TomlTestSupport.table(parsed, KEY, "x").get("cmd"));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
